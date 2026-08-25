package com.choplab.sampler.ui

import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
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
        assertFalse(requiresNewProjectConfirmation(BuiltInDrumKits.installStarterKit(SamplerUiState())))
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
        assertEquals("空PAD＝追加／音ありPAD＝タップ上書き・長押し微調整", continuing.guidance)

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
    fun launchStageUsesTheResolvedProductionDestination() {
        assertEquals(
            WorkflowStage.CAPTURE,
            initialWorkflowStage(SamplerUiState(projectLaunchTarget = ProjectLaunchTarget.CAPTURE)),
        )
        assertEquals(
            WorkflowStage.CHOP,
            initialWorkflowStage(SamplerUiState(projectLaunchTarget = ProjectLaunchTarget.CHOP)),
        )
        assertEquals(
            WorkflowStage.BEAT,
            initialWorkflowStage(SamplerUiState(projectLaunchTarget = ProjectLaunchTarget.BEAT)),
        )
    }

    @Test
    fun beatDefaultsToTheChopPadSurfaceAndKeepsStepsSecondary() {
        val defaultSurface = beatWorkspaceSurface(showFineControls = false)
        val fineSurface = beatWorkspaceSurface(showFineControls = true)

        assertTrue(defaultSurface.showPadGrid)
        assertFalse(defaultSurface.showDetailedSequencer)
        assertFalse(fineSurface.showPadGrid)
        assertTrue(fineSurface.showDetailedSequencer)
    }

    @Test
    fun unavailableStagesStayDisabledUntilTheirRequiredMaterialExists() {
        val empty = SamplerUiState()
        assertTrue(workflowStageEnabled(WorkflowStage.CAPTURE, empty))
        assertFalse(workflowStageEnabled(WorkflowStage.CHOP, empty))
        assertFalse(workflowStageEnabled(WorkflowStage.BEAT, empty))
        assertFalse(workflowStageEnabled(WorkflowStage.FINISH, empty))

        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val loaded = empty.copy(currentAudio = audio)
        assertTrue(workflowStageEnabled(WorkflowStage.CHOP, loaded))
        assertFalse(workflowStageEnabled(WorkflowStage.BEAT, loaded))
        assertTrue(workflowStageEnabled(WorkflowStage.FINISH, loaded))

        val pads = loaded.pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 50)
        }
        assertTrue(workflowStageEnabled(WorkflowStage.BEAT, loaded.copy(pads = pads)))
        assertEquals(WorkflowStage.CAPTURE, reconcileWorkflowStage(WorkflowStage.BEAT, empty))
        assertEquals(WorkflowStage.CHOP, reconcileWorkflowStage(WorkflowStage.BEAT, loaded))
        assertEquals(
            WorkflowStage.BEAT,
            reconcileWorkflowStage(WorkflowStage.BEAT, loaded.copy(pads = pads)),
        )
    }

    @Test
    fun lockedStagesExplainTheExactPrerequisite() {
        val empty = SamplerUiState()

        assertEquals(
            WorkflowStageAvailability(
                enabled = false,
                blockedReason = "曲を読み込むか録音すると使えます",
            ),
            workflowStageAvailability(WorkflowStage.CHOP, empty),
        )
        assertEquals(
            "チョップでPADに音を入れると使えます",
            workflowStageAvailability(WorkflowStage.BEAT, empty).blockedReason,
        )
        assertEquals(
            "音源か音の入ったPADを用意すると使えます",
            workflowStageAvailability(WorkflowStage.FINISH, empty).blockedReason,
        )
        assertEquals(
            WorkflowStageAvailability(enabled = true, blockedReason = null),
            workflowStageAvailability(WorkflowStage.CAPTURE, empty),
        )
        assertEquals(
            "まだ使えません。曲を読み込むか録音すると使えます",
            workflowStageStateDescription(workflowStageAvailability(WorkflowStage.CHOP, empty)),
        )
    }

    @Test
    fun nextActionTracksTheSingleStepThatUnlocksProgress() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val empty = SamplerUiState()
        val loaded = empty.copy(currentAudio = audio)
        val pads = loaded.pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 50)
        }
        val playable = loaded.copy(pads = pads)
        val exportReady = playable.copy(activeSteps = setOf(stepKey(0, 0)))

        assertEquals(
            WorkflowNextActionPresentation(
                stage = WorkflowStage.CAPTURE,
                title = "NEXT 1 入れる",
                guidance = "曲を読み込むか録音します",
            ),
            workflowNextActionPresentation(empty),
        )
        assertEquals(
            WorkflowStage.CAPTURE,
            workflowNextActionPresentation(BuiltInDrumKits.installStarterKit(empty)).stage,
        )
        assertEquals(WorkflowStage.CHOP, workflowNextActionPresentation(loaded).stage)
        assertEquals(
            WorkflowStage.CHOP,
            workflowNextActionPresentation(BuiltInDrumKits.installStarterKit(loaded)).stage,
        )
        assertEquals("曲を流し、空PADを押します", workflowNextActionPresentation(loaded).guidance)
        assertEquals(WorkflowStage.BEAT, workflowNextActionPresentation(playable).stage)
        assertEquals(WorkflowStage.FINISH, workflowNextActionPresentation(exportReady).stage)
    }

    @Test
    fun loadingAndRecordingOwnTheNextActionInsteadOfCompetingWithNavigation() {
        assertEquals(
            WorkflowNextActionPresentation(
                stage = null,
                title = "NEXT 待つ",
                guidance = "音声の読込が終わるまで待ちます",
            ),
            workflowNextActionPresentation(SamplerUiState(isLoading = true)),
        )

        val recording = SamplerUiState(
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_MICROPHONE,
                phase = RecordingPhase.RECORDING,
            ),
        )
        assertEquals("NEXT 録音を止める", workflowNextActionPresentation(recording).title)
        assertEquals("上の停止ボタンで録音を保存します", workflowNextActionPresentation(recording).guidance)

        val stopping = recording.copy(
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_MICROPHONE,
                phase = RecordingPhase.STOPPING,
            ),
        )
        assertEquals("NEXT 待つ", workflowNextActionPresentation(stopping).title)
        assertEquals("録音の停止と保存が終わるまで待ちます", workflowNextActionPresentation(stopping).guidance)
    }

    @Test
    fun padOnlyAndLoopProjectsDoNotGetSentBackToCapture() {
        val audio = PcmAudio(1L, "pad.wav", ShortArray(100), 1_000)
        val pads = SamplerUiState().pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 50)
        }
        val padOnly = SamplerUiState(pads = pads)

        assertEquals(WorkflowStage.BEAT, workflowNextActionPresentation(padOnly).stage)
        assertEquals(
            WorkflowStage.FINISH,
            workflowNextActionPresentation(
                padOnly.copy(pads = pads.toMutableList().also { it[0] = it[0].copy(playMode = PadPlayMode.LOOP) }),
            ).stage,
        )
    }

    @Test
    fun everyNextActionFitsTheBoundedStatusCopyBudget() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val pads = SamplerUiState().pads.toMutableList().also {
            it[0] = PadModel(0, audio, 0, 50)
        }
        val states = listOf(
            SamplerUiState(),
            SamplerUiState(isLoading = true),
            SamplerUiState(currentAudio = audio),
            SamplerUiState(currentAudio = audio, pads = pads),
            SamplerUiState(currentAudio = audio, pads = pads, activeSteps = setOf(stepKey(0, 0))),
        )

        states.map(::workflowNextActionPresentation).forEach { presentation ->
            assertTrue(presentation.title.length <= 12)
            assertTrue(presentation.guidance.length <= 26)
        }
    }

    @Test
    fun pristineStarterGetsAFocusedOwnAudioEntryAndAnExplicitDemoRoute() {
        val starter = BuiltInDrumKits.installStarterKit(SamplerUiState())

        val presentation = captureEntryPresentation(starter)

        assertTrue(presentation.focused)
        assertTrue(presentation.starterDemoAvailable)
        assertEquals("まず、自分の音を入れる", presentation.title)
        assertEquals("曲を読み込むか、前の制作を開きます", presentation.guidance)
    }

    @Test
    fun loadedOrRecordingCaptureKeepsTheExistingSourceSafetyWorkspace() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val loaded = captureEntryPresentation(SamplerUiState(currentAudio = audio))
        val recording = captureEntryPresentation(
            SamplerUiState(
                recordingSession = RecordingSession.Active(
                    kind = RecordingKind.SOURCE_MICROPHONE,
                    phase = RecordingPhase.RECORDING,
                ),
            ),
        )

        assertFalse(loaded.focused)
        assertFalse(recording.focused)
    }

    @Test
    fun saveCopySeparatesProjectSafetyFromWavReadiness() {
        assertEquals(
            FinishReadinessPresentation(
                title = "制作は保存できます",
                guidance = "WAVはまだ準備中です。『ビート』で鳴らすマスを光らせてください。",
            ),
            finishReadinessPresentation(readyForWav = false),
        )
        assertEquals(
            "制作を保存・書き出し",
            finishReadinessPresentation(readyForWav = true).title,
        )
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

        assertEquals(8f, compactMachineButtonFontSizeSp(fontScale = 1.3f))
        assertEquals(9f, compactMachineButtonLineHeightSp(fontScale = 1.3f))
        assertEquals(8f, compactMachineButtonFontSizeSp(fontScale = 2f))
        assertEquals(9f, compactMachineButtonLineHeightSp(fontScale = 2f))
        assertFalse(machineHeaderShowsCaption(fontScale = 1.3f))
        assertTrue(machineHeaderShowsBankStatus(fontScale = 1f))
        assertFalse(machineHeaderShowsBankStatus(fontScale = 1.3f))
        assertEquals(1, workflowStageRows(fontScale = 1f))
        assertEquals(2, workflowStageRows(fontScale = 1.3f))
        assertEquals(2, workflowStageRows(fontScale = 2f))
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

    @Test
    fun allCaptureEntrancesAreDisabledDuringProjectRecovery() {
        val loading = captureInputPolicy(SamplerUiState(isLoading = true))
        assertFalse(loading.fileEnabled)
        assertFalse(loading.microphoneEnabled)
        assertFalse(loading.systemAudioEnabled)

        val idle = captureInputPolicy(SamplerUiState())
        assertTrue(idle.fileEnabled)
        assertTrue(idle.microphoneEnabled)
        assertTrue(idle.systemAudioEnabled)

        val microphoneRecording = captureInputPolicy(
            SamplerUiState(
                recordingSession = RecordingSession.Active(
                    RecordingKind.SOURCE_MICROPHONE,
                    RecordingPhase.RECORDING,
                ),
            ),
        )
        assertFalse(microphoneRecording.fileEnabled)
        assertTrue(microphoneRecording.microphoneEnabled)
        assertFalse(microphoneRecording.systemAudioEnabled)

        val loadingWhileMicrophoneRecording = captureInputPolicy(
            SamplerUiState(
                isLoading = true,
                recordingSession = RecordingSession.Active(
                    RecordingKind.SOURCE_MICROPHONE,
                    RecordingPhase.RECORDING,
                ),
            ),
        )
        assertTrue(loadingWhileMicrophoneRecording.microphoneEnabled)

        val loadingWhileSystemAudioRecording = captureInputPolicy(
            SamplerUiState(
                isLoading = true,
                recordingSession = RecordingSession.Active(
                    RecordingKind.SOURCE_SYSTEM_AUDIO,
                    RecordingPhase.RECORDING,
                ),
            ),
        )
        assertTrue(loadingWhileSystemAudioRecording.systemAudioEnabled)
    }

    @Test
    fun recordingControlsExposeOneOwnerAndDisableEveryCompetingEntrance() {
        val microphoneStarting = RecordingSession.Active(
            RecordingKind.SOURCE_MICROPHONE,
            RecordingPhase.STARTING,
        )
        val microphoneControl = recordingControlPresentation(
            session = microphoneStarting,
            kind = RecordingKind.SOURCE_MICROPHONE,
            idleLabel = "マイク録音\nMIC REC",
            stopLabel = "録音を止める\nMIC STOP",
        )
        val competingSystemControl = recordingControlPresentation(
            session = microphoneStarting,
            kind = RecordingKind.SOURCE_SYSTEM_AUDIO,
            idleLabel = "端末を録音\nDEVICE REC",
            stopLabel = "録音を止める\nDEVICE STOP",
        )

        assertEquals("録音を止める\nMIC STOP", microphoneControl.label)
        assertTrue(microphoneControl.enabled)
        assertTrue(microphoneControl.active)
        assertEquals("別の録音中\nWAIT", competingSystemControl.label)
        assertFalse(competingSystemControl.enabled)
        assertFalse(competingSystemControl.active)

        val stopping = recordingControlPresentation(
            session = microphoneStarting.copy(phase = RecordingPhase.STOPPING),
            kind = RecordingKind.SOURCE_MICROPHONE,
            idleLabel = "マイク録音\nMIC REC",
            stopLabel = "録音を止める\nMIC STOP",
        )
        assertEquals("停止・保存中\nPLEASE WAIT", stopping.label)
        assertFalse(stopping.enabled)
        assertTrue(stopping.active)
    }

    @Test
    fun machineHeaderAlwaysNamesAndStopsTheActiveRecordingSession() {
        val microphone = recordingHeaderPresentation(
            RecordingSession.Active(RecordingKind.SOURCE_MICROPHONE, RecordingPhase.RECORDING),
        )
        assertEquals("MIC  REC", microphone?.statusLabel)
        assertEquals("録音を停止\nMIC STOP", microphone?.stopLabel)
        assertTrue(microphone?.stopEnabled == true)
        assertEquals("マイク素材を録音中", microphone?.accessibilityLabel)

        val vocalStopping = recordingHeaderPresentation(
            RecordingSession.Active(RecordingKind.VOCAL_OVERDUB, RecordingPhase.STOPPING),
        )
        assertEquals("VOICE  STOPPING", vocalStopping?.statusLabel)
        assertEquals("停止・保存中\nPLEASE WAIT", vocalStopping?.stopLabel)
        assertFalse(vocalStopping?.stopEnabled == true)

        assertEquals(null, recordingHeaderPresentation(RecordingSession.Idle))
    }

    @Test
    fun externalPickersStayClosedUntilRecordingAndSavingHaveFinished() {
        assertTrue(externalDocumentActionsEnabled(SamplerUiState()))
        assertFalse(externalDocumentActionsEnabled(SamplerUiState(isLoading = true)))
        assertFalse(
            externalDocumentActionsEnabled(
                SamplerUiState(
                    recordingSession = RecordingSession.Active(
                        RecordingKind.SOURCE_MICROPHONE,
                        RecordingPhase.RECORDING,
                    ),
                ),
            ),
        )
        assertFalse(
            externalDocumentActionsEnabled(
                SamplerUiState(
                    recordingSession = RecordingSession.Active(
                        RecordingKind.VOCAL_OVERDUB,
                        RecordingPhase.STOPPING,
                    ),
                ),
            ),
        )
    }
}
