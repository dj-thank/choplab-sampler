package com.choplab.sampler.ui

import com.choplab.sampler.model.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternEditingPresentationTest {
    private fun fixture(): SamplerUiState {
        val audio = PcmAudio(name = "fixture", samples = ShortArray(100), sampleRate = 8000)
        return SamplerUiState(pads = List(SamplerConfig.PAD_COUNT) { PadModel(it, audio, 0, 100) },
            activeSteps = setOf(stepKey(0, 0)))
    }

    @Test fun displayAdmissionMatchesEveryRecordingAndLoadingState() {
        val states = listOf(fixture().copy(isLoading = true)) + RecordingKind.entries.flatMap { kind ->
            RecordingPhase.entries.map { phase -> fixture().copy(recordingSession = RecordingSession.Active(kind, phase)) }
        }
        for (state in states) {
            val editing = patternEditingPresentation(state)
            assertFalse(editing.fillEnabled)
            assertFalse(editing.clearEnabled)
            assertFalse(editing.shiftEnabled)
            assertFalse(arrangementStudioPresentation(state).editEnabled)
            assertTrue(projectEditBlockedReason(state) != null)
            assertTrue(chopProductionDockItems(state).all { !it.enabled })
            assertTrue(captureProductionDockItems(state).all { !it.enabled })
            val dock = beatProductionDockItems(false, externalDocumentActionsEnabled(state))
            assertTrue(dock.filter { it.intent in listOf(ProductionDockIntent.SHOW_QUICK, ProductionDockIntent.SHOW_STEPS) }.all { it.enabled })
            assertTrue(dock.filter { it.intent !in listOf(ProductionDockIntent.SHOW_QUICK, ProductionDockIntent.SHOW_STEPS) }.all { !it.enabled })
            assertTrue(editing.guidance.isNotBlank())
            assertTrue(arrangementStudioPresentation(state).guidance.isNotBlank())
        }
    }

    @Test fun completedRecordingAndAnalysisReleaseTheirOwnEditBarrier() {
        val stopping = fixture().copy(recordingSession = RecordingSession.Active(
            RecordingKind.VOCAL_OVERDUB, RecordingPhase.STOPPING))
        assertTrue(projectEditBlockedReason(stopping) != null)
        assertTrue(projectEditBlockedReason(endRecordingSession(stopping, RecordingKind.VOCAL_OVERDUB)) == null)
        assertTrue(projectEditBlockedReason(endRecordingSession(stopping, RecordingKind.SOURCE_MICROPHONE)) != null)
        val loading = fixture().copy(isLoading = true)
        assertTrue(projectEditBlockedReason(loading) != null)
        assertTrue(projectEditBlockedReason(loading.copy(isLoading = false)) == null)
    }

    @Test fun noOpButtonsAreDisabledButStaleStepsCanStillBeCleared() {
        val before = fixture()
        assertTrue(patternEditingPresentation(before).shiftEnabled)
        assertTrue(patternEditingPresentation(before).clearEnabled)
        val empty = patternEditingPresentation(before.copy(activeSteps = emptySet()))
        assertFalse(empty.clearEnabled)
        assertFalse(empty.shiftEnabled)
        assertTrue(empty.fillEnabled)
        val full = patternEditingPresentation(before.copy(activeSteps = (0..15).toSet()))
        assertFalse(full.shiftEnabled)
        for (pad in listOf(PadModel(0), before.pads[0].copy(playMode = PadPlayMode.LOOP),
            before.pads[0].copy(contentKind = PadContentKind.VOCAL))) {
            val p = patternEditingPresentation(before.copy(pads = before.pads.toMutableList().also { it[0] = pad }))
            assertFalse(p.fillEnabled)
            assertFalse(p.shiftEnabled)
            assertTrue(p.clearEnabled)
        }
    }

    @Test fun ordinaryPatternEditingStaysLiveButArrangementRequiresStoppedTransport() {
        val state = fixture().copy(transportPlaying = true)
        assertTrue(patternEditingPresentation(state).fillEnabled)
        assertTrue(patternEditingPresentation(state).shiftEnabled)
        assertFalse(arrangementStudioPresentation(state).editEnabled)
        assertTrue(projectEditBlockedReason(state) == null)
    }
}
