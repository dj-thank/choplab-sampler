package com.choplab.sampler.ui

import kotlin.math.roundToInt

/** Pure, shared viewport rules for the phone waveform surfaces. */
data class WaveformViewport(
    val totalFrames: Int,
    val zoom: Float,
    val scroll: Float,
    val visibleStart: Int,
    val visibleFrames: Int,
)

fun resolveWaveformViewport(
    totalFrames: Int,
    zoom: Float,
    scroll: Float,
): WaveformViewport {
    val safeTotal = totalFrames.coerceAtLeast(1)
    val safeZoom = zoom.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 1f
    val visibleFrames = (safeTotal / safeZoom).roundToInt().coerceIn(1, safeTotal)
    val maximumStart = (safeTotal - visibleFrames).coerceAtLeast(0)
    val safeScroll = if (maximumStart == 0) 0f else scroll.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val visibleStart = (maximumStart * safeScroll).roundToInt().coerceIn(0, maximumStart)
    return WaveformViewport(safeTotal, safeZoom, safeScroll, visibleStart, visibleFrames)
}

/** Speaks the first and inclusive last displayed frame IDs; model ranges remain end-exclusive. */
fun waveformViewportStateDescription(viewport: WaveformViewport): String {
    val visibleEndInclusive = (viewport.visibleStart + viewport.visibleFrames - 1)
        .coerceIn(viewport.visibleStart, viewport.totalFrames - 1)
    return if (viewport.visibleStart == 0 && viewport.visibleFrames == viewport.totalFrames) {
        "全体表示。0から${visibleEndInclusive}フレーム"
    } else {
        "拡大表示。${viewport.visibleStart}から${visibleEndInclusive}フレーム。全体${viewport.totalFrames}フレーム"
    }
}

fun waveformFrameAtX(
    x: Float,
    width: Float,
    visibleStart: Int,
    visibleFrames: Int,
    totalFrames: Int,
): Int {
    val safeTotal = totalFrames.coerceAtLeast(1)
    if (!width.isFinite() || width <= 0f) return visibleStart.coerceIn(0, safeTotal - 1)
    val safeWidth = width
    val fraction = (x / safeWidth).coerceIn(0f, 1f)
    return (visibleStart.coerceAtLeast(0) + visibleFrames.coerceAtLeast(1) * fraction)
        .roundToInt()
        .coerceIn(0, safeTotal - 1)
}

fun zoomViewportAtFocus(
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

fun panWaveformViewport(
    totalFrames: Int,
    zoom: Float,
    scroll: Float,
    fraction: Float,
): WaveformViewport {
    val base = zoomViewportAtFocus(
        frame = 0,
        totalFrames = totalFrames,
        zoom = zoom,
        zoomChange = 1f,
        maximumZoom = zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f,
    )
    val maximumStart = (base.totalFrames - base.visibleFrames).coerceAtLeast(0)
    if (maximumStart == 0) return base.copy(scroll = 0f, visibleStart = 0)
    val safeScroll = scroll.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val safeFraction = fraction.takeIf(Float::isFinite) ?: 0f
    val currentStart = (maximumStart * safeScroll).roundToInt()
    val nextStart = (currentStart + base.visibleFrames * safeFraction)
        .roundToInt()
        .coerceIn(0, maximumStart)
    return base.copy(
        scroll = nextStart.toFloat() / maximumStart,
        visibleStart = nextStart,
    )
}
