package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ScratchPlaybackCursorTest {
    @Test
    fun cursorWrapsForwardAndBackwardInsideTheChop() {
        val cursor = ScratchPlaybackCursor(startFrame = 100, endFrame = 200, initialFrame = 198.0)

        cursor.advance(5.0)
        assertEquals(103.0, cursor.position, 0.0001)

        cursor.advance(-8.0)
        assertEquals(195.0, cursor.position, 0.0001)
    }

    @Test
    fun zeroSpeedKeepsTheNeedleStill() {
        val cursor = ScratchPlaybackCursor(startFrame = 0, endFrame = 10, initialFrame = 4.5)

        cursor.advance(0.0)

        assertEquals(4.5, cursor.position, 0.0001)
    }

    @Test
    fun nonFiniteGestureSpeedCannotPoisonPlaybackPosition() {
        val cursor = ScratchPlaybackCursor(startFrame = 0, endFrame = 10, initialFrame = 4.5)

        cursor.advance(Double.NaN)
        cursor.advance(Double.POSITIVE_INFINITY)

        assertEquals(4.5, cursor.position, 0.0001)
    }
}
