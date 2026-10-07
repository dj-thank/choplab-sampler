package com.choplab.apple

import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.engine.MixerProgram
import com.choplab.engine.PcmQuantizer

internal interface ByteInput { fun read(buffer: ByteArray, offset: Int, length: Int): Int }
internal interface ByteOutput { fun write(buffer: ByteArray, offset: Int, length: Int) }

internal class ArrayInput(private val bytes: ByteArray) : ByteInput {
    private var position = 0
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}
internal fun FileReader.asInput() = object : ByteInput {
    override fun read(buffer: ByteArray, offset: Int, length: Int) = this@asInput.read(buffer, offset, length)
}
internal fun FileWriter.asOutput() = object : ByteOutput {
    override fun write(buffer: ByteArray, offset: Int, length: Int) = this@asOutput.write(buffer, offset, length)
}
/** Growable in-memory output, for small documents and tests. */
internal class ArrayOutput(initial: Int = 1024) : ByteOutput {
    private var bytes = ByteArray(initial)
    var size = 0
        private set
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (size + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
        buffer.copyInto(bytes, size, offset, offset + length)
        size += length
    }
    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

internal data class WavInfo(val sampleRate: Int, val channels: Int, val frames: Long, val bits: Int, val floatingPoint: Boolean)
internal class WavAudio(val info: WavInfo, val samples: FloatArray)

/**
 * RIFF/WAVE PCM16, PCM24 and IEEE float32, with the same acceptance rules, limits and byte layout as the JVM
 * hosts' WavCodec, so an asset written on one host is verified identically on another.
 */
internal object IosWav {
    const val MAX_EXPORT_WAV_BYTES = 44L + (ProjectLimits.MAX_TIMELINE_FRAMES + MixerProgram.MAX_TAIL_FRAMES) * 2 * 4

    fun inspect(input: ByteInput, maxBytes: Long = ProjectLimits.MAX_ASSET_BYTES): WavInfo = parse(input, maxBytes, 0, false).info
    fun read(input: ByteInput, maxBytes: Long = ProjectLimits.MAX_ASSET_BYTES, maxDecodedBytes: Long = EngineFormat.MAX_RESIDENT_BYTES): WavAudio =
        parse(input, maxBytes, maxDecodedBytes, true)

    /** Known-length 32-bit float stream (rendered/recorded assets). Float writes never dither. */
    class FloatWriter(private val output: ByteOutput, private val frames: Long, sampleRate: Int = 48_000, private val channels: Int = 2) {
        private val buffer = ByteArray(16_384)
        private var written = 0L
        private var finished = false
        init {
            require(frames in 1..ProjectLimits.MAX_FRAMES && sampleRate in 8_000..192_000 && channels in 1..2)
            require(44 + frames * channels * 4 <= ProjectLimits.MAX_ASSET_BYTES)
            header(output, frames * channels * 4, sampleRate, channels, 32, 3)
        }
        fun write(samples: FloatArray, offsetFrames: Int = 0, frameCount: Int = samples.size / channels - offsetFrames) {
            check(!finished)
            require(samples.size % channels == 0) { "Incomplete float PCM frame" }
            require(offsetFrames >= 0 && frameCount >= 0 && (offsetFrames.toLong() + frameCount) * channels <= samples.size)
            require(written + frameCount <= frames)
            val start = offsetFrames * channels
            val end = start + frameCount * channels
            var bytes = 0
            for (index in start until end) {
                val value = samples[index]
                require(value.isFinite())
                val bits = value.toRawBits()
                buffer[bytes++] = bits.toByte(); buffer[bytes++] = (bits ushr 8).toByte()
                buffer[bytes++] = (bits ushr 16).toByte(); buffer[bytes++] = (bits ushr 24).toByte()
                if (bytes == buffer.size) { output.write(buffer, 0, bytes); bytes = 0 }
            }
            if (bytes > 0) output.write(buffer, 0, bytes)
            written += frameCount
        }
        fun finish() { check(!finished && written == frames) { "WAV frame count mismatch" }; finished = true }
    }

