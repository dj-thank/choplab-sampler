package com.choplab.jvm.inception

import com.choplab.core.inception.*
import com.choplab.core.model.requireId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

/** Small independent local store. No caches, autosave generations or remote copies retain deleted text. */
class FileInceptionStore(
    directory: Path,
    private val maximumBytes: Long = 8L * 1024 * 1024,
    private val usableBytes: (() -> Long)? = null,
    private val beforePublish: (() -> Unit)? = null,
) : InceptionStore {
    private val root = directory.toAbsolutePath().normalize()
    private val local = locks.computeIfAbsent(root) { ReentrantLock() }
    private fun path(id: String): Path { requireId(id); return root.resolve("$id.inception") }
    private fun fail(code: InceptionProblem): Nothing = throw InceptionException(code)
    private suspend fun <T> locked(body: () -> T): T = withContext(Dispatchers.IO) {
        ensureActive()
        try {
            local.withLock {
                Files.createDirectories(root)
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) fail(InceptionProblem.STORAGE)
                val lock = root.resolve(".store.lock")
                if (Files.isSymbolicLink(lock)) fail(InceptionProblem.STORAGE)
                FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use { body() }
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (e: InceptionException) { throw e }
        catch (_: IllegalArgumentException) { fail(InceptionProblem.CORRUPT) }
        catch (_: Exception) { fail(InceptionProblem.STORAGE) }
    }
    private fun entries(): List<Path> = Files.list(root).use { it.limit(258).toList() }.also { all ->
        if (all.size > 256 || all.any { Files.isSymbolicLink(it) || !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) fail(InceptionProblem.CAPACITY)
    }
    override suspend fun list(): List<InceptionWorkspace> = locked {
        val docs = entries().filter { it.fileName.toString().endsWith(".inception") }
        if (docs.size > 32) fail(InceptionProblem.CAPACITY)
        docs.map(::read).sortedBy { it.id }
    }
    override suspend fun load(id: String): InceptionWorkspace? = locked {
        val file = path(id)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) read(file) else null
    }
    override suspend fun save(document: InceptionWorkspace, expectedRevision: Long?) {
        val context = coroutineContext
        locked {
            context.ensureActive()
            val file = path(document.id)
            val old = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) read(file) else null
            if (old?.revision != expectedRevision || (old == null && expectedRevision != null)) fail(InceptionProblem.STALE)
            if (old != null && document.revision != old.revision + 1) fail(InceptionProblem.STALE)
            val payload = InceptionJson.encode(document).toByteArray(Charsets.UTF_8)
            val bytes = (MAGIC + digest(payload) + "\n").toByteArray(Charsets.US_ASCII) + payload
            val remnants = entries().filter { it.fileName.toString().startsWith("${document.id}.pending-") }
            remnants.forEach { read(it, document.id) }
            remnants.forEach(Files::delete)
            val files = entries()
            if (old == null && files.count { it.fileName.toString().endsWith(".inception") } >= 32) fail(InceptionProblem.CAPACITY)
            if (files.sumOf { Files.size(it) } + bytes.size > maximumBytes ||
                (usableBytes?.invoke() ?: root.toFile().usableSpace) < bytes.size + 4096L) fail(InceptionProblem.CAPACITY)
            val pending = root.resolve("${document.id}.pending-${UUID.randomUUID()}")
            try {
                FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) { context.ensureActive(); channel.write(buffer) }
                    channel.force(true)
                }
                if (read(pending, document.id) != document) fail(InceptionProblem.CORRUPT)
                beforePublish?.invoke(); context.ensureActive()
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(pending) }
        }
    }
    override suspend fun delete(id: String, expectedRevision: Long) {
        locked {
            val file = path(id)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || read(file).revision != expectedRevision) fail(InceptionProblem.STALE)
            // Only complete, validated store-owned remnants can be removed. Unknown bytes prevent a false deletion claim.
            val pending = entries().filter { it.fileName.toString().startsWith("$id.pending-") }
            pending.forEach { read(it, id) }
            pending.forEach(Files::delete)
            Files.delete(file)
        }
    }
    private fun read(file: Path, expectedId: String = file.fileName.toString().removeSuffix(".inception")): InceptionWorkspace {
        if (Files.isSymbolicLink(file) || Files.size(file) !in 1..(InceptionLimits.MAX_BYTES + 128).toLong()) fail(InceptionProblem.CORRUPT)
        val bytes = Files.readAllBytes(file)
        val prefix = MAGIC.toByteArray(Charsets.US_ASCII)
        val headerSize = prefix.size + 65
        if (bytes.size <= headerSize || !bytes.copyOfRange(0, prefix.size).contentEquals(prefix) || bytes[headerSize - 1] != 10.toByte()) fail(InceptionProblem.CORRUPT)
        val payload = bytes.copyOfRange(headerSize, bytes.size)
        if (String(bytes, prefix.size, 64, Charsets.US_ASCII) != digest(payload)) fail(InceptionProblem.CORRUPT)
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()
        return InceptionJson.decode(raw).also { if (it.id != expectedId) fail(InceptionProblem.CORRUPT) }
    }
    companion object {
        private const val MAGIC = "CHOPLAB-INCEPTION-1\n"
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
