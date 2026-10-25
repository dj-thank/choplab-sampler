package com.choplab.apple

import com.choplab.core.inception.*
import com.choplab.core.model.requireId
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.posix.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Independent version-1 text store. No music archives, generations, remote copies or text caches. */
internal class IosInceptionStore(
    private val directory: String,
    private val maximumBytes: Long = 8L * 1024 * 1024,
    private val usableBytes: (() -> Long)? = null,
    private val beforePublish: (() -> Unit)? = null,
) : InceptionStore {
    init { require(maximumBytes >= 0) }
    private suspend fun <T> locked(body: (String, CoroutineContext) -> T): T = withContext(Dispatchers.Default) {
        try {
            ensureActive()
            val context = coroutineContext
            val root = canonicalDirectory(directory)
            val lock = registry.withLock { locks.getOrPut(root) { HostLock() } }
            lock.withLock {
                ensureActive()
                val fd = open("$root/.store.lock", O_CREAT or O_RDWR or O_NOFOLLOW or O_NONBLOCK, S_IRUSR or S_IWUSR)
                if (fd < 0) fail(InceptionProblem.STORAGE)
                try {
                    memScoped {
                        val info = alloc<stat>()
                        if (fstat(fd, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT) != S_IFREG) fail(InceptionProblem.STORAGE)
                    }
                    val deadline = monotonicNanos() + 1_000_000_000
                    while (flock(fd, LOCK_EX or LOCK_NB) != 0) {
                        if (errno != EWOULDBLOCK && errno != EAGAIN) fail(InceptionProblem.STORAGE)
                        ensureActive()
                        if (monotonicNanos() >= deadline) fail(InceptionProblem.BUSY)
                        NSThread.sleepForTimeInterval(0.005)
                    }
                    try { body(root, context) } finally { flock(fd, LOCK_UN) }
                } finally { platform.posix.close(fd) }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: InceptionException) { throw failure }
        catch (_: Exception) { fail(InceptionProblem.STORAGE) }
    }

    override suspend fun list(): List<InceptionWorkspace> = locked { root, _ ->
        val documents = entries(root).filter { it.endsWith(".inception") }
        if (documents.size > MAX_DOCUMENTS) fail(InceptionProblem.CAPACITY)
        documents.map { read("$root/$it", it.removeSuffix(".inception")) }.sortedBy { it.id }
    }

    override suspend fun load(id: String): InceptionWorkspace? = locked { root, _ ->
        val file = path(root, id)
        if (exists(file)) read(file, id) else null
    }

    override suspend fun save(document: InceptionWorkspace, expectedRevision: Long?) {
        locked { root, context ->
            val file = path(root, document.id)
            val old = if (exists(file)) read(file, document.id) else null
            if (old?.revision != expectedRevision || (old == null && expectedRevision != null) ||
                (old != null && document.revision != old.revision + 1)) fail(InceptionProblem.STALE)
            val payload = try { InceptionJson.encode(document).encodeToByteArray() }
                catch (_: IllegalArgumentException) { fail(InceptionProblem.CAPACITY) }
            val bytes = (MAGIC + sha256(payload) + "\n").encodeToByteArray() + payload
            val remnants = entries(root).filter { it.startsWith("${document.id}.pending-") }
            remnants.forEach { read("$root/$it", document.id) }
            remnants.forEach { remove("$root/$it") }
            val files = entries(root)
            if (old == null && files.count { it.endsWith(".inception") } >= MAX_DOCUMENTS) fail(InceptionProblem.CAPACITY)
            val occupied = files.fold(0L) { total, name ->
                val size = requireNotNull(regularFileSize("$root/$name"))
                if (size < 0 || size > maximumBytes - total) fail(InceptionProblem.CAPACITY)
                total + size
            }
            if (bytes.size > maximumBytes - occupied || available(root) < bytes.size + 4096L) fail(InceptionProblem.CAPACITY)
            val pending = "$root/${document.id}.pending-${NSUUID().UUIDString}"
            try {
                context.ensureActive()
                FileWriter(pending, maxBytes = bytes.size.toLong()).use { it.write(bytes); it.syncAndClose() }
                if (read(pending, document.id) != document) fail(InceptionProblem.CORRUPT)
                beforePublish?.invoke(); context.ensureActive()
                renameReplacing(pending, file)
            } finally { if (exists(pending)) remove(pending) }
        }
    }

    override suspend fun delete(id: String, expectedRevision: Long) {
        locked { root, context ->
            val file = path(root, id)
            if (!exists(file) || read(file, id).revision != expectedRevision) fail(InceptionProblem.STALE)
            val remnants = entries(root).filter { it.startsWith("$id.pending-") }
            // Validate everything before removing any text. Unknown/corrupt copies stay for recovery.
            remnants.forEach { read("$root/$it", id) }
            context.ensureActive()
            remnants.forEach { remove("$root/$it") }
            remove(file)
        }
    }

    private fun path(root: String, id: String): String {
        try { requireId(id) } catch (_: IllegalArgumentException) { fail(InceptionProblem.INVALID) }
        return "$root/$id.inception"
    }
    private fun entries(root: String): List<String> = listFiles(root).also { names ->
        if (names.size > MAX_FILES) fail(InceptionProblem.CAPACITY)
        if (names.any { it == "." || it == ".." || '/' in it || regularFileSize("$root/$it") == null }) fail(InceptionProblem.CORRUPT)
    }
    private fun available(root: String): Long = usableBytes?.invoke() ?: run {
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(root, null) ?: fail(InceptionProblem.STORAGE)
        (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: fail(InceptionProblem.STORAGE)
    }
    private fun read(file: String, expectedId: String): InceptionWorkspace {
        try {
            val bytes = readFile(file, (InceptionLimits.MAX_BYTES + 128).toLong())
            val prefix = MAGIC.encodeToByteArray()
            val headerSize = prefix.size + 65
            if (bytes.size <= headerSize || !bytes.copyOfRange(0, prefix.size).contentEquals(prefix) || bytes[headerSize - 1] != 10.toByte()) fail(InceptionProblem.CORRUPT)
            val payload = bytes.copyOfRange(headerSize, bytes.size)
            if (bytes.decodeToString(prefix.size, prefix.size + 64, throwOnInvalidSequence = true) != sha256(payload)) fail(InceptionProblem.CORRUPT)
            return InceptionJson.decode(payload.decodeToString(throwOnInvalidSequence = true)).also {
                if (it.id != expectedId) fail(InceptionProblem.CORRUPT)
            }
        } catch (failure: InceptionException) { throw failure }
        catch (_: Exception) { fail(InceptionProblem.CORRUPT) }
    }
    private fun exists(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        if (lstat(path, info.ptr) == 0) true else if (errno == ENOENT) false else fail(InceptionProblem.STORAGE)
    }
    private fun remove(path: String) { if (unlink(path) != 0) fail(InceptionProblem.STORAGE) }

    private companion object {
        const val MAGIC = "CHOPLAB-INCEPTION-1\n"
        const val MAX_DOCUMENTS = 32
        const val MAX_FILES = 256
        val registry = HostLock()
        val locks = mutableMapOf<String, HostLock>()
        fun fail(problem: InceptionProblem): Nothing = throw InceptionException(problem)
    }
}
