package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformViewportTest {
    @Test
    fun zoomCentersTheRequestedTrimFrameAndClampsAtSourceEdges() {
        assertEquals(0f, centeredViewportScroll(frame = 0, totalFrames = 1_000, zoom = 10f))
        assertEquals(0.5f, centeredViewportScroll(frame = 500, totalFrames = 1_000, zoom = 10f))
        assertEquals(1f, centeredViewportScroll(frame = 999, totalFrames = 1_000, zoom = 10f))
        assertEquals(0f, centeredViewportScroll(frame = 500, totalFrames = 1_000, zoom = 1f))
    }

    @Test
    fun waveformEnvelopePrecomputesVisibleBucketsForCheapPlayheadRedraws() {
        val samples = shortArrayOf(
            Short.MIN_VALUE,
            0,
            Short.MAX_VALUE,
            0,
            0,
            Short.MIN_VALUE,
            0,
            Short.MAX_VALUE,
        )

        val envelope = buildWaveformEnvelope(
            samples = samples,
            visibleStart = 0,
            visibleEnd = samples.size,
            pixelWidth = 4,
            pixelStep = 2,
        )

        assertEquals(2, envelope.minimums.size)
        assertEquals(2, envelope.maximums.size)
        assertTrue(envelope.minimums[0] <= -0.99f)
        assertTrue(envelope.maximums[0] >= 0.99f)
        assertTrue(envelope.minimums[1] <= -0.99f)
        assertTrue(envelope.maximums[1] >= 0.99f)
    }

    @Test
    fun stereoEnvelopeUsesFrameExtentAndExplicitMonoProjection() {
        val samples = shortArrayOf(
            0, 0,
            0, 0,
            Short.MIN_VALUE, Short.MIN_VALUE,
            Short.MAX_VALUE, Short.MAX_VALUE,
        )

        val envelope = buildWaveformEnvelope(
            samples = samples,
            channelCount = 2,
            visibleStart = 0,
            visibleEnd = 4,
            pixelWidth = 4,
            pixelStep = 2,
        )

        assertEquals(2, envelope.minimums.size)
        assertTrue(envelope.minimums[1] <= -0.99f)
        assertTrue(envelope.maximums[1] >= 0.99f)
    }
}
