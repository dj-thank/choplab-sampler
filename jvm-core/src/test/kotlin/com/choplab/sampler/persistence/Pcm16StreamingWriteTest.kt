package com.choplab.sampler.persistence

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class Pcm16StreamingWriteTest {
    private fun scalarWav(samples: ShortArray, channels: Int, rate: Int = 48_000): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        fun le16(n: Int) { out.write(n and 255); out.write((n ushr 8) and 255) }
        fun le32(n: Int) { le16(n); le16(n ushr 16) }
        ascii("RIFF"); le32(36 + samples.size * 2); ascii("WAVEfmt "); le32(16)
        le16(1); le16(channels); le32(rate); le32(rate * channels * 2)
        le16(channels * 2); le16(16); ascii("data"); le32(samples.size * 2)
        samples.forEach { le16(it.toInt()) }
        return out.toByteArray()
    }

    @Test fun everySignedPcm16ValueMatchesTheIndependentScalarWriter() {
        val samples = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        val out = ByteArrayOutputStream()
        Pcm16WavCodec.write(out, samples, 48_000, 1)
        assertArrayEquals(scalarWav(samples, 1), out.toByteArray())
    }

    @Test fun monoAndStereoChunkBoundariesProduceExactlyTheSameBytes() {
        for (channels in 1..2) for (frames in listOf(0, 1, 2, 2047, 2048, 2049, 4095, 4096, 4097, 8193)) {
            val samples = ShortArray(frames * channels) { (it * 7919 - 32768).toShort() }
            val out = ByteArrayOutputStream()
            Pcm16WavCodec.write(out, samples, 44_100, channels)
            assertArrayEquals(scalarWav(samples, channels, 44_100), out.toByteArray())
        }
    }

    @Test fun payloadWritesStayBoundedAndExcludeStaleFinalBufferBytes() {
        val sizes = mutableListOf<Int>()
        var total = 0
        val out = object : OutputStream() {
            override fun write(value: Int) { total++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { sizes += length; total += length }
        }
        val samples = ShortArray(4097)
        Pcm16WavCodec.write(out, samples, 48_000, 1)
        assertEquals(44 + samples.size * 2, total)
        assertEquals(8192, sizes.maxOrNull())
        assertEquals(2, sizes.last())
    }

    @Test fun outputOwnershipStaysWithTheCaller() {
        var closed = false
        val out = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        Pcm16WavCodec.write(out, ShortArray(8), 48_000, 2)
        assertFalse(closed)
    }

    @Test fun writeFailurePropagatesWithoutClosingTheCallersStream() {
        val failure = IOException("synthetic write failure")
        var closed = false
        val out = object : OutputStream() {
            override fun write(value: Int) { throw failure }
            override fun close() { closed = true }
        }
        var caught: Throwable? = null
        try { Pcm16WavCodec.write(out, shortArrayOf(1), 48_000, 1) } catch (e: Throwable) { caught = e }
        assertSame(failure, caught)
        assertFalse(closed)
    }
}
