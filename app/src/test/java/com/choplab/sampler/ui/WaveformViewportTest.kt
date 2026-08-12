package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformViewportTest {
    @Test
    fun zoomCentersTheRequestedTrimFrameAndClampsAtSourceEdges() {
        assertEquals(0f, centeredViewportScroll(frame = 0, totalFrames = 1_000, zoom = 10f))
        assertEquals(0.5f, centeredViewportScroll(frame = 500, totalFrames = 1_000, zoom = 10f))
        assertEquals(1f, centeredViewportScroll(frame = 999, totalFrames = 1_000, zoom = 10f))
        assertEquals(0f, centeredViewportScroll(frame = 500, totalFrames = 1_000, zoom = 1f))
    }
}
