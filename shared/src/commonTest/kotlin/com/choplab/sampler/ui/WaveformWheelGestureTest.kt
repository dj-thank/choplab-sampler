package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformWheelGestureTest {
    @Test
    fun wheelUpZoomsInAndWheelDownZoomsOutWithoutPanning() {
        val zoomIn = resolveWaveformWheelGesture(deltaX = 0f, deltaY = -1f)
        val zoomOut = resolveWaveformWheelGesture(deltaX = 0f, deltaY = 1f)

        assertEquals(WAVEFORM_WHEEL_ZOOM_STEP, zoomIn.zoomChange)
        assertEquals(0f, zoomIn.panFraction)
        assertEquals(1f / WAVEFORM_WHEEL_ZOOM_STEP, zoomOut.zoomChange)
        assertEquals(0f, zoomOut.panFraction)
    }

    @Test
    fun horizontalWheelPansWithoutChangingZoom() {
        val right = resolveWaveformWheelGesture(deltaX = 1f, deltaY = 0f)
        val left = resolveWaveformWheelGesture(deltaX = -2f, deltaY = 0.5f)

        assertEquals(1f, right.zoomChange)
        assertEquals(WAVEFORM_WHEEL_PAN_FRACTION, right.panFraction)
        assertEquals(1f, left.zoomChange)
        assertEquals(-WAVEFORM_WHEEL_PAN_FRACTION, left.panFraction)
    }

    @Test
    fun emptyOrInvalidDeltasLeaveTheViewportAlone() {
        val idle = resolveWaveformWheelGesture(deltaX = 0f, deltaY = 0f)
        val invalid = resolveWaveformWheelGesture(deltaX = Float.NaN, deltaY = Float.POSITIVE_INFINITY)

        assertEquals(WaveformWheelGesture(zoomChange = 1f, panFraction = 0f), idle)
        assertEquals(WaveformWheelGesture(zoomChange = 1f, panFraction = 0f), invalid)
    }

    @Test
    fun wheelZoomStaysInsideTheEditorZoomBounds() {
        var zoom = 1f
        var scroll = 0f
        repeat(40) {
            val wheel = resolveWaveformWheelGesture(deltaX = 0f, deltaY = -1f)
            val next = zoomViewportAtFocus(500, 1_000, zoom, wheel.zoomChange, 32f)
            zoom = next.zoom
            scroll = next.scroll
        }
        assertTrue(zoom <= 32f)
        assertTrue(scroll in 0f..1f)
        repeat(60) {
            val wheel = resolveWaveformWheelGesture(deltaX = 0f, deltaY = 1f)
            zoom = zoomViewportAtFocus(500, 1_000, zoom, wheel.zoomChange, 32f).zoom
        }
        assertEquals(1f, zoom)
    }
}
