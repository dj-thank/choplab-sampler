package com.choplab.desktop.audio

import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.persistence.ProjectArchiveCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    fun boundsDecodedNameBeforeProjectArchiveReadBack() {
        val bytes = pcm16LittleEndian(1, -2)
        val monoFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            8_000f,
            16,
            1,
            2,
            8_000f,
            false,
        )
        val stream = AudioInputStream(ByteArrayInputStream(bytes), monoFormat, 2)
        val sourceName = " ".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS) + "take.wav"

        val audio = DesktopWavDecoder.readMono(sourceName, stream, maximumFrames = 2)
        val archive = ByteArrayOutputStream()
        ProjectArchiveCodec.write(
            SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount),
            archive,
        )
        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(archive.toByteArray()))

        assertEquals("sample", audio.name)
        assertEquals(audio.name, restored.currentAudio?.name)
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

    @Test
    fun acceptsUnknownLengthStreamAtExactStreamingBoundary() {
        val bytes = pcm16LittleEndian(1, 2)
        val monoFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            8_000f,
            16,
            1,
            2,
            8_000f,
            false,
        )
        val stream = AudioInputStream(
            ByteArrayInputStream(bytes),
            monoFormat,
            AudioSystem.NOT_SPECIFIED.toLong(),
        )

        val audio = DesktopWavDecoder.readMono("unknown.wav", stream, maximumFrames = 2)

        assertContentEquals(shortArrayOf(1, 2), audio.samples)
        assertEquals(8_000, audio.sampleRate)
    }

    @Test
    fun acceptsTheExactProjectSampleRateCeiling() {
        assertEquals(
            ProjectLimits.MAX_SAMPLE_RATE,
            DesktopWavDecoder.validateSampleRate(ProjectLimits.MAX_SAMPLE_RATE.toFloat()),
        )
    }

    @Test
    fun rejectsUnsupportedSampleRateBeforeReadingPayload() {
        val source = FailOnReadInputStream()
        val unsupportedFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            (ProjectLimits.MAX_SAMPLE_RATE + 1).toFloat(),
            16,
            1,
            2,
            (ProjectLimits.MAX_SAMPLE_RATE + 1).toFloat(),
            false,
        )
        val stream = AudioInputStream(
            source,
            unsupportedFormat,
            AudioSystem.NOT_SPECIFIED.toLong(),
        )

        assertFailsWith<IllegalArgumentException> {
            DesktopWavDecoder.readMono("unsupported.wav", stream, maximumFrames = 1)
        }

        assertEquals(0, source.readCalls)
    }

    private fun pcm16LittleEndian(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { sample -> putShort(sample) } }
            .array()

    private fun pcm16LittleEndian(vararg samples: Int): ByteArray =
        pcm16LittleEndian(*samples.map(Int::toShort).toShortArray())

    private class FailOnReadInputStream : InputStream() {
        var readCalls: Int = 0
            private set

        override fun read(): Int {
            readCalls++
            error("Unsupported-rate payload must not be read")
        }
    }
}
