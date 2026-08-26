package com.choplab.sampler.audio

import android.media.AudioFormat
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.persistableAudioDisplayName
import com.choplab.sampler.persistence.ProjectArchiveCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16ArrayBuilderTest {
    @Test
    fun decodedPcmFormatCannotChangeAfterOutputStartsEvenWithoutAnotherPcmBuffer() {
        validateStableDecodedPcmFormat(
            storedChannelCount = null,
            storedSampleRate = null,
            decodedFormat = DecodedAudioFormat(sampleRate = 48_000, channelCount = 2),
        )
        validateStableDecodedPcmFormat(
            storedChannelCount = 2,
            storedSampleRate = 48_000,
            decodedFormat = DecodedAudioFormat(sampleRate = 48_000, channelCount = 2),
        )

        assertThrows(IllegalStateException::class.java) {
            validateStableDecodedPcmFormat(
                storedChannelCount = 2,
                storedSampleRate = 48_000,
                decodedFormat = DecodedAudioFormat(sampleRate = 44_100, channelCount = 2),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            validateStableDecodedPcmFormat(
                storedChannelCount = 2,
                storedSampleRate = 48_000,
                decodedFormat = DecodedAudioFormat(sampleRate = 48_000, channelCount = 1),
            )
        }
    }

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
    fun decodedPcmTightensItsStreamingLimitWhenOutputRateChanges() {
        val builder = Pcm16ArrayBuilder(initialCapacity = 1, maximumSize = 3)
        builder.append(0f)
        builder.append(0f)

        builder.updateMaximumSize(2)

        assertEquals(2, builder.size)
        assertThrows(IllegalStateException::class.java) { builder.append(0f) }
        assertThrows(IllegalStateException::class.java) { builder.updateMaximumSize(1) }
    }

    @Test
    fun decodedAudioNameStaysPersistableWithoutSplittingUnicode() {
        assertEquals("fallback.wav", persistableAudioDisplayName("  ", "fallback.wav"))
        assertEquals("sample", persistableAudioDisplayName("\t", null))
        assertEquals(
            "fallback.wav",
            persistableAudioDisplayName(
                " ".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS) + "x",
                "fallback.wav",
            ),
        )

        val providerName = "a".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS - 1) +
            "\uD83C\uDFB5.wav"
        val persistedName = persistableAudioDisplayName(providerName, null)
        val audio = PcmAudio(
            name = persistedName,
            samples = shortArrayOf(1, -2),
            sampleRate = 8_000,
        )
        val archive = ByteArrayOutputStream()

        ProjectArchiveCodec.write(
            SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount),
            archive,
        )
        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(archive.toByteArray()))

        assertEquals(ProjectLimits.MAX_ASSET_NAME_CHARS - 1, persistedName.length)
        assertFalse(Character.isHighSurrogate(persistedName.last()))
        assertEquals(persistedName, restored.currentAudio?.name)
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
