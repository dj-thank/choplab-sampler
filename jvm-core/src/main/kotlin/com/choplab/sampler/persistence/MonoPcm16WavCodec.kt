package com.choplab.sampler.persistence

import java.io.InputStream
import java.io.OutputStream

/** Strict canonical mono/stereo WAV codec used inside `.choplab` archives. */
internal object Pcm16WavCodec {
    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / Byte.SIZE_BITS

    fun write(output: OutputStream, samples: ShortArray, sampleRate: Int, channelCount: Int) {
        require(channelCount in 1..2) { "WAVのチャンネル数に対応していません" }
        require(samples.size % channelCount == 0) { "音声データに不完全なPCMフレームがあります" }
        val blockAlign = Math.multiplyExact(channelCount, BYTES_PER_SAMPLE)
        val dataBytes = Math.multiplyExact(samples.size, BYTES_PER_SAMPLE)
        output.writeAscii("RIFF")
        output.writeLittleEndianInt(Math.addExact(36, dataBytes))
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(PCM_FORMAT)
        output.writeLittleEndianShort(channelCount)
        output.writeLittleEndianInt(sampleRate)
        output.writeLittleEndianInt(Math.multiplyExact(sampleRate, blockAlign))
        output.writeLittleEndianShort(blockAlign)
        output.writeLittleEndianShort(BITS_PER_SAMPLE)
        output.writeAscii("data")
        output.writeLittleEndianInt(dataBytes)

        val buffer = ByteArray(8 * 1024)
        var sampleIndex = 0
        while (sampleIndex < samples.size) {
            val count = minOf(buffer.size / BYTES_PER_SAMPLE, samples.size - sampleIndex)
            for (offset in 0 until count) {
                val value = samples[sampleIndex + offset].toInt()
                buffer[offset * 2] = (value and 0xFF).toByte()
                buffer[offset * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            output.write(buffer, 0, count * BYTES_PER_SAMPLE)
            sampleIndex += count
        }
    }

    fun read(
        input: InputStream,
        expectedFrames: Int,
        expectedSampleRate: Int,
        expectedChannelCount: Int,
    ): ShortArray {
        require(expectedChannelCount in 1..2) { "WAVのチャンネル数に対応していません" }
        val header = input.readExactly(HEADER_BYTES)
        require(header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WAVE") {
            "音声entryはWAV形式ではありません"
        }
        require(header.ascii(12, 4) == "fmt " && header.littleEndianInt(16) == 16) {
            "WAVのfmt chunkが不正です"
        }
        require(header.littleEndianShort(20) == PCM_FORMAT) { "WAVはPCM形式ではありません" }
        require(header.littleEndianShort(22) == expectedChannelCount) { "WAVのチャンネル数が一致しません" }
        require(header.littleEndianInt(24) == expectedSampleRate) { "WAVのsample rateが一致しません" }
        val expectedBlockAlign = Math.multiplyExact(expectedChannelCount, BYTES_PER_SAMPLE)
        require(header.littleEndianInt(28) == expectedSampleRate * expectedBlockAlign) {
            "WAVのbyte rateが不正です"
        }
        require(header.littleEndianShort(32) == expectedBlockAlign) { "WAVのblock alignが不正です" }
        require(header.littleEndianShort(34) == BITS_PER_SAMPLE) { "WAVはPCM 16-bitではありません" }
        require(header.ascii(36, 4) == "data") { "WAVのdata chunkがありません" }

        val expectedSamples = Math.multiplyExact(expectedFrames, expectedChannelCount)
        val expectedDataBytes = Math.multiplyExact(expectedSamples, BYTES_PER_SAMPLE)
        require(header.littleEndianInt(4) == 36 + expectedDataBytes) { "WAVのRIFFサイズが一致しません" }
        require(header.littleEndianInt(40) == expectedDataBytes) { "WAVのdataサイズが一致しません" }

        val pcm = input.readExactly(expectedDataBytes)
        return ShortArray(expectedSamples) { index ->
            val offset = index * BYTES_PER_SAMPLE
            val low = pcm[offset].toInt() and 0xFF
            val high = pcm[offset + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            require(count > 0) { "音声データが途中で終わっています" }
            offset += count
        }
        return result
    }

    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}

/** Compatibility facade for schema 2–6, whose audio contract is always mono. */
internal object MonoPcm16WavCodec {
    fun write(output: OutputStream, samples: ShortArray, sampleRate: Int) {
        Pcm16WavCodec.write(output, samples, sampleRate, channelCount = 1)
    }

    fun read(input: InputStream, expectedFrames: Int, expectedSampleRate: Int): ShortArray =
        Pcm16WavCodec.read(
            input = input,
            expectedFrames = expectedFrames,
            expectedSampleRate = expectedSampleRate,
            expectedChannelCount = 1,
        )
}
