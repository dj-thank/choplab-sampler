package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionPolicyTest {
    @Test
    fun recordingTruthHasExactlyOneKindAndPhase() {
        val microphone = RecordingSession.Active(
            kind = RecordingKind.SOURCE_MICROPHONE,
            phase = RecordingPhase.RECORDING,
        )
        val state = SamplerUiState(recordingSession = microphone)

        assertTrue(state.microphoneRecording)
        assertFalse(state.systemAudioRecording)
        assertFalse(state.vocalOverdubRecording)
        assertEquals(microphone, state.recordingSession)
    }

    @Test
    fun everyRecordingStartsFromSilenceAndOnlyVocalMayRestoreTheBeatLoop() {
        val source = recordingStartPolicy(RecordingSession.Idle, RecordingKind.SOURCE_SYSTEM_AUDIO)
        assertTrue(source.allowed)
        assertTrue(source.stopAllPlaybackBeforeStart)
        assertFalse(source.allowBeatLoopDuringRecording)

        val vocal = recordingStartPolicy(RecordingSession.Idle, RecordingKind.VOCAL_OVERDUB)
        assertTrue(vocal.allowed)
        assertTrue(vocal.stopAllPlaybackBeforeStart)
        assertTrue(vocal.allowBeatLoopDuringRecording)
    }

    @Test
    fun activeSessionRejectsOverlapAndBlocksOnlyPlaybackStarts() {
        val active = RecordingSession.Active(
            kind = RecordingKind.SOURCE_MICROPHONE,
            phase = RecordingPhase.RECORDING,
        )

        assertFalse(recordingStartPolicy(active, RecordingKind.VOCAL_OVERDUB).allowed)
        assertFalse(playbackRequestAllowedDuringRecording(active, startsPlayback = true))
        assertTrue(playbackRequestAllowedDuringRecording(active, startsPlayback = false))
        assertTrue(playbackRequestAllowedDuringRecording(RecordingSession.Idle, startsPlayback = true))
    }

    @Test
    fun startingAndStoppingRemainVisibleAsTheSameRecordingSession() {
        val starting = RecordingSession.Active(
            kind = RecordingKind.SOURCE_SYSTEM_AUDIO,
            phase = RecordingPhase.STARTING,
        )
        val stopping = starting.copy(phase = RecordingPhase.STOPPING)

        assertTrue(starting.isActive)
        assertTrue(stopping.isActive)
        assertEquals(RecordingKind.SOURCE_SYSTEM_AUDIO, stopping.kind)
        assertTrue(starting.canRequestStop(RecordingKind.SOURCE_SYSTEM_AUDIO))
        assertFalse(stopping.canRequestStop(RecordingKind.SOURCE_SYSTEM_AUDIO))
    }

    @Test
    fun staleCallbacksCannotChangeAnotherRecordingKind() {
        val microphoneStarting = beginRecordingSession(
            SamplerUiState(),
            RecordingKind.SOURCE_MICROPHONE,
        )
        val wrongObservation = observeRecordingSession(
            microphoneStarting,
            RecordingKind.SOURCE_SYSTEM_AUDIO,
        )
        val wrongCompletion = endRecordingSession(
            wrongObservation,
            RecordingKind.SOURCE_SYSTEM_AUDIO,
        )

        assertEquals(microphoneStarting.recordingSession, wrongObservation.recordingSession)
        assertEquals(microphoneStarting.recordingSession, wrongCompletion.recordingSession)

        val recording = observeRecordingSession(
            microphoneStarting,
            RecordingKind.SOURCE_MICROPHONE,
        )
        val stopping = stopRecordingSession(recording, RecordingKind.SOURCE_MICROPHONE)
        val ended = endRecordingSession(stopping, RecordingKind.SOURCE_MICROPHONE)

        assertEquals(RecordingPhase.RECORDING, recording.recordingSession.activePhaseFor(RecordingKind.SOURCE_MICROPHONE))
        assertEquals(RecordingPhase.STOPPING, stopping.recordingSession.activePhaseFor(RecordingKind.SOURCE_MICROPHONE))
        assertEquals(RecordingSession.Idle, ended.recordingSession)
    }

    @Test
    fun lateRecordingObservationCannotUndoAStopRequest() {
        val stopping = SamplerUiState(
            recordingSession = RecordingSession.Active(
                RecordingKind.SOURCE_SYSTEM_AUDIO,
                RecordingPhase.STOPPING,
            ),
        )

        val lateServiceObservation = observeRecordingSession(
            stopping,
            RecordingKind.SOURCE_SYSTEM_AUDIO,
        )

        assertEquals(stopping.recordingSession, lateServiceObservation.recordingSession)
    }
}