    /** The only integer quantization boundary for export: seeded TPDF, as on every host. */
    class PcmWriter(private val output: ByteOutput, val frames: Long, sampleRate: Int = 48_000, private val channels: Int = 2,
                    bits: Int = 24, seed: Int = 1, dither: Boolean = true, bufferFrames: Int = 4096) {
        private val sampleBytes = bits / 8
        private val quantizer = PcmQuantizer(seed, bits, dither)
        private val buffer: ByteArray
        private var written = 0L
        private var finished = false
        init {
            require(frames in 1..(ProjectLimits.MAX_TIMELINE_FRAMES + MixerProgram.MAX_TAIL_FRAMES) && sampleRate in 8_000..192_000 && channels in 1..2)
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
            if (frames * channels * sampleBytes and 1L != 0L) output.write(byteArrayOf(0), 0, 1)
            finished = true
        }
    }

    private fun header(output: ByteOutput, bytes: Long, rate: Int, channels: Int, bits: Int, format: Int) {
        require(bytes + 44 + (bytes and 1L) <= MAX_EXPORT_WAV_BYTES)
        val head = ByteArray(44)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> head[at + i] = c.code.toByte() }
        fun le16(at: Int, value: Int) { head[at] = value.toByte(); head[at + 1] = (value ushr 8).toByte() }
        fun le32(at: Int, value: Long) { for (i in 0 until 4) head[at + i] = (value ushr (i * 8)).toByte() }
        ascii(0, "RIFF"); le32(4, 36 + bytes + (bytes and 1L)); ascii(8, "WAVEfmt "); le32(16, 16)
        le16(20, format); le16(22, channels); le32(24, rate.toLong()); le32(28, rate.toLong() * channels * bits / 8)
        le16(32, channels * bits / 8); le16(34, bits); ascii(36, "data"); le32(40, bytes)
        output.write(head, 0, head.size)
    }

    private fun parse(input: ByteInput, maxBytes: Long, maxDecodedBytes: Long, allocate: Boolean): WavAudio {
        require(maxBytes in 44..maxOf(ProjectLimits.MAX_ASSET_BYTES, MAX_EXPORT_WAV_BYTES))
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
                    require(format == null && info == null && (size == 16L || size == 18L))
                    val fmt = input.exact(size.toInt())
                    val tag = fmt.u16(0); val channels = fmt.u16(2); val rate = fmt.u32(4); val bits = fmt.u16(14)
                    require((tag == 1 && (bits == 16 || bits == 24)) || (tag == 3 && bits == 32)) { "Unsupported WAV encoding" }
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
                    require(frames in 1..(if (maxBytes > ProjectLimits.MAX_ASSET_BYTES)
                        ProjectLimits.MAX_TIMELINE_FRAMES + MixerProgram.MAX_TAIL_FRAMES else ProjectLimits.MAX_FRAMES + 480_000))
                    val samples = frames * fmt.channels
                    require(!allocate || samples * 4 <= maxDecodedBytes) { "Decoded WAV exceeds residency limit" }
                    if (allocate) data = FloatArray(samples.toInt())
                    var offset = 0L
                    while (offset < samples) {
                        val count = minOf((buffer.size / sampleBytes).toLong(), samples - offset).toInt()
                        input.exactInto(buffer, count * sampleBytes)
                        for (i in 0 until count) {
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
            if (size and 1L != 0L) { input.exactInto(buffer, 1); consumed++ }
        }
        require(input.read(buffer, 0, 1) == -1) { "Trailing WAV bytes" }
        return WavAudio(requireNotNull(info) { "Missing WAV data" }, data)
    }
}

internal fun ByteInput.exact(count: Int): ByteArray = ByteArray(count).also { exactInto(it, count) }
internal fun ByteInput.exactInto(buffer: ByteArray, count: Int) {
    var offset = 0
    while (offset < count) { val n = read(buffer, offset, count - offset); require(n > 0) { "Truncated or stalled input" }; offset += n }
}
internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
internal fun ByteArray.ascii(at: Int, length: Int): String = (at until at + length).map { this[it].toInt().toChar() }.joinToString("")
