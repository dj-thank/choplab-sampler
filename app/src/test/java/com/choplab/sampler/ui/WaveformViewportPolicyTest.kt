package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformViewportPolicyTest {
    @Test
    fun tapMapsToVisibleFrameAndClampsAtBothEdges() {
        assertEquals(100, waveformFrameAtX(x = -50f, width = 200f, visibleStart = 100, visibleFrames = 400, totalFrames = 1_000))
        assertEquals(100, waveformFrameAtX(x = 0f, width = 200f, visibleStart = 100, visibleFrames = 400, totalFrames = 1_000))
        assertEquals(500, waveformFrameAtX(x = 200f, width = 200f, visibleStart = 100, visibleFrames = 400, totalFrames = 1_000))
        assertEquals(500, waveformFrameAtX(x = 250f, width = 200f, visibleStart = 100, visibleFrames = 400, totalFrames = 1_000))
    }

    @Test
    fun zoomKeepsTheGestureFocusFrameInsideTheViewport() {
        val next = zoomViewportAtFocus(
            frame = 600,
            totalFrames = 1_000,
            zoom = 2f,
            zoomChange = 2f,
            maximumZoom = 32f,
        )
        assertEquals(4f, next.zoom, 0.0001f)
        assertEquals(600, next.visibleStart + next.visibleFrames / 2)
    }

    @Test
    fun invalidGestureValuesProduceSafeFiniteViewport() {
        val next = zoomViewportAtFocus(
            frame = -10,
            totalFrames = 0,
            zoom = Float.NaN,
            zoomChange = Float.POSITIVE_INFINITY,
            maximumZoom = Float.NaN,
        )
        assertTrue(next.zoom.isFinite())
        assertTrue(next.scroll.isFinite())
        assertEquals(1, next.totalFrames)
        assertEquals(0, next.visibleStart)
    }
}
