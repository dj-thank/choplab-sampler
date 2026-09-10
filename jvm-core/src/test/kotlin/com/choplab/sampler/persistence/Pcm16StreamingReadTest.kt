package com.choplab.sampler.persistence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16StreamingReadTest {
    @Test
    fun largeStereoReadUsesBoundedScratchInsteadOfAnAudioSizedByteArray() {
        val samples = ShortArray(200_000) { (it * 7919).toShort() }
        val input = ObservedInput(wav(samples, 2))
        assertArrayEquals(samples, Pcm16WavCodec.read(input, samples.size / 2, 48_000, 2))
        assertTrue(input.largestRequest <= 8 * 1024)
    }

    @Test
    fun everySignedPcm16ValueSurvivesChunkBoundaries() {
        val samples = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        assertArrayEquals(samples, read(wav(samples), samples.size))
    }

    @Test
    fun oddAndShortInputReadsKeepStereoChannelOrder() {
        val samples = ShortArray(40_002) { if (it % 2 == 0) (it / 2).toShort() else (-it).toShort() }
        val input = ObservedInput(wav(samples, 2), fragmentSize = 3)
        assertArrayEquals(samples, Pcm16WavCodec.read(input, samples.size / 2, 48_000, 2))
    }

    @Test
    fun singleByteInputReadsAreSupported() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        assertArrayEquals(samples, Pcm16WavCodec.read(ObservedInput(wav(samples), 1), samples.size, 48_000, 1))
    }

    @Test
    fun payloadReadNeverConsumesTheFollowingBytes() {
        val samples = ShortArray(16_385) { it.toShort() }
        val input = ByteArrayInputStream(wav(samples) + byteArrayOf(42, 43, 44))
        assertArrayEquals(samples, Pcm16WavCodec.read(input, samples.size, 48_000, 1))
        assertEquals(3, input.available())
        assertEquals(42, input.read())
    }

    @Test
    fun truncatedHeaderIsRejectedAtEveryOffset() {
        val bytes = wav(shortArrayOf(1))
        for (end in 0 until 44) {
            assertThrows(IllegalArgumentException::class.java) { read(bytes.copyOf(end), 1) }
        }
    }

    @Test
    fun truncatedPayloadIsRejectedAroundBufferAndSampleBoundaries() {
        val samples = ShortArray(40_000) { it.toShort() }
        val bytes = wav(samples)
        for (end in listOf(44, 45, 44 + 8191, 44 + 8192, 44 + 32767, 44 + 32768, bytes.size - 1)) {
            assertThrows(IllegalArgumentException::class.java) { read(bytes.copyOf(end), samples.size) }
        }
    }

    @Test
    fun nonProgressingHeaderReadFailsInsteadOfSpinning() {
        val input = ObservedInput(wav(shortArrayOf(1)), zeroAtPosition = 0)
        assertThrows(IllegalArgumentException::class.java) { Pcm16WavCodec.read(input, 1, 48_000, 1) }
    }

    @Test
    fun nonProgressingPayloadReadFailsInsteadOfSpinning() {
        val input = ObservedInput(wav(shortArrayOf(1)), zeroAtPosition = 44)
        assertThrows(IllegalArgumentException::class.java) { Pcm16WavCodec.read(input, 1, 48_000, 1) }
    }

    @Test
    fun malformedHeaderIsRejectedBeforeAnyPayloadRead() {
        for (offset in listOf(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40)) {
            val bytes = wav(shortArrayOf(1, -2)).also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val input = ObservedInput(bytes)
            assertThrows(IllegalArgumentException::class.java) { Pcm16WavCodec.read(input, 2, 48_000, 1) }
            assertEquals(44, input.position)
        }
    }

    @Test
    fun ioFailureIsNotReportedAsASuccessfulPartialWaveform() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic read failure")
        }
        assertThrows(IOException::class.java) { Pcm16WavCodec.read(input, 1, 48_000, 1) }
    }

    @Test
    fun independentReadersDoNotShareMutableScratch() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 8).map { seed ->
                executor.submit<Boolean> {
                    val samples = ShortArray(40_000) { (it * 17 + seed * 101).toShort() }
                    samples.contentEquals(read(wav(samples), samples.size))
                }
            }
            tasks.forEach { assertTrue(it.get(20, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun legacyMonoFacadeUsesTheSameExactDecoder() {
        val samples = ShortArray(32_777) { (-it).toShort() }
        val input = ObservedInput(wav(samples))
        assertArrayEquals(samples, MonoPcm16WavCodec.read(input, samples.size, 48_000))
        assertTrue(input.largestRequest <= 8 * 1024)
    }

    @Test
    fun emptyCanonicalWavKeepsItsExistingCodecContract() {
        assertArrayEquals(shortArrayOf(), read(wav(shortArrayOf()), 0))
    }

    private fun wav(samples: ShortArray, channels: Int = 1): ByteArray =
        ByteArrayOutputStream().also { Pcm16WavCodec.write(it, samples, 48_000, channels) }.toByteArray()

    private fun read(bytes: ByteArray, frames: Int): ShortArray =
        Pcm16WavCodec.read(ByteArrayInputStream(bytes), frames, 48_000, 1)

    private class ObservedInput(
        private val bytes: ByteArray,
        private val fragmentSize: Int = Int.MAX_VALUE,
        private val zeroAtPosition: Int = -1,
    ) : InputStream() {
        var position = 0
            private set
        var largestRequest = 0
            private set

        override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt() and 255

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestRequest = maxOf(largestRequest, length)
            if (length == 0) return 0
            if (position == zeroAtPosition) return 0
            if (position == bytes.size) return -1
            val count = minOf(length, fragmentSize, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
