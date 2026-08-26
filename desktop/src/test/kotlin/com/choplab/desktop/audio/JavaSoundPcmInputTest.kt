package com.choplab.desktop.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class JavaSoundPcmInputTest {
    @Test
    fun stereoClipInputKeepsInterleavingChannelCountAndFrameLength() {
        val samples = shortArrayOf(1_000, -3_000, 2_000, -4_000)

        pcm16AudioInputStream(samples, sampleRate = 48_000, channelCount = 2).use { stream ->
            val decoded = ByteBuffer.wrap(stream.readBytes())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }

            assertEquals(2, stream.format.channels)
            assertEquals(4, stream.format.frameSize)
            assertEquals(2L, stream.frameLength)
            assertContentEquals(samples, decoded)
        }
    }
}
