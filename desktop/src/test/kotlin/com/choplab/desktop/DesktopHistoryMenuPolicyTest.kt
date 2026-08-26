package com.choplab.desktop

import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHistoryMenuPolicyTest {
    @Test
    fun nativeMenuUsesTheSameBusyAndDirectionTruthAsTheSharedDeck() {
        val idle = SamplerUiState(canUndo = true, canRedo = false)
        assertTrue(desktopHistoryActionEnabled(idle, DesktopHistoryAction.UNDO))
        assertFalse(desktopHistoryActionEnabled(idle, DesktopHistoryAction.REDO))

        val loading = idle.copy(isLoading = true, canRedo = true)
        assertFalse(desktopHistoryActionEnabled(loading, DesktopHistoryAction.UNDO))
        assertFalse(desktopHistoryActionEnabled(loading, DesktopHistoryAction.REDO))

        val recording = idle.copy(
            canRedo = true,
            recordingSession = RecordingSession.Active(
                RecordingKind.VOCAL_OVERDUB,
                RecordingPhase.RECORDING,
            ),
        )
        assertFalse(desktopHistoryActionEnabled(recording, DesktopHistoryAction.UNDO))
        assertFalse(desktopHistoryActionEnabled(recording, DesktopHistoryAction.REDO))
    }
}
