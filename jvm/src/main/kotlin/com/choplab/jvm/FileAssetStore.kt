package com.choplab.jvm

import com.choplab.core.AssetStore
import com.choplab.core.model.Asset
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Content-addressed asset publication. Existing corrupt bytes are preserved for diagnosis. No GC. */
class FileAssetStore(directory: Path, val maxStoredBytes: Long = ProjectLimits.MAX_TOTAL_BYTES,
                     private val decoder: OriginalAudioDecoder? = null) : AssetStore {
    val directory: Path
    private val lock: Any
    init {
        require(maxStoredBytes in 1..ProjectLimits.MAX_TOTAL_BYTES)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory))
        this.directory = directory.toRealPath()
        lock = StoreLocks.forPath(this.directory)
    }
    override suspend fun containsVerified(asset: Asset): Boolean = withContext(Dispatchers.IO) {
        val context = coroutineContext
        synchronized(lock) { verified(asset) { context[kotlinx.coroutines.Job]?.isActive == false } }
    }
    override suspend fun write(asset: Asset, bytes: ByteArray) = withContext(Dispatchers.IO) { publish(asset, ByteArrayInputStream(bytes)); Unit }
    override suspend fun read(asset: Asset): ByteArray = withContext(Dispatchers.IO) { synchronized(lock) {
        require(verified(asset)) { "Asset unavailable or corrupt" }
        Files.newInputStream(path(asset)).use { readBounded(it, asset.byteCount).also { bytes -> require(bytes.size.toLong() == asset.byteCount && sha256(bytes) == asset.hash) } }
    } }

    fun publish(asset: Asset, input: InputStream, cancelled: () -> Boolean = { false }) = synchronized(lock) {
        val target = path(asset)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(verified(asset)) { "Existing asset is corrupt" }
            require(digest(input, asset.byteCount) == asset.hash) { "Incoming asset hash mismatch" }
            return@synchronized
        }
        require(storedBytes() + asset.byteCount <= maxStoredBytes) { "Asset store quota exceeded" }
        val pending = directory.resolve(".pending-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(pending.toFile()).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    require(!cancelled()) { "Asset publication cancelled" }
                    val n = input.read(buffer); require(n != 0) { "Stalled asset input" }; if (n < 0) break
                    count += n; require(count <= asset.byteCount)
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                require(count == asset.byteCount && digest.digest().hex() == asset.hash) { "Asset hash or size mismatch" }
                output.fd.sync()
            }
            validateAudio(asset, pending, cancelled)
            require(!cancelled()) { "Asset publication cancelled" }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(pending) }
    }
    /** What the store holds now, as [publish] and [adopt] count it against [maxStoredBytes]. */
    fun storedBytes(): Long = synchronized(lock) {
        Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum() }
    }
    /**
     * Stores [file], a finished audio file nobody else writes, as [asset]: checked as [publish] checks its input, then
     * moved into place, or copied where it lives on another file system. [file] is gone afterwards in every case.
     */
    fun adopt(asset: Asset, file: Path, cancelled: () -> Boolean = { false }) = synchronized(lock) {
        try {
            val target = path(asset)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                require(verified(asset)) { "Existing asset is corrupt" }
                return@synchronized
            }
            require(storedBytes() + asset.byteCount <= maxStoredBytes) { "Asset store quota exceeded" }
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == asset.byteCount) { "Asset size mismatch" }
            require(Files.newInputStream(file).use { digest(it, asset.byteCount) } == asset.hash) { "Asset hash mismatch" }
            validateAudio(asset, file, cancelled)
            require(!cancelled()) { "Asset publication cancelled" }
            val pending = directory.resolve(".pending-${UUID.randomUUID()}")
            try {
                try { Files.move(file, pending, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: IOException) {
                    Files.copy(file, pending)
                    FileChannel.open(pending, StandardOpenOption.WRITE).use { it.force(true) }
                }
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(pending) }
        } finally { Files.deleteIfExists(file) }
    }
    fun verified(asset: Asset, cancelled: () -> Boolean = { false }): Boolean = synchronized(lock) {
        val target = path(asset)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != asset.byteCount) return@synchronized false
        try {
            val actual = Files.newInputStream(target).use { digest(it, asset.byteCount) }
            if (actual != asset.hash) false else { validateAudio(asset, target, cancelled); true }
        } catch (_: IOException) { false } catch (_: IllegalArgumentException) { false }
    }
    fun openVerified(asset: Asset): InputStream = synchronized(lock) {
        require(verified(asset)) { "Asset unavailable or corrupt" }
        Files.newInputStream(path(asset))
    }
    private fun path(asset: Asset): Path = directory.resolve("${asset.hash}.${asset.extension}").also { require(it.parent == directory) }
    /** Worker-only decoder input; each handoff rechecks content identity, size and audio metadata. */
    fun verifiedPath(asset: Asset, cancelled: () -> Boolean = { false }): Path = synchronized(lock) {
        require(verified(asset, cancelled)) { "Asset unavailable or corrupt" }
        path(asset)
    }
    private fun validateAudio(asset: Asset, path: Path, cancelled: () -> Boolean = { false }) {
        if (asset.extension != "wav") {
            require(asset.role == com.choplab.core.model.AssetRole.ORIGINAL)
            val info = requireNotNull(decoder) { "A verified host decoder is required for this codec" }
                .inspect(path, asset.hash, cancelled)
            require(info.frames == asset.frames && info.sampleRate == asset.sampleRate && info.channels == asset.channels) { "Audio metadata mismatch" }
            return
        }
        Files.newInputStream(path).use { input ->
            val info = WavCodec.inspect(input)
            require(info.frames == asset.frames && info.sampleRate == asset.sampleRate && info.channels == asset.channels) { "WAV metadata mismatch" }
            require(asset.role == com.choplab.core.model.AssetRole.ORIGINAL || info.floatingPoint) { "Rendered/cache audio must retain float PCM" }
        }
    }
}

internal object StoreLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun forPath(path: Path): Any = locks.computeIfAbsent(path.toAbsolutePath().normalize().toString().lowercase()) { Any() }
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun digest(input: InputStream, expected: Long): String {
    val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192); var count = 0L
    while (true) {
        val n = input.read(buffer); require(n != 0); if (n < 0) break
        count += n; require(count <= expected); hash.update(buffer, 0, n)
    }
    require(count == expected); return hash.digest().hex()
}
internal fun readBounded(input: InputStream, maximum: Long): ByteArray {
    require(maximum in 0..ProjectLimits.MAX_TOTAL_BYTES)
    val output = ByteArrayOutputStream(minOf(maximum, 8192L).toInt()); val buffer = ByteArray(8192); var count = 0L
    while (true) {
        val n = input.read(buffer); require(n != 0); if (n < 0) break
        count += n; require(count <= maximum) { "Input exceeds byte limit" }; output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
