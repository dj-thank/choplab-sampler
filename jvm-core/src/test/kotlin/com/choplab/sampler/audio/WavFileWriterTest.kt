package com.choplab.sampler.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavFileWriterTest {
    @Test
    fun patchesRiffAndDataSizesOnClose() {
        val file = File.createTempFile("choplab", ".wav")
        try {
            WavFileWriter(file, sampleRate = 48_000, channelCount = 1).use { writer ->
                writer.writePcm16(shortArrayOf(0, 1_000, -1_000, Short.MAX_VALUE))
            }

            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
            assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
            assertEquals(44 + 8, bytes.size)
            assertEquals(8, littleEndianInt(bytes, 40))
            assertEquals(bytes.size - 8, littleEndianInt(bytes, 4))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsOverflowBeforeWritingAndKeepsPriorPayloadValid() {
        val file = File.createTempFile("choplab-overflow", ".wav")
        try {
            WavFileWriter(
                file = file,
                sampleRate = 8_000,
                channelCount = 1,
                maximumPcmBytes = 4,
            ).use { writer ->
                writer.writePcm16(shortArrayOf(1, 2))
                val error = assertThrows(IllegalStateException::class.java) {
                    writer.writePcm16(shortArrayOf(3))
                }
                assertTrue(error.message.orEmpty().contains("RIFF/WAV"))
            }

            val bytes = file.readBytes()
            assertEquals(48, bytes.size)
            assertEquals(4, littleEndianInt(bytes, 40))
            assertEquals(40, littleEndianInt(bytes, 4))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsPartialStereoFrames() {
        val file = File.createTempFile("choplab-frame", ".wav")
        try {
            WavFileWriter(file, sampleRate = 48_000, channelCount = 2).use { writer ->
                assertThrows(IllegalArgumentException::class.java) {
                    writer.writePcm16(shortArrayOf(1))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    writer.writePcm16Bytes(byteArrayOf(1, 2))
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
}
