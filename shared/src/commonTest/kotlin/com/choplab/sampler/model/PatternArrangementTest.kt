package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatternArrangementTest {
    private val patternA = setOf(stepKey(0, 0), stepKey(1, 4))
    private val patternB = setOf(stepKey(0, 8), stepKey(2, 12))

    @Test
    fun switchingVariationStoresCurrentStepsAndRestoresTheOtherSlot() {
        val state = SamplerUiState(
            activeSteps = patternA,
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), patternB),
            ),
        )

        val selectedB = state.selectPatternVariation(1)
        val returnedA = selectedB.selectPatternVariation(0)

        assertEquals(1, selectedB.patternArrangement.selectedSlot)
        assertEquals(patternB, selectedB.activeSteps)
        assertEquals(patternA, selectedB.patternArrangement.storedStepsBySlot[0])
        assertEquals(patternA, returnedA.activeSteps)
        assertEquals(patternB, returnedA.materializedPatternArrangement().storedStepsBySlot[1])
    }

    @Test
    fun duplicateCopiesTheSelectedPatternAndMovesEditingToTheOtherSlot() {
        val state = SamplerUiState(activeSteps = patternA)

        val duplicated = state.duplicateSelectedPatternToOther()

        assertEquals(1, duplicated.patternArrangement.selectedSlot)
        assertEquals(patternA, duplicated.activeSteps)
        assertEquals(listOf(patternA, patternA), duplicated.materializedPatternArrangement().storedStepsBySlot)
        assertTrue("B" in duplicated.statusMessage)
    }

    @Test
    fun fourSongSectionsResolveToMaterializedABOrder() {
        val state = SamplerUiState(
            activeSteps = patternB,
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(patternA, emptySet()),
                selectedSlot = 1,
                songSections = listOf(0, 1, 0, 1),
                songModeEnabled = true,
            ),
        )

        assertEquals(listOf(patternA, patternB, patternA, patternB), state.patternSequenceForPlayback())
        assertEquals(listOf(patternA, patternB, patternA, patternB), state.patternSequenceForExport())
        assertEquals(
            listOf(0, 0, 0, 0),
            state.toggleSongSectionPattern(1)
                .toggleSongSectionPattern(3)
                .patternArrangement.songSections,
        )
    }

    @Test
    fun patternModeExportsFourRepeatsWithoutChangingItsOneBarLiveLoop() {
        val state = SamplerUiState(activeSteps = patternA)

        assertEquals(listOf(patternA), state.patternSequenceForPlayback())
        assertEquals(List(4) { patternA }, state.patternSequenceForExport())
    }

    @Test
    fun songAudibilityCountsItsFourResolvedSectionsWhileAnyWorkIncludesInactiveB() {
        val audio = PcmAudio(name = "audible.wav", samples = ShortArray(32) { 1_000 }, sampleRate = 8_000)
        val pads = SamplerUiState().pads.toMutableList().also {
            it[2] = PadModel(2, audio, 0, audio.frameCount)
        }
        val state = SamplerUiState(
            pads = pads,
            activeSteps = emptySet(),
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), setOf(stepKey(2, 4))),
                songSections = listOf(1, 0, 1, 0),
                songModeEnabled = true,
            ),
        )

        assertTrue(state.hasAnyPatternSteps())
        assertTrue(state.hasAudiblePlaybackPatternContent())
        assertEquals(2, state.audiblePlaybackStepCount())
        assertFalse(state.copy(patternArrangement = state.patternArrangement.copy(songModeEnabled = false))
            .hasAudiblePlaybackPatternContent())
    }

    @Test
    fun arrangementChangesFailClosedWhileTransportIsPlaying() {
        val state = SamplerUiState(
            activeSteps = patternA,
            transportPlaying = true,
        )

        val selected = state.selectPatternVariation(1)
        val duplicated = state.duplicateSelectedPatternToOther()
        val section = state.toggleSongSectionPattern(0)
        val mode = state.toggleSongMode()

        listOf(selected, duplicated, section, mode).forEach { blocked ->
            assertEquals(state.activeSteps, blocked.activeSteps)
            assertEquals(state.patternArrangement, blocked.patternArrangement)
            assertTrue("停止" in blocked.statusMessage)
        }
    }

    @Test
    fun clearingOnePadRemovesItsEventsFromBothVariations() {
        val state = SamplerUiState(
            activeSteps = patternA,
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), patternB),
            ),
        )

        val cleared = state.removePadFromEveryPattern(0)
        val stored = cleared.materializedPatternArrangement().storedStepsBySlot

        assertFalse(stored.flatten().any { key -> key / SamplerConfig.STEP_COUNT == 0 })
        assertEquals(setOf(stepKey(1, 4)), stored[0])
        assertEquals(setOf(stepKey(2, 12)), stored[1])
    }

    @Test
    fun malformedArrangementIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            PatternArrangement(storedStepsBySlot = listOf(emptySet()))
        }
        assertFailsWith<IllegalArgumentException> {
            PatternArrangement(songSections = listOf(0, 1, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            PatternArrangement(selectedSlot = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            PatternArrangement(songSections = listOf(0, 0, 0, 2))
        }
    }

    @Test
    fun sharedCommandsMakeArrangementEditsOneUndoRedoAndPersistenceUnit() {
        val session = ProductionSession()
        val initial = SamplerUiState(activeSteps = patternA)

        val copied = session.commit(
            session.planCommand(initial, ProductionCommand.DuplicateSelectedPatternToOther),
        )
        val section = session.commit(
            session.planCommand(copied.state, ProductionCommand.ToggleSongSectionPattern(2)),
        )
        val songMode = session.commit(
            session.planCommand(section.state, ProductionCommand.ToggleSongMode),
        )

        assertEquals(3L, songMode.revision)
        assertTrue(songMode.persistenceRequired)
        assertEquals(listOf(0, 0, 1, 0), songMode.state.patternArrangement.songSections)
        assertTrue(songMode.state.patternArrangement.songModeEnabled)
        assertTrue(songMode.effects.single() is ProductionEffect.RefreshPattern)

        val undone = assertNotNull(session.undo(songMode.state))
        assertFalse(undone.state.patternArrangement.songModeEnabled)
        val redone = assertNotNull(session.redo(undone.state))
        assertTrue(redone.state.patternArrangement.songModeEnabled)
    }
}
