package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedWorkflowTest {
    @Test
    fun stagesFollowTheBeginnerProductionJourney() {
        assertEquals(
            listOf("入れる", "切る", "叩く", "並べる", "完成"),
            WorkflowStage.entries.map(WorkflowStage::label),
        )
        assertEquals(
            listOf("CAPTURE", "SLICE", "PLAY", "ARRANGE", "FINISH"),
            WorkflowStage.entries.map(WorkflowStage::caption),
        )
    }

    @Test
    fun restoredStageFallsBackInsteadOfCrashing() {
        assertEquals(WorkflowStage.PLAY, restoreWorkflowStage("CHOP"))
        assertEquals(WorkflowStage.PLAY, restoreWorkflowStage("not-a-stage"))
        assertEquals(WorkflowStage.ARRANGE, restoreWorkflowStage("ARRANGE"))
    }

    @Test
    fun newProjectsStartAtCaptureAndLoadedProjectsStartAtPlay() {
        assertEquals(WorkflowStage.CAPTURE, initialWorkflowStage(hasAudio = false))
        assertEquals(WorkflowStage.PLAY, initialWorkflowStage(hasAudio = true))
    }

    @Test
    fun everyStageHasShortBeginnerGuidanceAndLinearNextStep() {
        WorkflowStage.entries.forEach { stage ->
            assertTrue(stage.guidance.isNotBlank())
            assertTrue(stage.guidance.length <= 34)
        }
        assertEquals(WorkflowStage.SLICE, WorkflowStage.CAPTURE.next())
        assertEquals(WorkflowStage.FINISH, WorkflowStage.ARRANGE.next())
        assertEquals(null, WorkflowStage.FINISH.next())
    }

    @Test
    fun selectedPadPitchIsShownAsAFamiliarKeyName() {
        assertEquals("C3", semitoneToKeyName(0f))
        assertEquals("C#3", semitoneToKeyName(1f))
        assertEquals("B2", semitoneToKeyName(-1f))
        assertEquals("C5", semitoneToKeyName(24f))
    }
}
