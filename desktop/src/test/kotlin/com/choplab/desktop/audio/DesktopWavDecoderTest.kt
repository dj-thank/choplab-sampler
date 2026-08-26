package com.choplab.desktop.audio

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopWavDecoderTest {
    private val stereoFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        48_000f,
        16,
        2,
        4,
        48_000f,
        false,
    )

    @Test
    fun streamsAndPreservesAsymmetricStereoFrames() {
        val bytes = pcm16LittleEndian(
            1_000, -1_000,
            Short.MAX_VALUE, Short.MAX_VALUE,
            Short.MIN_VALUE, Short.MIN_VALUE,
        )
        val stream = AudioInputStream(ByteArrayInputStream(bytes), stereoFormat, 3)

        val audio = DesktopWavDecoder.readPcm("stereo.wav", stream, maximumFrames = 10)

        assertEquals("stereo.wav", audio.name)
        assertEquals(48_000, audio.sampleRate)
        assertEquals(2, audio.channelCount)
        assertEquals(3, audio.frameCount)
        assertContentEquals(
            shortArrayOf(1_000, -1_000, Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE),
            audio.samples,
        )
    }

    @Test
    fun downmixesThreeChannelInputWithoutGuessingAStoredLayout() {
        val surroundFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            48_000f,
            16,
            3,
            6,
            48_000f,
            false,
        )
        val bytes = pcm16LittleEndian(9_000, -3_000, 0, -9_000, 3_000, 0)
        val stream = AudioInputStream(ByteArrayInputStream(bytes), surroundFormat, 2)

        val audio = DesktopWavDecoder.readPcm("three-channel.wav", stream, maximumFrames = 10)

        assertEquals(1, audio.channelCount)
        assertEquals(2, audio.frameCount)
        assertContentEquals(shortArrayOf(2_000, -2_000), audio.samples)
    }

    @Test
    fun rejectsKnownOversizedStreamBeforeReadingPayload() {
        val bytes = pcm16LittleEndian(1, 1, 2, 2)
        val stream = AudioInputStream(ByteArrayInputStream(bytes), stereoFormat, 2)

        assertFailsWith<IllegalArgumentException> {
            DesktopWavDecoder.readPcm("too-large.wav", stream, maximumFrames = 1)
        }
    }

    @Test
    fun rejectsUnknownLengthStreamAtBuilderBoundary() {
        val bytes = pcm16LittleEndian(1, 1, 2, 2)
        val stream = AudioInputStream(
            ByteArrayInputStream(bytes),
            stereoFormat,
            AudioSystem.NOT_SPECIFIED.toLong(),
        )

        assertFailsWith<IllegalStateException> {
            DesktopWavDecoder.readPcm("unknown.wav", stream, maximumFrames = 1)
        }
    }

    private fun pcm16LittleEndian(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { sample -> putShort(sample) } }
            .array()

    private fun pcm16LittleEndian(vararg samples: Int): ByteArray =
        pcm16LittleEndian(*samples.map(Int::toShort).toShortArray())
}
