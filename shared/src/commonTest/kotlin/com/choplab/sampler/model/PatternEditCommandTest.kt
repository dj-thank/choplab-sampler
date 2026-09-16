package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatternEditCommandTest {
    private fun fixture(): SamplerUiState {
        val audio = PcmAudio(name = "fixture", samples = ShortArray(1000), sampleRate = 8000)
        return SamplerUiState(
            pads = List(SamplerConfig.PAD_COUNT) { PadModel(it, audio, 0, audio.frameCount) },
            selectedPad = 0,
            activeSteps = setOf(stepKey(0, 0), stepKey(0, 15), stepKey(32, 4)),
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), setOf(stepKey(1, 3))),
                songSections = listOf(0, 1, 0, 1), songModeEnabled = true,
            ),
        )
    }

    @Test fun shiftWrapsBothDirectionsAndPreservesOtherPadsAndVariation() {
        val before = fixture()
        val after = reduceProductionCommand(before, ProductionCommand.ShiftSelectedPadPattern(1))
        assertEquals(setOf(stepKey(0, 1), stepKey(0, 0), stepKey(32, 4)), after.state.activeSteps)
        assertEquals(before.patternArrangement, after.state.patternArrangement)
        assertEquals(listOf(ProductionEffect.RefreshPattern), after.effects)
        assertEquals(before.activeSteps, reduceProductionCommand(after.state,
            ProductionCommand.ShiftSelectedPadPattern(-1)).state.activeSteps)
        assertEquals(before.activeSteps, before.activeSteps.shiftPadSteps(0, Int.MIN_VALUE))
        assertEquals(before.activeSteps.shiftPadSteps(0, -1), before.activeSteps.shiftPadSteps(0, Int.MAX_VALUE))
    }

    @Test fun clearEitherVariationPreservesOtherMusicAndSong() {
        for (slot in 0..1) {
            val before = fixture().selectPatternVariation(slot)
            val cleared = reduceProductionCommand(before, ProductionCommand.ClearSelectedPattern)
            val expected = before.materializedPatternArrangement()
            val actual = cleared.state.materializedPatternArrangement()
            assertTrue(cleared.state.activeSteps.isEmpty())
            assertEquals(expected.storedStepsBySlot[1-slot], actual.storedStepsBySlot[1-slot])
            assertEquals(expected.songSections, actual.songSections)
            assertEquals(expected.songModeEnabled, actual.songModeEnabled)
            assertEquals(before.pads, cleared.state.pads)
            assertEquals(ProductionMutation.NONE,
                reduceProductionCommand(cleared.state, ProductionCommand.ClearSelectedPattern).mutation)
        }
    }

    @Test fun editsAreSeparateUndoUnitsAndPersistOnlyWhenChanged() {
        val session = ProductionSession()
        val before = fixture()
        val shifted = session.commit(session.planCommand(before, ProductionCommand.ShiftSelectedPadPattern(1)))
        val cleared = session.commit(session.planCommand(shifted.state, ProductionCommand.ClearSelectedPattern))
        assertEquals(2L, cleared.revision)
        assertTrue(cleared.persistenceRequired)
        val undoClear = assertNotNull(session.undo(cleared.state))
        assertEquals(shifted.state.activeSteps, undoClear.state.activeSteps)
        val undoShift = assertNotNull(session.undo(undoClear.state))
        assertEquals(before.activeSteps, undoShift.state.activeSteps)
        val redoShift = assertNotNull(session.redo(undoShift.state))
        val redoClear = assertNotNull(session.redo(redoShift.state))
        assertTrue(redoClear.state.activeSteps.isEmpty())
        val noop = session.commit(session.planCommand(redoClear.state, ProductionCommand.ClearSelectedPattern))
        assertEquals(redoClear.revision, noop.revision)
        assertFalse(noop.persistenceRequired)
    }

    @Test fun allPatternCommandsRejectLoadingAndEveryRecordingPhase() {
        val commands = listOf(ProductionCommand.FillSelectedPadPattern(RepeatGrid.EIGHTH),
            ProductionCommand.ClearSelectedPadPattern, ProductionCommand.ShiftSelectedPadPattern(1),
            ProductionCommand.ClearSelectedPattern, ProductionCommand.ClearAllPatterns)
        val states = listOf(fixture().copy(isLoading = true)) + RecordingKind.entries.flatMap { kind ->
            RecordingPhase.entries.map { phase -> fixture().copy(recordingSession = RecordingSession.Active(kind, phase)) }
        }
        for (state in states) for (command in commands) {
            val result = reduceProductionCommand(state, command)
            assertEquals(state.activeSteps, result.state.activeSteps)
            assertEquals(state.patternArrangement, result.state.patternArrangement)
            assertFalse(result.mutation == ProductionMutation.PROJECT)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test fun ineligiblePadsCannotBeFilledOrShiftedAndClearRetainsCleanupCapability() {
        val before = fixture()
        for (pad in listOf(PadModel(0), before.pads[0].copy(playMode = PadPlayMode.LOOP),
            before.pads[0].copy(contentKind = PadContentKind.VOCAL))) {
            val state = before.copy(pads = before.pads.toMutableList().also { it[0] = pad })
            for (command in listOf(ProductionCommand.FillSelectedPadPattern(RepeatGrid.QUARTER),
                ProductionCommand.ShiftSelectedPadPattern(1))) {
                assertEquals(state.activeSteps, reduceProductionCommand(state, command).state.activeSteps)
            }
            assertEquals(setOf(stepKey(32, 4)),
                reduceProductionCommand(state, ProductionCommand.ClearSelectedPadPattern).state.activeSteps)
        }
    }

    @Test fun repeatedPresetAndSymmetricShiftDoNotCreateHistory() {
        val filled = reduceProductionCommand(fixture(), ProductionCommand.FillSelectedPadPattern(RepeatGrid.SIXTEENTH))
        assertEquals(ProductionMutation.PROJECT, filled.mutation)
        assertEquals(ProductionMutation.NONE, reduceProductionCommand(filled.state,
            ProductionCommand.FillSelectedPadPattern(RepeatGrid.SIXTEENTH)).mutation)
        assertEquals(ProductionMutation.NONE, reduceProductionCommand(filled.state,
            ProductionCommand.ShiftSelectedPadPattern(1)).mutation)
        val playing = fixture().copy(transportPlaying = true)
        assertEquals(playing.activeSteps, reduceProductionCommand(playing,
            ProductionCommand.ClearSelectedPattern).state.activeSteps)
    }
}
