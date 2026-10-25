package com.choplab.apple

import com.choplab.core.AssetStore
import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Content-addressed asset publication in the app container, with the JVM store's rules: a file is published only
 * after its size, SHA-256 and audio metadata match the asset, by rename from a synced pending file. Existing corrupt
 * bytes are kept for diagnosis and never served. No garbage collection.
 */
internal class IosAssetStore(directory: String, private val maxStoredBytes: Long = ProjectLimits.MAX_TOTAL_BYTES,
    val memory: PcmAdmission = PcmAdmission()) : AssetStore {
    val directory = canonicalDirectory(directory)
    private val lock = HostLock()
    private val pending = mutableMapOf<String, Long>()

    init { require(maxStoredBytes in 1..ProjectLimits.MAX_TOTAL_BYTES) }

    override suspend fun containsVerified(asset: Asset): Boolean = withContext(Dispatchers.IO) { verified(asset) }
    override suspend fun write(asset: Asset, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() == asset.byteCount && sha256(bytes) == asset.hash) { "Asset hash or size mismatch" }
        val pending = pendingPath(asset.byteCount)
        try {
            FileWriter(pending, maxBytes = asset.byteCount).use { writer -> writer.write(bytes); writer.syncAndClose() }
            adopt(asset, pending)
        } finally { discardPending(pending) }
    }
    override suspend fun read(asset: Asset): ByteArray = withContext(Dispatchers.IO) {
        require(verified(asset)) { "Asset unavailable or corrupt" }
        readFile(path(asset), asset.byteCount).also { require(it.size.toLong() == asset.byteCount && sha256(it) == asset.hash) }
    }

    /** Publishes [file], a finished file nobody else writes, as [asset]. [file] is gone afterwards in every case. */
    fun adopt(asset: Asset, file: String, cancelled: () -> Boolean = { false }) = lock.withLock {
        require(file in pending && pending[file] == asset.byteCount) { "Unowned staging file" }
        try {
            val target = path(asset)
            require(regularFileSize(file) == asset.byteCount) { "Asset size mismatch" }
            require(sha256File(file, asset.byteCount) == asset.hash) { "Asset hash mismatch" }
            validateAudio(asset, file)
            if (regularFileSize(target) != null) {
                require(verifiedLocked(asset)) { "Existing asset is corrupt" }
                return@withLock
            }
            require(accountedBytes() <= maxStoredBytes) { "Asset store quota exceeded" }
            require(!cancelled()) { "Asset publication cancelled" }
            // Same volume (the app container): rename publishes atomically.
            renameReplacing(file, target)
        } finally { pending.remove(file); deleteFile(file) }
    }

    fun verified(asset: Asset): Boolean = lock.withLock { verifiedLocked(asset) }

    /** Worker-only decoder input; the file's identity is rechecked before every handoff. */
    fun verifiedPath(asset: Asset): String = lock.withLock {
        require(verifiedLocked(asset)) { "Asset unavailable or corrupt" }
        path(asset)
    }

    fun storedBytes(): Long = lock.withLock { directoryBytes(directory) }

    /** Reserve before opening the file. Unknown/crashed pending files remain counted and preserved. */
    fun pendingPath(bytes: Long): String = lock.withLock {
        require(bytes in 1..ProjectLimits.MAX_ASSET_BYTES)
        require(bytes <= maxStoredBytes - accountedBytes()) { "Asset store quota exceeded" }
        requireDiskSpace(directory, bytes)
        ("$directory/." + uniqueName("pending-")).also { pending[it] = bytes }
    }

    fun discardPending(file: String) = lock.withLock { if (pending.remove(file) != null) deleteFile(file) }

    private fun accountedBytes(): Long = directoryBytes(directory) + pending.entries.sumOf { (path, size) ->
        (size - (regularFileSize(path) ?: 0L)).coerceAtLeast(0L)
    }

    private fun verifiedLocked(asset: Asset): Boolean {
        val target = path(asset)
        val size = regularFileSize(target) ?: return false
        if (size != asset.byteCount) return false
        return try {
            if (sha256File(target, asset.byteCount) != asset.hash) false
            else { validateAudio(asset, target); true }
        } catch (_: Exception) { false }
    }

    private fun validateAudio(asset: Asset, file: String) {
        val info = memory.reserve(512 * 1024).use { IosAudioDecoder.validate(file, asset.extension) }
        require(info.frames == asset.frames && info.sampleRate == asset.sampleRate && info.channels == asset.channels) { "Audio metadata mismatch" }
        if (asset.extension != "wav") { require(asset.role == AssetRole.ORIGINAL); return }
        if (asset.role != AssetRole.ORIGINAL) {
            val wav = FileReader(file).use { IosWav.inspect(it.asInput()) }
            require(wav.floatingPoint) { "Rendered/cache audio must retain float PCM" }
        }
    }

    private fun path(asset: Asset): String = "$directory/${asset.hash}.${asset.extension}"
}
