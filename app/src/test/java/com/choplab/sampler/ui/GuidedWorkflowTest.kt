package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.stepKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedWorkflowTest {
    @Test
    fun chopSwitchLabelsExposeBankPageAndCurrentPad() {
        assertEquals("● A メロディー\nMELODY", bankSwitchLabel(0, selected = true, compact = false))
        assertEquals("B\nDRUMS", bankSwitchLabel(1, selected = false, compact = true))
        assertEquals(
            "● PAD 01–16\n選択 A-04 / 3音",
            padPageSwitchLabel(
                pageIndex = 0,
                selected = true,
                assignedCount = 3,
                selectedPadLabel = "A-04",
            ),
        )
        assertEquals(
            "PAD 17–32\n切替 / 空",
            padPageSwitchLabel(
                pageIndex = 1,
                selected = false,
                assignedCount = 0,
                selectedPadLabel = "A-04",
            ),
        )
    }

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
    fun chopSessionUsesAudioThreadTruthForAllFourSourcePhases() {
        val ready = chopSessionPresentation(sourcePhase = SourceUiPhase.STOPPED, assignedPadCount = 0)
        assertFalse(ready.captureMode)
        assertTrue(ready.primaryEnabled)
        assertEquals(
            "チョップ開始\nSTART CHOP",
            ready.primaryActionLabel,
        )
        assertEquals(
            "「チョップ開始」→ 音が鳴ったら空PADを叩く",
            ready.guidance,
        )

        val starting = chopSessionPresentation(SourceUiPhase.STARTING, assignedPadCount = 0)
        assertFalse(starting.captureMode)
        assertTrue(starting.primaryEnabled)
        assertEquals("再生準備中\nTAP TO CANCEL", starting.primaryActionLabel)
        assertEquals("再生を準備中。音が鳴るまで空PADは選択のみ", starting.guidance)

        val firstCapture = chopSessionPresentation(SourceUiPhase.PLAYING, assignedPadCount = 0)
        assertTrue(firstCapture.captureMode)
        assertTrue(firstCapture.primaryEnabled)
        assertEquals("元曲を止める\nSTOP SOURCE", firstCapture.primaryActionLabel)
        assertEquals("空PADを叩くと、その瞬間からチョップ", firstCapture.guidance)

        val continuing = chopSessionPresentation(SourceUiPhase.PLAYING, assignedPadCount = 1)
        assertTrue(continuing.captureMode)
        assertEquals("空PAD＝追加／音ありPAD＝試聴・長押し微調整", continuing.guidance)

        val stopping = chopSessionPresentation(SourceUiPhase.STOPPING, assignedPadCount = 1)
        assertFalse(stopping.captureMode)
        assertFalse(stopping.primaryEnabled)
        assertEquals("停止中\nPLEASE WAIT", stopping.primaryActionLabel)
        assertEquals("停止処理中。割当済みPADは上書きされません", stopping.guidance)
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
    fun onlyTheChopWorkspaceKeepsSourcePlaybackActive() {
        assertFalse(workflowStageKeepsSourcePlayback(WorkflowStage.CAPTURE))
        assertTrue(workflowStageKeepsSourcePlayback(WorkflowStage.CHOP))
        assertFalse(workflowStageKeepsSourcePlayback(WorkflowStage.BEAT))
        assertFalse(workflowStageKeepsSourcePlayback(WorkflowStage.FINISH))
    }

    @Test
    fun stageTabsNavigateWithoutStartingSourceOrChangingTheSelectedBank() {
        assertEquals(
            emptySet<WorkflowNavigationAction>(),
            workflowNavigationActions(WorkflowStage.BEAT, WorkflowStage.CHOP),
        )
        assertEquals(
            setOf(
                WorkflowNavigationAction.STOP_SOURCE,
                WorkflowNavigationAction.ENSURE_PLAYABLE_PAD,
            ),
            workflowNavigationActions(WorkflowStage.CHOP, WorkflowStage.BEAT),
        )
        assertEquals(
            StartChopPolicy(
                enabled = true,
                prepareMelodyDestination = true,
                startSource = true,
            ),
            startChopPolicy(SourceUiPhase.STOPPED),
        )
        assertFalse(startChopPolicy(SourceUiPhase.STOPPING).enabled)
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
        assertEquals(
            "1 PAD  →  2 ループ／並べる  →  3 足す／擦る",
            ARRANGE_QUICK_GUIDANCE_COMPACT,
        )
    }

    @Test
    fun arrangeCoachExplainsWhyLoopAndVoiceDoNotUseStepCells() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val loop = PadModel(0, audio, 0, 100, playMode = PadPlayMode.LOOP)
        val vocal = PadModel(1, audio, 0, 100, contentKind = PadContentKind.VOCAL)
        val oneShot = PadModel(2, audio, 0, 100)

        assertEquals(
            "ループは音声全体を反復。配置は別PAD",
            arrangeQuickGuidance(loop, compact = false),
        )
        assertEquals(
            "VOICEは開始時に一度再生",
            arrangeQuickGuidance(vocal, compact = true),
        )
        assertEquals(ARRANGE_QUICK_GUIDANCE, arrangeQuickGuidance(oneShot, compact = false))
        assertEquals(ARRANGE_QUICK_GUIDANCE_COMPACT, arrangeQuickGuidance(oneShot, compact = true))
    }

    @Test
    fun largeTextKeepsTwoLineMachineActionsReadableWithoutChangingNormalTypography() {
        assertEquals(8f, compactMachineButtonFontSizeSp(fontScale = 1f))
        assertEquals(9f, compactMachineButtonLineHeightSp(fontScale = 1f))
        assertTrue(machineHeaderShowsCaption(fontScale = 1f))

        assertEquals(7f, compactMachineButtonFontSizeSp(fontScale = 1.3f))
        assertEquals(8f, compactMachineButtonLineHeightSp(fontScale = 1.3f))
        assertFalse(machineHeaderShowsCaption(fontScale = 1.3f))
    }

    @Test
    fun largeTextUsesOneClearLineForTheSelectedChopLoopAction() {
        assertEquals(
            "選択音をループ\nSTART",
            beatLoopButtonLabel(looping = false, fontScale = 1f),
        )
        assertEquals(
            "選択音ループ",
            beatLoopButtonLabel(looping = false, fontScale = 1.3f),
        )
        assertEquals(
            "ループ停止",
            beatLoopButtonLabel(looping = true, fontScale = 1.3f),
        )
    }

    @Test
    fun sourcePresentationNeverClaimsNoSourceWhileProjectRecoveryIsRunning() {
        assertEquals(
            "LOADING",
            captureSourceStatusLabel(
                sourcePhase = SourceUiPhase.STOPPED,
                isLoading = true,
                audioLoaded = false,
            ),
        )
        assertEquals("音声を読込中\nPLEASE WAIT", emptySourceWaveformLabel(isLoading = true))
        assertEquals("NO SOURCE", captureSourceStatusLabel(SourceUiPhase.STOPPED, false, false))
        assertEquals("READY", captureSourceStatusLabel(SourceUiPhase.STOPPED, false, true))
        assertEquals("STARTING", captureSourceStatusLabel(SourceUiPhase.STARTING, true, true))
        assertEquals("NO SOURCE\nLOAD OR RECORD AUDIO", emptySourceWaveformLabel(isLoading = false))
    }
}
