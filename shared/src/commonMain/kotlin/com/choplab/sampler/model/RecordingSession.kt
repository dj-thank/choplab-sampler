package com.choplab.sampler.model

enum class RecordingKind {
    SOURCE_MICROPHONE,
    SOURCE_SYSTEM_AUDIO,
    VOCAL_OVERDUB,
}

enum class RecordingPhase {
    STARTING,
    RECORDING,
    STOPPING,
}

sealed interface RecordingSession {
    data object Idle : RecordingSession

    data class Active(
        val kind: RecordingKind,
        val phase: RecordingPhase,
    ) : RecordingSession
}

val RecordingSession.isActive: Boolean
    get() = this is RecordingSession.Active

fun RecordingSession.isActiveKind(kind: RecordingKind): Boolean =
    this is RecordingSession.Active && this.kind == kind

data class RecordingStartPolicy(
    val allowed: Boolean,
    val stopAllPlaybackBeforeStart: Boolean,
    val allowBeatLoopDuringRecording: Boolean,
)

fun recordingStartPolicy(
    current: RecordingSession,
    requested: RecordingKind,
): RecordingStartPolicy = RecordingStartPolicy(
    allowed = current == RecordingSession.Idle,
    stopAllPlaybackBeforeStart = true,
    allowBeatLoopDuringRecording = requested == RecordingKind.VOCAL_OVERDUB,
)

fun playbackRequestAllowedDuringRecording(
    session: RecordingSession,
    startsPlayback: Boolean,
): Boolean = session == RecordingSession.Idle || !startsPlayback

fun editingRequestAllowedDuringRecording(session: RecordingSession): Boolean =
    session == RecordingSession.Idle

fun RecordingSession.withPhase(phase: RecordingPhase): RecordingSession = when (this) {
    RecordingSession.Idle -> this
    is RecordingSession.Active -> copy(phase = phase)
}

fun RecordingSession.canRequestStop(kind: RecordingKind): Boolean =
    this is RecordingSession.Active && this.kind == kind && phase != RecordingPhase.STOPPING

fun RecordingSession.activePhaseFor(kind: RecordingKind): RecordingPhase? =
    (this as? RecordingSession.Active)?.takeIf { it.kind == kind }?.phase

fun beginRecordingSession(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    if (recordingStartPolicy(state.recordingSession, kind).allowed) {
        state.copy(recordingSession = RecordingSession.Active(kind, RecordingPhase.STARTING))
    } else {
        state
    }

fun observeRecordingSession(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    when (val session = state.recordingSession) {
        RecordingSession.Idle -> state.copy(
            recordingSession = RecordingSession.Active(kind, RecordingPhase.RECORDING),
        )
        is RecordingSession.Active -> if (
            session.kind == kind && session.phase != RecordingPhase.STOPPING
        ) {
            state.copy(recordingSession = session.copy(phase = RecordingPhase.RECORDING))
        } else {
            state
        }
    }

/**
 * Confirms a locally requested recorder only after its native startup succeeds.
 * Unlike a service observation, a late callback cannot recreate an idle session or undo STOPPING.
 */
fun confirmRecordingSessionStarted(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    when (val session = state.recordingSession) {
        RecordingSession.Idle -> state
        is RecordingSession.Active -> if (
            session.kind == kind && session.phase == RecordingPhase.STARTING
        ) {
            state.copy(recordingSession = session.copy(phase = RecordingPhase.RECORDING))
        } else {
            state
        }
    }

fun stopRecordingSession(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    when (val session = state.recordingSession) {
        RecordingSession.Idle -> state
        is RecordingSession.Active -> if (session.kind == kind) {
            state.copy(recordingSession = session.copy(phase = RecordingPhase.STOPPING))
        } else {
            state
        }
    }

fun endRecordingSession(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    when (val session = state.recordingSession) {
        RecordingSession.Idle -> state
        is RecordingSession.Active -> if (session.kind == kind) {
            state.copy(recordingSession = RecordingSession.Idle)
        } else {
            state
        }
    }

/**
 * Applies an asynchronous recorder failure unless a user-requested stop already owns cleanup.
 * A late worker callback must not make STOPPING look idle before the stop coroutine finishes.
 */
fun failRecordingSession(state: SamplerUiState, kind: RecordingKind): SamplerUiState =
    when (val session = state.recordingSession) {
        RecordingSession.Idle -> state
        is RecordingSession.Active -> if (
            session.kind == kind && session.phase != RecordingPhase.STOPPING
        ) {
            state.copy(recordingSession = RecordingSession.Idle)
        } else {
            state
        }
    }
