package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
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
}
