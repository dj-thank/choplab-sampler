package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRequestAdmissionTest {
    @Test
    fun idleStateAdmitsOnlyTheHistoryDirectionsThatExist() {
        val undoOnly = SamplerUiState(canUndo = true, canRedo = false)
        val redoOnly = SamplerUiState(canUndo = false, canRedo = true)

        assertNull(undoOnly.historyRequestDenial)
        assertTrue(undoOnly.undoRequestEnabled)
        assertFalse(undoOnly.redoRequestEnabled)
        assertFalse(redoOnly.undoRequestEnabled)
        assertTrue(redoOnly.redoRequestEnabled)
    }

    @Test
    fun loadingTakesOwnershipBeforeAnyHistoryDirection() {
        val loading = SamplerUiState(
            isLoading = true,
            canUndo = true,
            canRedo = true,
        )

        assertEquals(HistoryRequestDenial.LOADING, loading.historyRequestDenial)
        assertFalse(loading.undoRequestEnabled)
        assertFalse(loading.redoRequestEnabled)
    }

    @Test
    fun everyActiveRecordingKindAndPhaseRejectsBothHistoryDirections() {
        RecordingKind.entries.forEach { kind ->
            RecordingPhase.entries.forEach { phase ->
                val recording = SamplerUiState(
                    recordingSession = RecordingSession.Active(kind, phase),
                    canUndo = true,
                    canRedo = true,
                )

                assertEquals(HistoryRequestDenial.RECORDING, recording.historyRequestDenial)
                assertFalse(recording.undoRequestEnabled)
                assertFalse(recording.redoRequestEnabled)
            }
        }
    }

    @Test
    fun loadingPrecedesRecordingWhenBothRuntimeOwnersAreVisible() {
        val state = SamplerUiState(
            isLoading = true,
            recordingSession = RecordingSession.Active(
                RecordingKind.SOURCE_MICROPHONE,
                RecordingPhase.STOPPING,
            ),
            canUndo = true,
        )

        assertEquals(HistoryRequestDenial.LOADING, state.historyRequestDenial)
        assertFalse(state.undoRequestEnabled)
    }
}
