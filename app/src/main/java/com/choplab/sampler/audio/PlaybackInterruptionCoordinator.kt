package com.choplab.sampler.audio

import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession

/** Android audio focus is an external resource owned through this boundary. */
interface PlaybackFocusAdapter {
    fun requestPlaybackFocus(): Boolean

    fun abandonPlaybackFocus()

    fun close()
}

enum class PlaybackStartDecision {
    READY,
    FOCUS_DENIED,
}

enum class PlaybackInterruption {
    APP_BACKGROUND,
    AUDIO_FOCUS_LOSS,
    OUTPUT_BECOMING_NOISY,
}

data class PlaybackInterruptionPlan(
    val stopPlayback: Boolean,
    val requestRecordingStop: Boolean,
    val statusMessage: String,
)

class PlaybackInterruptionCoordinator(
    private val focusAdapter: PlaybackFocusAdapter,
) {
    private var playbackSessionActive = false
    private var closed = false

    fun beginPlayback(): PlaybackStartDecision {
        if (closed) return PlaybackStartDecision.FOCUS_DENIED
        if (playbackSessionActive) return PlaybackStartDecision.READY
        if (!focusAdapter.requestPlaybackFocus()) return PlaybackStartDecision.FOCUS_DENIED

        playbackSessionActive = true
        return PlaybackStartDecision.READY
    }

    /** Retargeting may reuse playback only while this coordinator still owns the session. */
    fun canRetargetPlayback(): Boolean = !closed && playbackSessionActive

    fun endPlaybackSession() {
        if (!playbackSessionActive) return

        playbackSessionActive = false
        focusAdapter.abandonPlaybackFocus()
    }

    fun close() {
        if (closed) return

        endPlaybackSession()
        closed = true
        focusAdapter.close()
    }

    fun interrupt(
        event: PlaybackInterruption,
        recordingSession: RecordingSession,
    ): PlaybackInterruptionPlan? {
        if (closed) return null
        val stopPlayback = playbackSessionActive
        val activeRecording = recordingSession as? RecordingSession.Active
        val requestRecordingStop = activeRecording != null &&
            activeRecording.phase != RecordingPhase.STOPPING &&
            activeRecording.kind != RecordingKind.SOURCE_SYSTEM_AUDIO
        if (!stopPlayback && !requestRecordingStop) return null

        if (stopPlayback) {
            playbackSessionActive = false
            focusAdapter.abandonPlaybackFocus()
        }
        return PlaybackInterruptionPlan(
            stopPlayback = stopPlayback,
            requestRecordingStop = requestRecordingStop,
            statusMessage = event.statusMessage(
                stopPlayback = stopPlayback,
                activeRecording = activeRecording,
                requestRecordingStop = requestRecordingStop,
            ),
        )
    }
}

private fun PlaybackInterruption.statusMessage(
    stopPlayback: Boolean,
    activeRecording: RecordingSession.Active?,
    requestRecordingStop: Boolean,
): String {
    val reason = when (this) {
        PlaybackInterruption.APP_BACKGROUND -> "バックグラウンド移行のため"
        PlaybackInterruption.AUDIO_FOCUS_LOSS -> "他のアプリが音声を使用したため"
        PlaybackInterruption.OUTPUT_BECOMING_NOISY -> "音声出力が切り替わったため"
    }
    val recordingLabel = when (activeRecording?.kind) {
        RecordingKind.SOURCE_MICROPHONE -> "マイク素材録音"
        RecordingKind.SOURCE_SYSTEM_AUDIO -> "端末音声録音"
        RecordingKind.VOCAL_OVERDUB -> "ボーカル録音"
        null -> null
    }
    val action = when {
        stopPlayback && requestRecordingStop -> "再生と${requireNotNull(recordingLabel)}を停止します"
        requestRecordingStop -> "${requireNotNull(recordingLabel)}を停止します"
        else -> "再生を停止しました"
    }
    val continuation = if (
        stopPlayback &&
        activeRecording?.kind == RecordingKind.SOURCE_SYSTEM_AUDIO &&
        activeRecording.phase != RecordingPhase.STOPPING
    ) {
        "。端末音声録音は継続します"
    } else {
        ""
    }
    return reason + action + continuation
}
