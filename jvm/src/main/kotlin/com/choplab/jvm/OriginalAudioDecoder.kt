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
    /** Hosts supply a bounded reader for long material. There is deliberately no whole-RAM fallback. */
    fun openPcm(path: Path, hash: String, cancelled: () -> Boolean = { false }): PcmFrameSource =
        throw UnsupportedOperationException("This host has no bounded PCM reader")
    override fun close() = Unit
}

/** Snapshot the selected file once. The encoded original, never a lossy intermediate, enters the store. */
class OriginalAudioImportPort(
    private val assets: FileAssetStore,
    private val resolve: (Location) -> Path,
    private val decoder: OriginalAudioDecoder,
    private val displayName: (Location) -> String? = { null },
) : ImportPort {
    private val wav = WavImportPort(assets, resolve, displayName)
    override suspend fun import(location: Location): Asset = withContext(Dispatchers.IO) {
        val source = resolve(location)
        val extension = audioExtension(source, displayName(location) ?: source.fileName.toString())
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
                (displayName(location) ?: source.fileName.toString()).replace(':', '_').take(256))
            // adopt validates the hash and metadata again, counts the bytes once and removes the snapshot.
            assets.adopt(asset, pending, cancelled)
            context.ensureActive()
            asset
        } finally { Files.deleteIfExists(pending) }
    }

    companion object { val EXTENSIONS: List<String> = Asset.EXTENSIONS }
}

/** Container signature for a stream picker without a useful filename; no file path enters the document. */
internal fun audioExtension(path: Path, name: String): String {
    val named = name.substringAfterLast('.', "").lowercase()
    if (named in OriginalAudioImportPort.EXTENSIONS) return named
    val header = ByteArray(16)
    val count = Files.newInputStream(path).use { it.read(header) }
    require(count >= 4) { "Unknown audio container" }
    fun at(first: Int, length: Int) = if (count >= first + length) String(header, first, length, Charsets.US_ASCII) else ""
    return when {
        at(0, 4) == "RIFF" && at(8, 4) == "WAVE" -> "wav"
        at(0, 4) == "fLaC" -> "flac"
        at(0, 4) == "OggS" -> "ogg"
        at(4, 4) == "ftyp" -> "m4a"
        at(0, 4) == "FORM" -> "aiff"
        header.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) -> "webm"
        at(0, 3) == "ID3" -> "mp3"
        (header[0].toInt() and 255) == 255 && (header[1].toInt() and 0xf6) == 0xf0 -> "aac"
        (header[0].toInt() and 255) == 255 && (header[1].toInt() and 0xe0) == 0xe0 -> "mp3"
        else -> error("Unknown audio container")
    }
}
