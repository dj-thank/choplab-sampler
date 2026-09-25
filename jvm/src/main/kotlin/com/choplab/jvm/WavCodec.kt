package com.choplab.jvm

import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.engine.PcmQuantizer
import java.io.InputStream
import java.io.OutputStream

data class WavInfo(val sampleRate: Int, val channels: Int, val frames: Long, val bits: Int, val floatingPoint: Boolean)
data class WavAudio(val info: WavInfo, val samples: FloatArray)

/** RIFF/WAVE PCM16, PCM24 and IEEE float32. Size and channel identity are checked before allocation. */
object WavCodec {
    /** Standalone 30-minute stereo PCM24 export plus the maximum explicit tail. Asset budgets are separate. */
    const val MAX_EXPORT_WAV_BYTES = 44L + (ProjectLimits.MAX_TIMELINE_FRAMES + 480_000) * 2 * 3
    /** Reuses one quantizer and byte buffer for a known-length PCM stream. Does not own output. */
    class PcmWriter(
        private val output: OutputStream,
        val frames: Long,
        sampleRate: Int = 48_000,
        private val channels: Int = 2,
        bits: Int = 24,
        seed: Int = 1,
        dither: Boolean = true,
        bufferFrames: Int = 4096,
    ) {
        private val sampleBytes = bits / 8
        private val quantizer = PcmQuantizer(seed, bits, dither)
        private val buffer: ByteArray
        private var written = 0L
        private var finished = false
        val bufferBytes: Int get() = buffer.size
        init {
            require(frames in 1..(ProjectLimits.MAX_TIMELINE_FRAMES + 480_000) && sampleRate in 8_000..192_000 && channels in 1..2)
            require(bufferFrames in 1..65_536)
            buffer = ByteArray(bufferFrames * channels * sampleBytes)
            header(output, frames * channels * sampleBytes, sampleRate, channels, bits, 1)
        }
        fun write(samples: FloatArray, offsetFrames: Int = 0, frameCount: Int = samples.size / channels - offsetFrames) {
            check(!finished)
            require(offsetFrames >= 0 && frameCount >= 0 && (offsetFrames.toLong() + frameCount) * channels <= samples.size)
            require(written + frameCount <= frames)
            var offset = offsetFrames * channels
            var left = frameCount * channels
            while (left > 0) {
                val count = minOf(left, buffer.size / sampleBytes)
                quantizer.encode(samples, buffer, offset, count)
                output.write(buffer, 0, count * sampleBytes)
                offset += count; left -= count
            }
            written += frameCount
        }
        fun finish() {
            check(!finished && written == frames) { "WAV frame count mismatch" }
            if (frames * channels * sampleBytes and 1L != 0L) output.write(0)
            finished = true
        }
    }
    fun inspect(input: InputStream, maxBytes: Long = ProjectLimits.MAX_ASSET_BYTES): WavInfo = parse(input, maxBytes, 0, false).info
    fun read(input: InputStream, maxBytes: Long = ProjectLimits.MAX_ASSET_BYTES, maxDecodedBytes: Long = EngineFormat.MAX_RESIDENT_BYTES): WavAudio = parse(input, maxBytes, maxDecodedBytes, true)

