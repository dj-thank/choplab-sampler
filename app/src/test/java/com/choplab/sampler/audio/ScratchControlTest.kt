package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchControlTest {
    @Test
    fun nanAndInfinityNormalizeToZero() {
        assertEquals(0f, normalizeScratchSpeed(Float.NaN), 0f)
        assertEquals(0f, normalizeScratchSpeed(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, normalizeScratchSpeed(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(4f, normalizeScratchSpeed(10f), 0f)
        assertEquals(-4f, normalizeScratchSpeed(-10f), 0f)
    }

    @Test
    fun finiteSpeedAfterInvalidInputProducesFiniteOutput() {
        val smoother = ScratchSpeedSmoother()

        assertEquals(0.0, smoother.next(Float.NaN), 0.0)
        val recovered = smoother.next(2f)

        assertTrue(recovered.isFinite())
        assertTrue(recovered > 0.0)
    }

    @Test
    fun equalPhysicalVelocityProducesEqualSpeedAtDifferentEventRates() {
        val sixtyHertz = scratchSpeedFromGesture(
            deltaPixels = 7f,
            elapsedMillis = 16L,
            sensitivityDivisor = 7f,
        )
        val oneTwentyHertz = scratchSpeedFromGesture(
            deltaPixels = 3.5f,
            elapsedMillis = 8L,
            sensitivityDivisor = 7f,
        )

        assertEquals(sixtyHertz, oneTwentyHertz, 0.001f)
    }

    @Test
    fun normalTouchEventGapDoesNotStopAnActiveScratchGesture() {
        assertFalse(scratchGestureIsIdle(elapsedMillis = 80L))
        assertTrue(scratchGestureIsIdle(elapsedMillis = 120L))
    }

    @Test
    fun dialProgressUsesTheSuppliedTargetBounds() {
        assertEquals(0.5f, scratchProgress(frame = 250, startFrame = 200, endFrame = 300), 0f)
        assertEquals(0f, scratchProgress(frame = 50, startFrame = 200, endFrame = 300), 0f)
        assertEquals(1f, scratchProgress(frame = 500, startFrame = 200, endFrame = 300), 0f)
    }
}
