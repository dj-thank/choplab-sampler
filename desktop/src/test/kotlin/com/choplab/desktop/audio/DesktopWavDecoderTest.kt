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
    fun streamsAndDownmixesStereoFrames() {
        val bytes = pcm16LittleEndian(
            1_000, -1_000,
            Short.MAX_VALUE, Short.MAX_VALUE,
            Short.MIN_VALUE, Short.MIN_VALUE,
        )
        val stream = AudioInputStream(ByteArrayInputStream(bytes), stereoFormat, 3)

        val audio = DesktopWavDecoder.readMono("stereo.wav", stream, maximumFrames = 10)

        assertEquals("stereo.wav", audio.name)
        assertEquals(48_000, audio.sampleRate)
        assertContentEquals(shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE), audio.samples)
    }

    @Test
    fun rejectsKnownOversizedStreamBeforeReadingPayload() {
        val bytes = pcm16LittleEndian(1, 1, 2, 2)
        val stream = AudioInputStream(ByteArrayInputStream(bytes), stereoFormat, 2)

        assertFailsWith<IllegalArgumentException> {
            DesktopWavDecoder.readMono("too-large.wav", stream, maximumFrames = 1)
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
            DesktopWavDecoder.readMono("unknown.wav", stream, maximumFrames = 1)
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
