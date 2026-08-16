package com.choplab.sampler.ui

import kotlin.math.roundToInt

/** Pure, shared viewport rules for the phone waveform surfaces. */
internal data class WaveformViewport(
    val totalFrames: Int,
    val zoom: Float,
    val scroll: Float,
    val visibleStart: Int,
    val visibleFrames: Int,
)

internal fun waveformFrameAtX(
    x: Float,
    width: Float,
    visibleStart: Int,
    visibleFrames: Int,
    totalFrames: Int,
): Int {
    val safeTotal = totalFrames.coerceAtLeast(1)
    val safeWidth = width.takeIf { it.isFinite() && it > 0f } ?: 1f
    val fraction = (x / safeWidth).coerceIn(0f, 1f)
    return (visibleStart.coerceAtLeast(0) + visibleFrames.coerceAtLeast(1) * fraction)
        .roundToInt()
        .coerceIn(0, safeTotal - 1)
}

internal fun zoomViewportAtFocus(
    frame: Int,
    totalFrames: Int,
    zoom: Float,
    zoomChange: Float,
    maximumZoom: Float,
): WaveformViewport {
    val safeTotal = totalFrames.coerceAtLeast(1)
    val safeMaximum = maximumZoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val safeZoom = zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    val safeChange = zoomChange.takeIf { it.isFinite() && it > 0f } ?: 1f
    val nextZoom = (safeZoom * safeChange).coerceIn(1f, safeMaximum)
    val visibleFrames = (safeTotal / nextZoom).roundToInt().coerceIn(1, safeTotal)
    val maximumStart = (safeTotal - visibleFrames).coerceAtLeast(0)
    val focus = frame.coerceIn(0, safeTotal - 1)
    val centeredStart = (focus - visibleFrames / 2).coerceIn(0, maximumStart)
    val scroll = if (maximumStart == 0) 0f else centeredStart.toFloat() / maximumStart
    return WaveformViewport(safeTotal, nextZoom, scroll, centeredStart, visibleFrames)
}
