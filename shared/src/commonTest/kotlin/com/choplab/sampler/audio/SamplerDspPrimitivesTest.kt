package com.choplab.sampler.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplerDspPrimitivesTest {
    @Test
    fun nonFiniteControlsUseExplicitSafeNeutralValues() {
        assertEquals(0f, SamplerDspPrimitives.pitchSemitones(Float.NaN))
        assertEquals(0f, SamplerDspPrimitives.pitchSemitones(Float.POSITIVE_INFINITY))
        assertEquals(1f, SamplerDspPrimitives.tone(Float.NaN))
        assertEquals(0f, SamplerDspPrimitives.gain(Float.NEGATIVE_INFINITY))
        assertEquals(92f, SamplerDspPrimitives.bpm(Float.NaN))
        assertEquals(50f, SamplerDspPrimitives.swing(Float.POSITIVE_INFINITY))
    }

    @Test
    fun pitchStepAndToneAlphaAreFiniteAndBounded() {
        assertEquals(2.0, SamplerDspPrimitives.sourceStep(12f, 48_000, 48_000), 1e-9)
        assertEquals(1.0, SamplerDspPrimitives.sourceStep(Float.NaN, 48_000, 48_000), 1e-9)
        assertEquals(1f, SamplerDspPrimitives.toneFilterAlpha(Float.NaN, 48_000))
        val dark = SamplerDspPrimitives.toneFilterAlpha(0.15f, 48_000)
        val medium = SamplerDspPrimitives.toneFilterAlpha(0.65f, 48_000)
        assertTrue(dark in 0f..1f)
        assertTrue(medium in 0f..1f)
        assertTrue(dark < medium)
    }

    @Test
    fun boundaryEnvelopeIsDirectionSymmetricAndRejectsInvalidPositions() {
        val forwardStart = SamplerDspPrimitives.boundaryEnvelope(12.0, 0, 100, reverse = false)
        val reverseStart = SamplerDspPrimitives.boundaryEnvelope(87.0, 0, 100, reverse = true)
        assertEquals(forwardStart, reverseStart, 0f)
        assertEquals(0f, SamplerDspPrimitives.boundaryEnvelope(Double.NaN, 0, 100, false))
        assertEquals(0f, SamplerDspPrimitives.boundaryEnvelope(0.0, 10, 10, false))
    }

    @Test
    fun limiterNeverPublishesNonFiniteOutput() {
        assertEquals(0f, SamplerDspPrimitives.softLimit(Float.NaN))
        assertEquals(0f, SamplerDspPrimitives.softLimit(Float.POSITIVE_INFINITY))
        assertEquals(0.5f, SamplerDspPrimitives.softLimit(1f), 0f)
        assertTrue(SamplerDspPrimitives.softLimit(Float.MAX_VALUE).isFinite())
    }

    @Test
    fun swingPairKeepsTheSameTwoStepDuration() {
        val straight = 48_000 * 60.0 / 120.0 / 4.0
        val long = SamplerDspPrimitives.stepLengthFrames(48_000, 120f, 62.5f, 0)
        val short = SamplerDspPrimitives.stepLengthFrames(48_000, 120f, 62.5f, 1)
        assertEquals(straight * 2.0, long + short, 1e-9)

        val safe = SamplerDspPrimitives.stepLengthFrames(
            sampleRate = 48_000,
            bpm = Float.NaN,
            swing = Float.NaN,
            step = 0,
        )
        assertTrue(safe.isFinite())
        assertTrue(safe > 0.0)
    }
}
