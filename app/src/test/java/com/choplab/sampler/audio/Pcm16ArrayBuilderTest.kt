package com.choplab.sampler.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16ArrayBuilderTest {
    @Test
    fun decodedAudioFormatRejectsImplausibleProviderOutput() {
        assertEquals(DecodedAudioFormat(8_000, 1), validateDecodedAudioFormat(8_000, 1))
        assertEquals(DecodedAudioFormat(192_000, 8), validateDecodedAudioFormat(192_000, 8))

        assertThrows(IllegalArgumentException::class.java) { validateDecodedAudioFormat(7_999, 1) }
        assertThrows(IllegalArgumentException::class.java) { validateDecodedAudioFormat(192_001, 1) }
        assertThrows(IllegalArgumentException::class.java) { validateDecodedAudioFormat(48_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { validateDecodedAudioFormat(48_000, 9) }
    }

    @Test
    fun decodedPcmCannotGrowPastTheHardFrameLimit() {
        val builder = Pcm16ArrayBuilder(
            initialFrameCapacity = 1,
            maximumFrames = 2,
            channelCount = 2,
        )

        builder.append(-1f)
        builder.append(0f)
        builder.append(1f)
        builder.append(0.5f)

        assertEquals(4, builder.size)
        assertEquals(2, builder.frameCount)
        assertEquals(4, builder.toArray().size)
        assertThrows(IllegalStateException::class.java) { builder.append(0f) }
    }

    @Test
    fun pcm16ConversionPreservesStereoAndDownmixesUnnamedMultichannelLayouts() {
        val stereo = Pcm16ArrayBuilder(initialFrameCapacity = 1, maximumFrames = 2, channelCount = 2)
        appendDecodedPcm(
            source = pcm16(12_000, -4_000, 8_000, -16_000),
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            sourceChannelCount = 2,
            destination = stereo,
        )
        assertArrayEquals(shortArrayOf(12_000, -4_000, 8_000, -16_000), stereo.toArray())

        val downmixed = Pcm16ArrayBuilder(initialFrameCapacity = 1, maximumFrames = 2, channelCount = 1)
        appendDecodedPcm(
            source = pcm16(9_000, -3_000, 0, -9_000, 3_000, 0),
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            sourceChannelCount = 3,
            destination = downmixed,
        )
        assertArrayEquals(shortArrayOf(2_000, -2_000), downmixed.toArray())
    }

    @Test
    fun decodedPcmRejectsPartialFramesAndLayoutChanges() {
        val partial = Pcm16ArrayBuilder(initialFrameCapacity = 1, maximumFrames = 2, channelCount = 2)
        assertThrows(IllegalArgumentException::class.java) {
            appendDecodedPcm(
                source = pcm16(1, 2, 3),
                encoding = AudioFormat.ENCODING_PCM_16BIT,
                sourceChannelCount = 2,
                destination = partial,
            )
        }

        assertThrows(IllegalStateException::class.java) {
            appendDecodedPcm(
                source = pcm16(1),
                encoding = AudioFormat.ENCODING_PCM_16BIT,
                sourceChannelCount = 1,
                destination = partial,
            )
        }
    }

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                samples.forEach(::putShort)
                flip()
            }

    private fun pcm16(vararg samples: Int): ByteBuffer = pcm16(*samples.map(Int::toShort).toShortArray())
}
