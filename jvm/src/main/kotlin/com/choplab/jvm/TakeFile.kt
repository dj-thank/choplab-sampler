package com.choplab.jvm

import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * A recorded take on its way into the asset store: float samples go straight into a private float WAV file (named
 * `take-*`) as they arrive, up to [maxFrames]; [finish] completes its header and moves it into the store as a verified
 * asset, so the take is never held twice on disk. One recording thread writes; [finish] or [discard] runs once, after
 * it stopped.
 */
class TakeFile(scratch: Path, private val sampleRate: Int, private val channels: Int, private val maxFrames: Long,
               reserved: PcmMemoryBudget.Reservation? = null) {
    init { require(sampleRate in 8_000..192_000 && channels in 1..2 && maxFrames > 0) }
    // Synchronous construction is worker-only. VoiceTakes supplies its cancellable pre-reservation.
    private val reservation = reserved ?: kotlinx.coroutines.runBlocking { PcmMemoryBudget.shared.reserve(MEMORY_BYTES) }
    private var file: Path? = null
    private var channel: FileChannel? = null
    private var output: BufferedOutputStream? = null
    private var bytes: ByteArray? = null
    @Volatile var frames = 0L
        private set
    val full: Boolean get() = frames >= maxFrames
    /** Whether any sample was not silence: an input that delivers only zeros (muted, or not allowed) recorded nothing. */
    private var heard = false

    init {
        // The header goes in last, once the length is known.
        try {
            file = Files.createDirectories(scratch).resolve("take-${UUID.randomUUID()}.wav")
            channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            output = BufferedOutputStream(Channels.newOutputStream(channel!!), 1 shl 16)
            bytes = ByteArray(8192)
            output!!.write(ByteArray(HEADER_BYTES))
        } catch (failure: Throwable) { discard(); throw failure }
    }

    /** Appends [count] interleaved samples and drops what no longer fits; a sample that is not finite becomes silence. */
    fun write(samples: FloatArray, count: Int, offset: Int = 0) {
        require(offset in 0..samples.size && count in 0..samples.size - offset && count % channels == 0 && offset % channels == 0)
        val bytes = checkNotNull(bytes)
        val output = checkNotNull(output)
        val kept = minOf((maxFrames - frames) * channels, count.toLong()).toInt()
        var at = 0
        for (index in 0 until kept) {
            val sample = samples[offset + index].let { if (it.isFinite()) it else 0f }
            if (sample != 0f) heard = true
            val bits = sample.toRawBits()
            bytes[at] = bits.toByte(); bytes[at + 1] = (bits ushr 8).toByte()
            bytes[at + 2] = (bits ushr 16).toByte(); bytes[at + 3] = (bits ushr 24).toByte()
            at += 4
            if (at == bytes.size) { output.write(bytes, 0, at); at = 0 }
        }
        if (at > 0) output.write(bytes, 0, at)
        frames += kept / channels
    }

    /** Stores the take as a float WAV asset called [name]; null when nothing but silence was recorded. */
    fun finish(store: FileAssetStore, name: String): Asset? {
        try {
            val output = checkNotNull(output)
            val channel = checkNotNull(channel)
            val file = checkNotNull(file)
            output.flush()
            if (frames == 0L || !heard) return null
            val data = frames * channels * 4
            val header = ByteArrayOutputStream(HEADER_BYTES).also { WavCodec.floatHeader(it, data, sampleRate, channels) }.toByteArray()
            check(header.size == HEADER_BYTES)
            val buffer = ByteBuffer.wrap(header)
            while (buffer.hasRemaining()) channel.write(buffer, buffer.position().toLong())
            channel.force(true)
            output.close()
            val hash = Files.newInputStream(file).use { digest(it, HEADER_BYTES + data) }
            val asset = Asset(hash, "wav", HEADER_BYTES + data, sampleRate, channels, frames, name, AssetRole.ORIGINAL)
            store.adopt(asset, file)
            return asset
        } finally { close() }
    }

    /** Drops the take and its file. */
    fun discard() = close()

    private fun close() {
        try { output?.close() } catch (_: Exception) { }
        finally {
            try { channel?.close(); file?.let(Files::deleteIfExists) }
            finally { output = null; channel = null; bytes = null; file = null; reservation.close() }
        }
    }

    companion object {
        private const val HEADER_BYTES = 44
        // WAV stream + conversion window + header copies + worker digest window.
        const val MEMORY_BYTES = 65_536L + 8192 + 88 + 8192
    }
}
