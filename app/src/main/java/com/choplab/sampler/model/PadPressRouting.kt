package com.choplab.sampler.model

enum class PadSurfaceMode {
    CAPTURE,
    PERFORMANCE,
}

enum class PadPressAction {
    CAPTURE_CHOP,
    PLAY_ASSIGNED,
    SELECT_ONLY,
}

fun resolvePadPressAction(
    sourcePlaying: Boolean,
    padAssigned: Boolean,
    surfaceMode: PadSurfaceMode,
): PadPressAction = when {
    sourcePlaying && surfaceMode == PadSurfaceMode.CAPTURE -> PadPressAction.CAPTURE_CHOP
    padAssigned -> PadPressAction.PLAY_ASSIGNED
    else -> PadPressAction.SELECT_ONLY
}

fun sourcePlaybackStartFrame(requestedFrame: Int, frameCount: Int): Int {
    if (frameCount <= 1) return 0
    return requestedFrame.takeIf { it in 0 until frameCount - 1 } ?: 0
}
