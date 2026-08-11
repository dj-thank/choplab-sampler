package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stepKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedWorkflowTest {
    @Test
    fun newSourceRequiresConfirmationOnlyWhenTheCurrentProjectHasMaterialWork() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val assignedPads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) PadModel(index, audio, 0, 50) else PadModel(index)
        }

        assertFalse(requiresNewProjectConfirmation(SamplerUiState()))
        assertTrue(requiresNewProjectConfirmation(SamplerUiState(currentAudio = audio)))
        assertTrue(requiresNewProjectConfirmation(SamplerUiState(pads = assignedPads)))
        assertTrue(requiresNewProjectConfirmation(SamplerUiState(activeSteps = setOf(stepKey(0, 0)))))
    }

    @Test
    fun chopCoachExplainsWaveformCaptureAuditionAndTrimAtTheRightMoment() {
        assertEquals(
            "波形タップ＝そこから再生 → 空PAD＝チョップ",
            chopQuickGuidance(assignedPadCount = 0),
        )
        assertEquals(
            "PAD＝試聴／長押し＝微調整 → ビートへ",
            chopQuickGuidance(assignedPadCount = 1),
        )
    }

    @Test
    fun stagesFollowTheBeginnerProductionJourney() {
        assertEquals(
            listOf("入れる", "チョップ", "ビート", "保存"),
            WorkflowStage.entries.map(WorkflowStage::label),
        )
        assertEquals(
            listOf("CAPTURE", "CHOP", "BEAT", "SAVE"),
            WorkflowStage.entries.map(WorkflowStage::caption),
        )
    }

    @Test
    fun restoredStageFallsBackInsteadOfCrashing() {
        assertEquals(WorkflowStage.CHOP, restoreWorkflowStage("PLAY"))
        assertEquals(WorkflowStage.CHOP, restoreWorkflowStage("not-a-stage"))
        assertEquals(WorkflowStage.BEAT, restoreWorkflowStage("ARRANGE"))
    }

    @Test
    fun newProjectsStartAtCaptureAndLoadedProjectsStartAtPlay() {
        assertEquals(WorkflowStage.CAPTURE, initialWorkflowStage(hasAudio = false))
        assertEquals(WorkflowStage.CHOP, initialWorkflowStage(hasAudio = true))
    }

    @Test
    fun everyStageHasShortBeginnerGuidanceAndLinearNextStep() {
        WorkflowStage.entries.forEach { stage ->
            assertTrue(stage.guidance.isNotBlank())
            assertTrue(stage.guidance.length <= 34)
        }
        assertEquals(WorkflowStage.CHOP, WorkflowStage.CAPTURE.next())
        assertEquals(WorkflowStage.FINISH, WorkflowStage.BEAT.next())
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
    fun arrangeBeatLoopPromptNamesWholeChopPlaybackInsteadOfBeatGridRetriggering() {
        assertEquals(
            "2 選択音をループ  A-01の音声全体を繰り返す",
            arrangeBeatLoopPrompt(isAssigned = true, padLabel = "A-01"),
        )
        assertEquals(
            "2 選択音をループ  先に音の入ったPADを選んでください",
            arrangeBeatLoopPrompt(isAssigned = false, padLabel = "A-01"),
        )
        assertEquals(
            "2 選択音をループ  A-04の音声全体を繰り返し中",
            arrangeBeatLoopPrompt(
                isAssigned = false,
                padLabel = "B-04",
                loopingPadLabel = "A-04",
            ),
        )
        assertEquals("配置プリセット  鳴らす場所を選ぶ", placementPresetPrompt())
    }

    @Test
    fun arrangeQuickModeNamesTheThreePrimaryActions() {
        assertEquals(
            listOf("1 音ありPADを選ぶ", "2 ループ／並べる", "3 足す／スクラッチ"),
            arrangeQuickSteps(),
        )
        assertEquals(
            "1 音ありPADを選ぶ  →  2 ループ／並べる  →  3 足す／スクラッチ",
            ARRANGE_QUICK_GUIDANCE,
        )
    }
}
