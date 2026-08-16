package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformViewportPolicyTest {
    @Test
    fun viewportStateNormalizesInvalidZoomAndScrollIntoTheWholeSource() {
        val viewport = resolveWaveformViewport(
            totalFrames = 1_000,
            zoom = Float.NaN,
            scroll = Float.POSITIVE_INFINITY,
        )

        assertEquals(1f, viewport.zoom)
        assertEquals(0f, viewport.scroll)
        assertEquals(0, viewport.visibleStart)
        assertEquals(1_000, viewport.visibleFrames)
    }

    @Test
    fun viewportDescriptionDistinguishesWholeSourceFromZoomedRange() {
        assertEquals(
            "全体表示。0から999フレーム",
            waveformViewportStateDescription(resolveWaveformViewport(1_000, 1f, 0f)),
        )
        assertEquals(
            "拡大表示。250から749フレーム。全体1000フレーム",
            waveformViewportStateDescription(resolveWaveformViewport(1_000, 2f, 0.5f)),
        )
    }

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

    @Test
    fun pinchOutStopsAtWholeSourceAndResetsScroll() {
        val next = zoomViewportAtFocus(
            frame = 800,
            totalFrames = 1_000,
            zoom = 8f,
            zoomChange = 0.01f,
            maximumZoom = 32f,
        )
        assertEquals(1f, next.zoom, 0.0001f)
        assertEquals(1_000, next.visibleFrames)
        assertEquals(0f, next.scroll, 0.0001f)
    }

    @Test
    fun focusNearSourceEdgesNeverMovesViewportOutsideAudio() {
        val first = zoomViewportAtFocus(0, 1_000, 1f, 4f, 32f)
        val last = zoomViewportAtFocus(999, 1_000, 1f, 4f, 32f)
        assertEquals(0, first.visibleStart)
        assertEquals(750, last.visibleStart)
    }

    @Test
    fun zeroWidthAndHalfPixelMappingHaveDeterministicResults() {
        assertEquals(100, waveformFrameAtX(20f, 0f, 100, 400, 1_000))
        assertEquals(101, waveformFrameAtX(0.5f, 200f, 100, 400, 1_000))
    }

    @Test
    fun panMovesByVisibleFractionAndClampsAtEdges() {
        val middle = panWaveformViewport(totalFrames = 1_000, zoom = 4f, scroll = 0.5f, fraction = 0.25f)
        val first = panWaveformViewport(totalFrames = 1_000, zoom = 4f, scroll = 0.1f, fraction = -1f)
        val last = panWaveformViewport(totalFrames = 1_000, zoom = 4f, scroll = 0.9f, fraction = 1f)
        assertEquals(0.5833f, middle.scroll, 0.001f)
        assertEquals(0f, first.scroll, 0.0001f)
        assertEquals(1f, last.scroll, 0.0001f)
    }
}
