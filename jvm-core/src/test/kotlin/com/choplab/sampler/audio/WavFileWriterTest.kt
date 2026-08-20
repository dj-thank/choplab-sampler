package com.choplab.sampler.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
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

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
}