    fun writeFloat(output: OutputStream, samples: FloatArray, sampleRate: Int = 48_000, channels: Int = 2) {
        validateSamples(samples, sampleRate, channels)
        header(output, samples.size.toLong() * 4, sampleRate, channels, 32, 3)
        val buffer = ByteArray(8192)
        var count = 0
        for (sample in samples) {
            val bits = sample.toRawBits()
            repeat(4) { buffer[count++] = (bits ushr (it * 8)).toByte() }
            if (count == buffer.size) { output.write(buffer); count = 0 }
        }
        if (count > 0) output.write(buffer, 0, count)
    }
    /** The only integer quantization boundary; defaults to seeded TPDF. Float writes never dither. */
    fun writePcm(output: OutputStream, samples: FloatArray, sampleRate: Int = 48_000, channels: Int = 2, bits: Int = 24, seed: Int = 1, dither: Boolean = true) {
        validateSamples(samples, sampleRate, channels)
        PcmWriter(output, samples.size.toLong() / channels, sampleRate, channels, bits, seed, dither).apply { write(samples); finish() }
    }
    private fun validateSamples(samples: FloatArray, rate: Int, channels: Int) {
        require(rate in 8_000..192_000 && channels in 1..2)
        require(samples.isNotEmpty() && samples.size % channels == 0 && samples.size.toLong() / channels <= ProjectLimits.MAX_FRAMES)
        require(samples.size.toLong() * 4 <= EngineFormat.MAX_RESIDENT_BYTES && samples.all { it.isFinite() })
    }
    private fun header(output: OutputStream, bytes: Long, rate: Int, channels: Int, bits: Int, format: Int) {
        require(bytes + 44 + (bytes and 1L) <= MAX_EXPORT_WAV_BYTES)
        output.ascii("RIFF"); output.le32(36 + bytes + (bytes and 1L)); output.ascii("WAVEfmt "); output.le32(16)
        output.le16(format); output.le16(channels); output.le32(rate.toLong()); output.le32(rate.toLong() * channels * bits / 8)
        output.le16(channels * bits / 8); output.le16(bits); output.ascii("data"); output.le32(bytes)
    }
    private fun parse(input: InputStream, maxBytes: Long, maxDecodedBytes: Long, allocate: Boolean): WavAudio {
        require(maxBytes in 44..ProjectLimits.MAX_ASSET_BYTES)
        require(!allocate || maxDecodedBytes in 4..EngineFormat.MAX_RESIDENT_BYTES)
        val head = input.exact(12)
        require(head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE") { "Not RIFF WAVE" }
        val total = head.u32(4) + 8
        require(total in 44..maxBytes)
        var consumed = 12L
        var format: WavInfo? = null
        var info: WavInfo? = null
        var data = FloatArray(0)
        val buffer = ByteArray(8192)
        while (consumed < total) {
            require(total - consumed >= 8)
            val chunk = input.exact(8); consumed += 8
            val name = chunk.ascii(0, 4)
            val size = chunk.u32(4)
            require(size + (size and 1L) <= total - consumed) { "Chunk exceeds RIFF size" }
            when (name) {
                "fmt " -> {
                    require(format == null && info == null && size in setOf(16L, 18L))
                    val fmt = input.exact(size.toInt())
                    val tag = fmt.u16(0); val channels = fmt.u16(2); val rate = fmt.u32(4); val bits = fmt.u16(14)
                    require((tag == 1 && bits in setOf(16, 24)) || (tag == 3 && bits == 32)) { "Unsupported WAV encoding" }
                    require(channels in 1..2 && rate in 8_000..192_000)
                    require(fmt.u16(12) == channels * bits / 8 && fmt.u32(8) == rate * channels * bits / 8)
                    require(size == 16L || fmt.u16(16) == 0)
                    format = WavInfo(rate.toInt(), channels, 0, bits, tag == 3)
                }
                "data" -> {
                    val fmt = requireNotNull(format) { "fmt must precede data" }
                    require(info == null)
                    val sampleBytes = fmt.bits / 8
                    val alignment = fmt.channels * sampleBytes
                    require(size > 0 && size % alignment == 0L)
                    val frames = size / alignment
                    require(frames in 1..(ProjectLimits.MAX_FRAMES + 480_000))
                    val samples = frames * fmt.channels
                    require(!allocate || samples * 4 <= maxDecodedBytes) { "Decoded WAV exceeds residency limit" }
                    if (allocate) data = FloatArray(samples.toInt())
                    var offset = 0L
                    while (offset < samples) {
                        val count = minOf((buffer.size / sampleBytes).toLong(), samples - offset).toInt()
                        input.exactInto(buffer, count * sampleBytes)
                        repeat(count) { i ->
                            val at = i * sampleBytes
                            val value = when {
                                fmt.floatingPoint -> Float.fromBits(buffer.u32(at).toInt()).also { require(it.isFinite()) { "Non-finite WAV sample" } }
                                fmt.bits == 16 -> buffer.u16(at).toShort().toFloat() / 32768f
                                else -> {
                                    val raw = (buffer[at].toInt() and 255) or ((buffer[at + 1].toInt() and 255) shl 8) or (buffer[at + 2].toInt() shl 16)
                                    raw.toFloat() / 8388608f
                                }
                            }
                            if (allocate) data[offset.toInt() + i] = value
                        }
                        offset += count
                    }
                    info = fmt.copy(frames = frames)
                }
                else -> {
                    var left = size
                    while (left > 0) { val count = minOf(left, buffer.size.toLong()).toInt(); input.exactInto(buffer, count); left -= count }
                }
            }
            consumed += size
            if (size and 1L != 0L) { require(input.read() >= 0); consumed++ }
        }
        require(input.read() == -1) { "Trailing WAV bytes" }
        return WavAudio(requireNotNull(info) { "Missing WAV data" }, data)
    }
}

internal fun InputStream.exact(count: Int): ByteArray = ByteArray(count).also { exactInto(it, count) }
internal fun InputStream.exactInto(buffer: ByteArray, count: Int) {
    var offset = 0
    while (offset < count) { val n = read(buffer, offset, count - offset); require(n > 0) { "Truncated or stalled input" }; offset += n }
}
internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
internal fun ByteArray.ascii(at: Int, length: Int): String = String(this, at, length, Charsets.US_ASCII)
internal fun OutputStream.ascii(value: String) { write(value.toByteArray(Charsets.US_ASCII)) }
internal fun OutputStream.le16(value: Int) { write(value and 255); write((value ushr 8) and 255) }
internal fun OutputStream.le32(value: Long) { repeat(4) { write(((value ushr (it * 8)) and 255).toInt()) } }
