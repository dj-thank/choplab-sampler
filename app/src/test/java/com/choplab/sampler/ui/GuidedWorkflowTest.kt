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
    fun selectedPadPitchExplainsDirectionWithoutClaimingSourceKeyDetection() {
        assertEquals("低い", pitchDirectionLabel(-1f))
        assertEquals("原キー", pitchDirectionLabel(0f))
        assertEquals("高い", pitchDirectionLabel(1f))
    }

    @Test
    fun tonePresetUsesBeginnerNamesAndCyclesPredictably() {
        assertEquals("暗い", toneCharacterLabel(0.2f))
        assertEquals("なじむ", toneCharacterLabel(0.6f))
        assertEquals("原音", toneCharacterLabel(1f))
        assertEquals(0.65f, nextTonePreset(0.35f))
        assertEquals(1f, nextTonePreset(0.65f))
        assertEquals(0.35f, nextTonePreset(1f))
        assertEquals(1f, nextLevelPreset(0.9f))
        assertEquals(0f, nextLevelPreset(1.5f))
    }

    @Test
    fun arrangeRepeatPromptMakesTheSecondStepExplicit() {
        assertEquals(
            "2 反復を選ぶ  A-01を何拍ごとに鳴らす？",
            arrangeRepeatPrompt(isAssigned = true, padLabel = "A-01"),
        )
        assertEquals(
            "2 反復を選ぶ  先に音の入ったPADを選んでください",
            arrangeRepeatPrompt(isAssigned = false, padLabel = "A-01"),
        )
    }

    @Test
    fun arrangeQuickModeNamesTheThreePrimaryActions() {
        assertEquals(
            listOf("1 PADを選ぶ", "2 反復を選ぶ", "3 ビートを聴く"),
            arrangeQuickSteps(),
        )
    }
}
