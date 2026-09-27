package com.choplab.jvm

import com.choplab.core.ImportPort
import com.choplab.core.Location
import com.choplab.core.model.Asset
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Worker-only host codec. Callers verify the bytes against [hash] before reusing decoded metadata.
 * Decoding retains the source rate, channel identity and float precision; the shared PCM port resamples.
 */
interface OriginalAudioDecoder : Closeable {
    fun inspect(path: Path, hash: String, cancelled: () -> Boolean = { false }): WavInfo
    fun decode(path: Path, hash: String, cancelled: () -> Boolean = { false }): WavAudio
    override fun close() = Unit
}

/** Snapshot the selected file once. The encoded original, never a lossy intermediate, enters the store. */
class OriginalAudioImportPort(
    private val assets: FileAssetStore,
    private val resolve: (Location) -> Path,
    private val decoder: OriginalAudioDecoder,
) : ImportPort {
    private val wav = WavImportPort(assets, resolve)
    override suspend fun import(location: Location): Asset = withContext(Dispatchers.IO) {
        val source = resolve(location)
        val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension == "wav") return@withContext wav.import(location)
        require(extension in EXTENSIONS) { "Unsupported audio format" }
        require(Files.isRegularFile(source) && Files.size(source) in 1..ProjectLimits.MAX_ASSET_BYTES)
        val pending = Files.createTempFile("choplab-import-", ".pending")
        val context = coroutineContext
        val cancelled = { context[Job]?.isActive == false }
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(source).use { input -> Files.newOutputStream(pending).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(n > 0)
                    count += n
                    require(count <= ProjectLimits.MAX_ASSET_BYTES) { "Audio exceeds the byte limit" }
                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                }
            } }
            val digest = hash.digest().hex()
            val info = decoder.inspect(pending, digest, cancelled)
            context.ensureActive()
            java.nio.channels.FileChannel.open(pending, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            val asset = Asset(digest, extension, count, info.sampleRate, info.channels, info.frames,
                source.fileName.toString().replace(':', '_').take(256))
            // adopt validates the hash and metadata again, counts the bytes once and removes the snapshot.
            assets.adopt(asset, pending, cancelled)
            context.ensureActive()
            asset
        } finally { Files.deleteIfExists(pending) }
    }

    companion object { val EXTENSIONS: List<String> = Asset.EXTENSIONS }
}
