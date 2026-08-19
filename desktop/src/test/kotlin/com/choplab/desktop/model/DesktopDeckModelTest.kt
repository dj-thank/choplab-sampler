package com.choplab.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDeckModelTest {
    @Test
    fun originalDeckHasFourBanksOfThirtyTwoAndAVisibleSixteenPadPage() {
        val model = DesktopDeckModel()

        assertEquals(4, model.bankCount)
        assertEquals(32, model.padsPerBank)
        assertEquals(128, model.padCount)
        assertEquals(2, model.pageCount)
        assertEquals((0 until 16).toList(), model.visibleSlots().map { it.globalIndex })
    }

    @Test
    fun bankAndPageSelectionKeepsTheOriginalPadCoordinates() {
        val model = DesktopDeckModel()

        model.selectBank(2)
        model.selectPage(1)
        model.selectPad(2 * 32 + 16 + 3)

        assertEquals(2, model.selectedBank)
        assertEquals(1, model.selectedPage)
        assertEquals(2 * 32 + 16 + 3, model.selectedPad)
        assertEquals((2 * 32 + 16 until 2 * 32 + 32).toList(), model.visibleSlots().map { it.globalIndex })
        assertEquals(3, model.selectedPadSlot.indexInPage)
    }

    @Test
    fun localAssignmentAndClearAreObservableThroughTheDeckState() {
        val model = DesktopDeckModel()

        model.assignLocalFile(0, "C:/samples/kick.wav")
        model.selectPad(0)
        model.toggleStep(0, 0)
        model.toggleStep(0, 8)

        assertEquals("C:/samples/kick.wav", model.selectedPadSlot.localFile)
        assertEquals(setOf(0, 8), model.activeStepsForSelectedPad())
        assertTrue(model.assignedCountOnPage(0) == 1)

        model.clearAssignments()

        assertFalse(model.selectedPadSlot.isAssigned)
        assertEquals(emptySet(), model.activeStepsForSelectedPad())
    }

    @Test
    fun workflowAvailabilityFollowsTheGuidedSourceAndPadContext() {
        val model = DesktopDeckModel()

        assertTrue(model.canEnterStage(DesktopWorkflowStage.CAPTURE))
        assertFalse(model.canEnterStage(DesktopWorkflowStage.CHOP))
        assertFalse(model.canEnterStage(DesktopWorkflowStage.ARRANGE))

        model.setSourceFile("C:/samples/source.wav")
        assertTrue(model.canEnterStage(DesktopWorkflowStage.CHOP))
        assertTrue(model.canEnterStage(DesktopWorkflowStage.PERFORMANCE))
        assertFalse(model.canEnterStage(DesktopWorkflowStage.ARRANGE))

        model.assignLocalFile(0, "C:/samples/chop.wav")
        assertTrue(model.canEnterStage(DesktopWorkflowStage.ARRANGE))
        assertTrue(model.canEnterStage(DesktopWorkflowStage.FINISH))

        model.clearAssignments()
        assertFalse(model.canEnterStage(DesktopWorkflowStage.ARRANGE))
        assertFalse(model.canEnterStage(DesktopWorkflowStage.FINISH))
    }
}
