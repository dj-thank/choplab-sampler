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

enum class SourcePlaybackRequest {
    START,
    STOP,
    RESTART,
    SEEK,
}

enum class SourcePlaybackToggleAction {
    START,
    STOP,
}

data class SourcePlaybackRequestFeedback(
    val sourcePlaying: Boolean,
    val statusMessage: String,
)

fun resolvePadPressAction(
    sourcePlaying: Boolean,
    padAssigned: Boolean,
    surfaceMode: PadSurfaceMode,
): PadPressAction = when {
    sourcePlaying && !padAssigned && surfaceMode == PadSurfaceMode.CAPTURE -> PadPressAction.CAPTURE_CHOP
    padAssigned -> PadPressAction.PLAY_ASSIGNED
    else -> PadPressAction.SELECT_ONLY
}

fun sourcePlaybackStartFrame(requestedFrame: Int, frameCount: Int): Int {
    if (frameCount <= 1) return 0
    return requestedFrame.takeIf { it in 0 until frameCount - 1 } ?: 0
}

fun sourcePlaybackToggleAction(
    appliedPlaying: Boolean,
    startPending: Boolean,
): SourcePlaybackToggleAction =
    if (appliedPlaying || startPending) {
        SourcePlaybackToggleAction.STOP
    } else {
        SourcePlaybackToggleAction.START
    }

/** Keeps UI playback truth on the last command applied by the audio thread. */
fun sourcePlaybackRequestFeedback(
    appliedPlaying: Boolean,
    request: SourcePlaybackRequest,
): SourcePlaybackRequestFeedback = SourcePlaybackRequestFeedback(
    sourcePlaying = appliedPlaying,
    statusMessage = when (request) {
        SourcePlaybackRequest.START -> if (appliedPlaying) {
            "サンプリング中 — 「ここだ」で空PADを叩いてください"
        } else {
            "再生を準備中 — 音が鳴ってから空PADを叩いてください"
        }
        SourcePlaybackRequest.STOP -> if (appliedPlaying) {
            "停止を準備中 — 音が止まるまでお待ちください"
        } else {
            "停止中 — PADでチョップを演奏できます"
        }
        SourcePlaybackRequest.RESTART -> if (appliedPlaying) {
            "再生中 — 曲の頭へ移動します"
        } else {
            "曲の頭から再生を準備中 — 音が鳴ってから空PADを叩いてください"
        }
        SourcePlaybackRequest.SEEK -> if (appliedPlaying) {
            "再生中 — 選んだ位置へ移動します"
        } else {
            "選んだ位置から再生を準備中 — 音が鳴ってから空PADを叩いてください"
        }
    },
)

fun sourcePlaybackAppliedStatusMessage(
    previouslyApplied: Boolean,
    nowApplied: Boolean,
    currentMessage: String,
): String = when {
    !previouslyApplied && nowApplied ->
        "サンプリング中 — 「ここだ」で空PADを叩いてください"
    previouslyApplied && !nowApplied && currentMessage.startsWith("停止を準備中") ->
        "停止中 — PADでチョップを演奏できます"
    previouslyApplied && !nowApplied ->
        "曲の再生が終わりました — PADでチョップを演奏できます"
    else -> currentMessage
}
