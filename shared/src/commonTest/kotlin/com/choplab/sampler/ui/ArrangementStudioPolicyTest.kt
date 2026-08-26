package com.choplab.sampler.ui

import com.choplab.sampler.model.PatternArrangement
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stepKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrangementStudioPolicyTest {
    @Test
    fun beatDockExposesOneArrangementEntryWithoutReplacingExistingTools() {
        val intents = beatProductionDockItems(stepsVisible = true).map(ProductionDockItem::intent)

        assertEquals(
            listOf(
                ProductionDockIntent.SHOW_QUICK,
                ProductionDockIntent.SHOW_STEPS,
                ProductionDockIntent.OPEN_ARRANGE,
                ProductionDockIntent.OPEN_ADD,
                ProductionDockIntent.OPEN_SCRATCH,
            ),
            intents,
        )
    }

    @Test
    fun presentationNamesSelectedCopyDestinationSectionsAndPlaybackTruth() {
        val state = SamplerUiState(
            patternArrangement = PatternArrangement(
                selectedSlot = 1,
                songSections = listOf(0, 1, 1, 0),
                songModeEnabled = true,
            ),
        )

        val presentation = arrangementStudioPresentation(state)

        assertEquals("パターンBを編集中", presentation.selectedPatternLabel)
        assertEquals("BをAへコピー", presentation.copyLabel)
        assertEquals("A", presentation.copyDestinationLabel)
        assertEquals(listOf("1小節 A", "2小節 B", "3小節 B", "4小節 A"), presentation.sectionLabels)
        assertEquals("Song 4小節", presentation.playbackModeLabel)
        assertTrue(presentation.editEnabled)
        assertTrue("再生・WAV" in presentation.guidance)
    }

    @Test
    fun copyRequiresConfirmationOnlyWhenItWouldOverwriteDifferentWork() {
        val patternA = setOf(stepKey(0, 0))
        val patternB = setOf(stepKey(1, 4))

        val destructive = arrangementStudioPresentation(
            SamplerUiState(
                activeSteps = patternA,
                patternArrangement = PatternArrangement(storedStepsBySlot = listOf(emptySet(), patternB)),
            ),
        )
        val sameContent = arrangementStudioPresentation(
            SamplerUiState(
                activeSteps = patternA,
                patternArrangement = PatternArrangement(storedStepsBySlot = listOf(emptySet(), patternA)),
            ),
        )

        assertTrue(destructive.copyNeedsConfirmation)
        assertFalse(sameContent.copyNeedsConfirmation)
    }

    @Test
    fun playbackLocksArrangementEditsWithActionableGuidance() {
        val presentation = arrangementStudioPresentation(SamplerUiState(transportPlaying = true))

        assertFalse(presentation.editEnabled)
        assertTrue("停止" in presentation.guidance)
    }
}
