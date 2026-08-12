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

enum class PerformancePadPressAction {
    TOGGLE_LOOP,
    TRIGGER_ONLY,
    TRIGGER_AND_RECORD_STEP,
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

enum class SourceUiPhase {
    STOPPED,
    STARTING,
    PLAYING,
    STOPPING,
}

data class SourcePlaybackRequestFeedback(
    val sourcePlaying: Boolean,
    val statusMessage: String,
)

fun sourceUiPhase(
    appliedPlaying: Boolean,
    pendingCommand: PendingSourceCommand,
): SourceUiPhase = when {
    pendingCommand == PendingSourceCommand.START && !appliedPlaying -> SourceUiPhase.STARTING
    pendingCommand == PendingSourceCommand.STOP && appliedPlaying -> SourceUiPhase.STOPPING
    appliedPlaying -> SourceUiPhase.PLAYING
    else -> SourceUiPhase.STOPPED
}

fun SamplerUiState.sourceUiPhase(): SourceUiPhase = sourceUiPhase(
    appliedPlaying = sourcePlaying,
    pendingCommand = pendingSourceCommand,
)

fun reconcilePendingSourceCommand(
    pendingCommand: PendingSourceCommand,
    appliedPlaying: Boolean,
): PendingSourceCommand = when (pendingCommand) {
    PendingSourceCommand.START -> if (appliedPlaying) {
        PendingSourceCommand.NONE
    } else {
        PendingSourceCommand.START
    }
    PendingSourceCommand.STOP -> if (appliedPlaying) {
        PendingSourceCommand.STOP
    } else {
        PendingSourceCommand.NONE
    }
    PendingSourceCommand.NONE -> PendingSourceCommand.NONE
}

fun resolvePadPressAction(
    sourcePlaying: Boolean,
    padAssigned: Boolean,
    surfaceMode: PadSurfaceMode,
): PadPressAction = when {
    sourcePlaying && !padAssigned && surfaceMode == PadSurfaceMode.CAPTURE -> PadPressAction.CAPTURE_CHOP
    padAssigned -> PadPressAction.PLAY_ASSIGNED
    else -> PadPressAction.SELECT_ONLY
}

fun resolvePerformancePadPressAction(
    pad: PadModel,
    recordArmed: Boolean,
    transportPlaying: Boolean,
): PerformancePadPressAction = when {
    pad.playMode == PadPlayMode.LOOP -> PerformancePadPressAction.TOGGLE_LOOP
    recordArmed && transportPlaying && pad.canUsePatternSteps() ->
        PerformancePadPressAction.TRIGGER_AND_RECORD_STEP
    else -> PerformancePadPressAction.TRIGGER_ONLY
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
    previouslyApplied && !nowApplied && (
        currentMessage.startsWith("すべての再生音を停止") ||
            currentMessage.startsWith("再生音を全停止")
        ) -> currentMessage
    previouslyApplied && !nowApplied ->
        "曲の再生が終わりました — PADでチョップを演奏できます"
    else -> currentMessage
}
