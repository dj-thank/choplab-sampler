package com.choplab.sampler.audio

import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.persistence.ProjectArchiveCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val builder = Pcm16ArrayBuilder(initialCapacity = 1, maximumSize = 3)

        builder.append(-1f)
        builder.append(0f)
        builder.append(1f)

        assertEquals(3, builder.size)
        assertEquals(3, builder.toArray().size)
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
}
