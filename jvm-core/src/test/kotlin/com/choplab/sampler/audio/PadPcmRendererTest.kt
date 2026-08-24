package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
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
    fun reverseResamplingStopsAtTheSameFrameAsTheRealtimeCursor() {
        val startFrame = 100
        val endFrame = 164
        val outputSampleRate = 60_000
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = startFrame,
            endFrame = endFrame,
            gain = 1f,
            reverse = true,
        )
        val sourceStep = SamplerDspPrimitives.sourceStep(
            pitchSemitones = pad.pitchSemitones,
            sourceSampleRate = audio.sampleRate,
            outputSampleRate = outputSampleRate,
        )
        val cursor = VoicePlaybackCursor(
            startFrame = startFrame,
            endFrame = endFrame,
            reverse = true,
            playMode = PadPlayMode.ONE_SHOT,
        )
        var realtimeFrameCount = 0
        while (!cursor.finished) {
            realtimeFrameCount++
            cursor.advance(sourceStep)
        }

        val rendered = PadPcmRenderer.render(pad, outputSampleRate)

        assertEquals(79, realtimeFrameCount)
        assertEquals(realtimeFrameCount, rendered.size)
        assertTrue(rendered.last() != 0.toShort())
    }

    @Test
    fun reverseOneShotUsesCursorRoundingAtTheStartBoundary() {
        val shortAudio = PcmAudio(
            name = "short",
            samples = shortArrayOf(8_000, 16_000),
            sampleRate = 8_000,
        )
        val rendered = PadPcmRenderer.render(
            PadModel(
                globalIndex = 0,
                audio = shortAudio,
                startFrame = 0,
                endFrame = 2,
                pitchSemitones = -12f,
                reverse = true,
            ),
            outputSampleRate = 48_000,
        )

        assertEquals(12, rendered.size)
    }

    @Test
    fun reverseLoopKeepsTheLegacyRenderBoundary() {
        val oneShot = PadPcmRenderer.render(
            PadModel(0, audio, 100, 102, pitchSemitones = -12f, reverse = true),
        )
        val loop = PadPcmRenderer.render(
            PadModel(
                globalIndex = 0,
                audio = audio,
                startFrame = 100,
                endFrame = 102,
                pitchSemitones = -12f,
                reverse = true,
                playMode = PadPlayMode.LOOP,
            ),
        )

        assertEquals(3, oneShot.size)
        assertEquals(4, loop.size)
        assertEquals(0.toShort(), loop.last())
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
