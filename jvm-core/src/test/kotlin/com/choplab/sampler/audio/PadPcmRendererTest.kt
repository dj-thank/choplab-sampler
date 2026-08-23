package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadPcmRendererTest {
    private val audio = PcmAudio(
        name = "ramp",
        samples = ShortArray(960) { index -> (index * 20 - 9_000).toShort() },
        sampleRate = 48_000,
    )

    @Test
    fun plusTwelveSemitonesHalvesRenderedDuration() {
        val normal = PadPcmRenderer.render(PadModel(0, audio, 0, audio.frameCount, pitchSemitones = 0f))
        val raised = PadPcmRenderer.render(PadModel(0, audio, 0, audio.frameCount, pitchSemitones = 12f))

        assertEquals(normal.size / 2, raised.size)
    }

    @Test
    fun reverseMirrorsTheAudibleMiddleOfTheRange() {
        val forward = PadPcmRenderer.render(PadModel(0, audio, 0, audio.frameCount, gain = 1f))
        val reverse = PadPcmRenderer.render(PadModel(0, audio, 0, audio.frameCount, gain = 1f, reverse = true))

        assertTrue(abs(forward[200].toInt() - reverse[reverse.lastIndex - 200].toInt()) < 32)
    }

    @Test
    fun zeroGainProducesSilence() {
        val rendered = PadPcmRenderer.render(PadModel(0, audio, 0, audio.frameCount, gain = 0f))
        assertTrue(rendered.all { it == 0.toShort() })
    }

    @Test
    fun nonFiniteControlsMatchTheExplicitNeutralPolicy() {
        val neutral = PadPcmRenderer.render(
            PadModel(0, audio, 0, audio.frameCount, pitchSemitones = 0f, tone = 1f, gain = 1f),
        )
        val nonFinitePitchAndTone = PadPcmRenderer.render(
            PadModel(
                0,
                audio,
                0,
                audio.frameCount,
                pitchSemitones = Float.NaN,
                tone = Float.NaN,
                gain = 1f,
            ),
        )
        val nonFiniteGain = PadPcmRenderer.render(
            PadModel(0, audio, 0, audio.frameCount, gain = Float.POSITIVE_INFINITY),
        )

        assertTrue(neutral.contentEquals(nonFinitePitchAndTone))
        assertTrue(nonFiniteGain.all { it == 0.toShort() })
    }
}
