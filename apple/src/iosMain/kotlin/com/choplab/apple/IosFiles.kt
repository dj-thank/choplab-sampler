package com.choplab.apple

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.*
import platform.posix.*

/** App-container paths. Nothing here enters a document: projects only hold content hashes. */
internal object IosPaths {
    fun applicationSupport(): String = directory(NSApplicationSupportDirectory)
    /** Shown in the Files app (UIFileSharingEnabled), where saved projects and exported WAVs land. */
    fun documents(): String = directory(NSDocumentDirectory)
    fun temporary(): String = NSTemporaryDirectory().trimEnd('/')
    private fun directory(kind: NSSearchPathDirectory): String {
        val url = NSFileManager.defaultManager.URLsForDirectory(kind, NSUserDomainMask).first() as NSURL
        return requireNotNull(url.path).also { ensureDirectory(it) }
    }
}

internal fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    require(isDirectory(path)) { "Directory unavailable" }
}

internal fun isDirectory(path: String): Boolean = memScoped {
    val info = alloc<stat>()
    lstat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFDIR
}

/** Size of a regular file that is not a symbolic link, or null. */
internal fun regularFileSize(path: String): Long? = memScoped {
    val info = alloc<stat>()
    if (lstat(path, info.ptr) != 0 || (info.st_mode.toInt() and S_IFMT) != S_IFREG) null else info.st_size
}

internal fun deleteFile(path: String) { unlink(path) }

internal fun listFiles(directory: String): List<String> =
    (NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) ?: emptyList<Any?>()).mapNotNull { it as? String }

/** Atomic within one volume: readers see the old or the new file, never a partial one. */
internal fun renameReplacing(from: String, to: String) { require(rename(from, to) == 0) { "Rename failed (${errno})" } }

internal fun uniqueName(prefix: String): String = prefix + NSUUID().UUIDString

/** Sequential reads of one file. */
internal class FileReader(path: String) : AutoCloseable {
    private val fd = open(path, O_RDONLY or O_NOFOLLOW).also { require(it >= 0) { "Cannot open file" } }
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        while (true) {
            val count = buffer.usePinned { platform.posix.read(fd, it.addressOf(offset), length.convert()) }.toInt()
            if (count >= 0) return if (count == 0) -1 else count
            require(errno == EINTR) { "Read failed" }
        }
    }
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var done = 0
        while (done < length) {
            val count = read(buffer, offset + done, length - done)
            require(count > 0) { "Truncated file" }
            done += count
        }
    }
    fun seek(position: Long) { require(lseek(fd, position, SEEK_SET) == position) { "Seek failed" } }
    override fun close() { platform.posix.close(fd) }
}

/** Sequential writes; [syncAndClose] flushes to storage before the file may be published by rename. */
internal class FileWriter(path: String, exclusive: Boolean = true) : AutoCloseable {
    private val fd = open(path, O_WRONLY or O_CREAT or O_NOFOLLOW or (if (exclusive) O_EXCL else O_TRUNC), S_IRUSR or S_IWUSR)
        .also { require(it >= 0) { "Cannot create file" } }
    private var open = true
    var written = 0L
        private set
    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        var done = 0
        while (done < length) {
            val count = buffer.usePinned { platform.posix.write(fd, it.addressOf(offset + done), (length - done).convert()) }.toInt()
            if (count < 0) { require(errno == EINTR) { "Write failed" }; continue }
            done += count
        }
        written += length
    }
    fun syncAndClose() {
        check(open)
        require(fsync(fd) == 0) { "Sync failed" }
        open = false
        require(platform.posix.close(fd) == 0) { "Close failed" }
    }
    override fun close() { if (open) { open = false; platform.posix.close(fd) } }
}

/** Reads at most [maximum] bytes; larger files are refused before allocation. */
internal fun readFile(path: String, maximum: Long): ByteArray {
    val size = requireNotNull(regularFileSize(path)) { "Not a regular file" }
    require(size <= maximum && size <= Int.MAX_VALUE) { "File exceeds limit" }
    val bytes = ByteArray(size.toInt())
    FileReader(path).use { it.readFully(bytes) }
    return bytes
}

/** Writes [bytes] to a sibling pending file, syncs, then renames it over [path]. */
internal fun writeFileAtomically(path: String, bytes: ByteArray) {
    val pending = path.substringBeforeLast('/') + "/." + uniqueName("pending-")
    try {
        FileWriter(pending).use { writer -> writer.write(bytes); writer.syncAndClose() }
        renameReplacing(pending, path)
    } finally { deleteFile(pending) }
}

/** Incremental SHA-256 (CommonCrypto). Lowercase hex, as content hashes are stored. */
internal class Sha256 {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
    private var finished = false
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        check(!finished)
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length > 0) bytes.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(offset), length.convert()) }
    }
    fun hex(): String {
        check(!finished)
        finished = true
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256_Final(it.addressOf(0), context.ptr) }
        nativeHeap.free(context)
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

internal fun sha256(bytes: ByteArray): String = Sha256().apply { update(bytes) }.hex()

/** Hashes a file without loading it; [expectedBytes] guards against a file that changed size. */
internal fun sha256File(path: String, expectedBytes: Long): String {
    val hash = Sha256()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    FileReader(path).use { reader ->
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedBytes) { "File grew while hashing" }
            hash.update(buffer, 0, count)
        }
    }
    require(total == expectedBytes) { "File size changed" }
    return hash.hex()
}

/** A single-owner mutual-exclusion lock for host state touched from worker and UI threads. */
internal class HostLock {
    private val lock = NSLock()
    fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
