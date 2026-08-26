package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PatternArrangement
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.selectedPadPage
import com.choplab.sampler.model.materializedPatternArrangement
import com.choplab.sampler.persistence.AtomicProjectStore
import java.nio.file.Files
import java.io.File
import com.choplab.sampler.ui.WorkflowStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSamplerControllerTest {
    private fun controller(): DesktopSamplerController =
        DesktopSamplerController(JavaSoundWavPlayer(), autosaveStore = null)

    @Test
    fun sharedWorkflowUsesTheCurrentFourAndroidStages() {
        assertEquals(listOf("入れる", "チョップ", "ビート", "保存"), WorkflowStage.entries.map { it.label })
        assertEquals(listOf("CAPTURE", "CHOP", "BEAT", "SAVE"), WorkflowStage.entries.map { it.caption })
    }

    @Test
    fun controllerUsesSharedTempoAndSelectionState() {
        val controller = controller()
        try {
            controller.setBpm(140f)
            controller.setSwing(61f)
            controller.selectBank(2)
            controller.selectPadPage(1)

            assertEquals(140f, controller.state.value.bpm)
            assertEquals(61f, controller.state.value.swing)
            assertEquals(2, controller.state.value.selectedBank)
            assertEquals(2 * SamplerConfig.PADS_PER_BANK + SamplerConfig.PAD_PAGE_SIZE, controller.state.value.selectedPad)
        } finally {
            controller.close()
        }
    }

    @Test
    fun builtInDrumKitUsesTheSharedAndroidCatalog() {
        val controller = controller()
        try {
            val firstDrum = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            val originalId = controller.state.value.pads[firstDrum].audio?.id
            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = false)
            assertEquals(originalId, controller.state.value.pads[firstDrum].audio?.id)
            assertEquals(
                "BANK B ドラムには音があります。確認操作なしでは上書きしません",
                controller.state.value.statusMessage,
            )

            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = true)
            val drumPads = controller.state.value.pads.subList(
                firstDrum,
                firstDrum + SamplerConfig.DRUM_KIT_PAD_COUNT,
            )
            assertNotEquals(originalId, drumPads.first().audio?.id)
            assertTrue(drumPads.all { it.isAssigned })
            assertTrue(drumPads.all { it.contentKind.name == "DRUM" })
            assertEquals("boom-bap", controller.state.value.selectedDrumKitId)
            assertTrue(controller.state.value.activeSteps.isNotEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun resetReturnsToTheSharedEmptySourceState() {
        val controller = controller()
        try {
            controller.setBpm(120f)
            controller.resetProject()
            assertEquals(null, controller.state.value.currentAudio)
            assertEquals(
                "新しい制作を準備しました — BANK BにDUSTY JAZZをセット済み",
                controller.state.value.statusMessage,
            )
            val drumStart = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            assertTrue(
                controller.state.value.pads
                    .subList(drumStart, drumStart + SamplerConfig.DRUM_KIT_PAD_COUNT)
                    .all(PadModel::isAssigned),
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun beatEntryPlayableSelectionKeepsPadBankAndPageCoherent() {
        val controller = controller()
        try {
            controller.resetProject()
            controller.selectBank(0)
            controller.selectPadPage(1)

            controller.ensurePlayablePadSelected()

            val state = controller.state.value
            val firstDrum = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            assertEquals(firstDrum, state.selectedPad)
            assertEquals(SamplerConfig.DRUM_BANK_INDEX, state.selectedBank)
            assertEquals(0, state.selectedPadPage())
            assertEquals("B-01をビートの操作対象にしました", state.statusMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun windowsRendererWritesTheFourBarWavUsingSharedPadState() {
        val output = Files.createTempFile("choplab-render", ".wav").toFile()
        try {
            val samples = ShortArray(480) { index -> if (index % 32 < 16) 12_000 else -12_000 }
            val audio = PcmAudio(name = "test", samples = samples, sampleRate = 48_000)
            val pads = List(SamplerConfig.PAD_COUNT) { index ->
                if (index == 0) PadModel(index, audio, 0, samples.size) else PadModel(index)
            }

            PatternRenderer.renderToWav(output, pads, setOf(stepKey(0, 0)), 92f, 54f)

            assertTrue(output.length() > 44L)
            assertEquals('R'.code.toByte(), output.inputStream().use { it.read().toByte() })
        } finally {
            output.delete()
        }
    }

    @Test
    fun projectSaveAndOpenReplaceStateOnlyAfterAValidSharedArchive() {
        val directory = Files.createTempDirectory("choplab-controller-project").toFile()
        val project = directory.resolve("session.choplab")
        val controller = controller()
        try {
            controller.setBpm(133f)
            controller.setSwing(64f)
            controller.saveProject(project)
            awaitCondition { project.isFile && !controller.state.value.isLoading }
            assertEquals(
                "session.choplab に制作を保存しました。アプリ内の安全コピーも保持しています",
                controller.state.value.statusMessage,
            )
            controller.setBpm(80f)

            controller.openProject(project)
            awaitCondition { controller.state.value.statusMessage == "session.choplabを開きました" }

            assertEquals(133f, controller.state.value.bpm)
            assertEquals(64f, controller.state.value.swing)
            assertEquals("session.choplabを開きました", controller.state.value.statusMessage)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun arrangementCommandsShareHistoryAndSurviveProjectRoundTrip() {
        val directory = Files.createTempDirectory("choplab-controller-arrangement").toFile()
        val project = directory.resolve("ab-song.choplab")
        val controller = controller()
        try {
            controller.ensurePlayablePadSelected()
            val patternA = controller.state.value.activeSteps
            controller.duplicateSelectedPatternToOther()
            controller.toggleStep(15)
            val patternB = controller.state.value.activeSteps
            assertNotEquals(patternA, patternB)

            controller.toggleSongSectionPattern(1)
            assertEquals(listOf(0, 1, 0, 0), controller.state.value.patternArrangement.songSections)
            controller.undoEdit()
            assertEquals(listOf(0, 0, 0, 0), controller.state.value.patternArrangement.songSections)
            controller.redoEdit()
            controller.toggleSongSectionPattern(3)
            controller.toggleSongMode()
            val expected = controller.state.value.materializedPatternArrangement()

            controller.saveProject(project)
            awaitCondition { project.isFile && !controller.state.value.isLoading }
            controller.selectPatternVariation(0)
            controller.toggleSongMode()

            controller.openProject(project)
            awaitCondition { controller.state.value.statusMessage == "ab-song.choplabを開きました" }

            val restored = controller.state.value
            assertEquals(expected, restored.patternArrangement)
            assertEquals(expected.storedStepsBySlot[expected.selectedSlot], restored.activeSteps)
            assertEquals(listOf(patternA, patternB), restored.patternArrangement.storedStepsBySlot)
            assertTrue(restored.patternArrangement.songModeEnabled)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun songModeDesktopExportUsesTheSavedABABOrder() {
        val directory = Files.createTempDirectory("choplab-controller-song-export").toFile()
        val output = directory.resolve("ab-song.wav")
        val store = AtomicProjectStore(directory.resolve("autosave"))
        val positive = PcmAudio(name = "pattern-a.wav", samples = ShortArray(256) { 12_000 }, sampleRate = 8_000)
        val negative = PcmAudio(name = "pattern-b.wav", samples = ShortArray(256) { -12_000 }, sampleRate = 8_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(index, positive, 0, positive.frameCount)
                1 -> PadModel(index, negative, 0, negative.frameCount)
                else -> PadModel(index)
            }
        }
        val patternA = setOf(stepKey(0, 0))
        val patternB = setOf(stepKey(1, 0))
        store.save(
            SamplerUiState(
                pads = pads,
                activeSteps = patternA,
                patternArrangement = PatternArrangement(
                    storedStepsBySlot = listOf(patternA, patternB),
                    songSections = listOf(0, 1, 0, 1),
                    songModeEnabled = true,
                ),
                bpm = 120f,
                swing = 50f,
            ),
        )
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
        )
        try {
            awaitCondition { !controller.state.value.isLoading }

            controller.exportBeat(output)
            awaitCondition { output.isFile && !controller.state.value.isLoading }

            val bytes = output.readBytes()
            val pcm = ShortArray((bytes.size - 44) / Short.SIZE_BYTES) { index ->
                java.nio.ByteBuffer.wrap(bytes, 44 + index * Short.SIZE_BYTES, Short.SIZE_BYTES)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .short
            }
            val barFrames = pcm.size / 4
            assertTrue(pcm[64] > 0)
            assertTrue(pcm[barFrames + 64] < 0)
            assertTrue(pcm[barFrames * 2 + 64] > 0)
            assertTrue(pcm[barFrames * 3 + 64] < 0)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedProjectKeepsTheCurrentProductionState() {
        val malformed = Files.createTempFile("choplab-controller-malformed", ".choplab").toFile()
        val controller = controller()
        try {
            malformed.writeText("invalid", Charsets.UTF_8)
            controller.setBpm(141f)

            controller.openProject(malformed)
            awaitCondition { controller.state.value.statusMessage.startsWith("制作読込失敗:") }

            assertEquals(141f, controller.state.value.bpm)
            assertTrue(controller.state.value.statusMessage.startsWith("制作読込失敗:"))
        } finally {
            controller.close()
            malformed.delete()
        }
    }

    @Test
    fun undoAndRedoUseTheSharedBoundedEditHistory() {
        val controller = controller()
        try {
            controller.setBpm(126f)
            assertTrue(controller.state.value.canUndo)

            controller.undoEdit()
            assertEquals(92f, controller.state.value.bpm)
            assertTrue(controller.state.value.canRedo)

            controller.redoEdit()
            assertEquals(126f, controller.state.value.bpm)
            assertTrue(controller.state.value.canUndo)
        } finally {
            controller.close()
        }
    }

    @Test
    fun sliceSelectionIsSessionOnlyAndDoesNotPolluteUndoHistory() {
        val directory = Files.createTempDirectory("choplab-session-selection").toFile()
        val store = AtomicProjectStore(directory)
        val audio = PcmAudio(
            name = "selection.wav",
            samples = ShortArray(2_000) { index -> if (index < 1_000) -2_000 else 2_000 },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                sliceMarkers = listOf(1_000),
            ),
        )
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
        )
        try {
            awaitCondition { !controller.state.value.isLoading }
            assertFalse(controller.state.value.canUndo)

            controller.selectSliceAt(1_200)

            assertEquals(1, controller.state.value.activeSliceIndex)
            assertFalse(controller.state.value.canUndo)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourceBoundaryUsesTheSameZeroCrossingAndMinimumPolicyOnDesktop() {
        val directory = Files.createTempDirectory("choplab-desktop-boundary").toFile()
        val store = AtomicProjectStore(directory)
        val audio = PcmAudio(
            name = "boundary.wav",
            samples = ShortArray(2_000) { index -> if (index < 500) -2_000 else 2_000 },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                sliceMarkers = listOf(300, 1_000),
            ),
        )
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
        )
        try {
            awaitCondition { !controller.state.value.isLoading }

            controller.setRangeStart(520)

            assertEquals(500, controller.state.value.rangeStartFrame)
            assertEquals(listOf(1_000), controller.state.value.sliceMarkers)
            assertTrue(controller.state.value.canUndo)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun padPerformanceModeUsesSharedGatePolicyAndStopsLoopOwnership() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            assertEquals(loopPad, controller.state.value.loopingPadIndex)

            controller.toggleSelectedPadPlayMode()

            assertEquals(PadPlayMode.GATE, controller.state.value.pads[loopPad].playMode)
            assertEquals(null, controller.state.value.loopingPadIndex)
            assertTrue(loopPad in engine.stoppedPads)

            controller.toggleSelectedPadPlayMode()
            assertEquals(PadPlayMode.ONE_SHOT, controller.state.value.pads[loopPad].playMode)
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedLoopStopDoesNotPublishTheRequestedModeChange() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            engine.failNextStopPad = true

            controller.toggleSelectedPadPlayMode()

            assertEquals(PadPlayMode.LOOP, controller.state.value.pads[loopPad].playMode)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(controller.state.value.statusMessage.startsWith("PADを停止できないため編集を適用しませんでした:"))
            controller.undoEdit()
            assertEquals(PadPlayMode.ONE_SHOT, controller.state.value.pads[loopPad].playMode)
        } finally {
            controller.close()
        }
    }

    @Test
    fun editsFlowIntoTheSharedThreeGenerationAutosaveStore() {
        val directory = Files.createTempDirectory("choplab-controller-autosave").toFile()
        val store = AtomicProjectStore(directory)
        val controller = DesktopSamplerController(
            JavaSoundWavPlayer(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
        )
        try {
            awaitCondition { !controller.state.value.isLoading }
            controller.setBpm(138f)

            awaitCondition { runCatching { store.load()?.bpm == 138f }.getOrDefault(false) }
            assertEquals(138f, store.load()?.bpm)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourceRecordingRoutesTheProductionToChopAfterDecode() {
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = recorder,
            autosaveStore = null,
        )
        try {
            val launchRevision = controller.state.value.projectLaunchRevision

            controller.toggleMicrophoneRecording()
            assertTrue(controller.state.value.recordingSession is RecordingSession.Active)
            controller.toggleMicrophoneRecording()
            awaitCondition { controller.state.value.currentAudio != null }

            assertEquals(ProjectLaunchTarget.CHOP, controller.state.value.projectLaunchTarget)
            assertTrue(controller.state.value.projectLaunchRevision > launchRevision)
        } finally {
            controller.close()
        }
    }

    @Test
    fun vocalRecordingRestartsAndKeepsTheSelectedBeatLoopActive() {
        val engine = FakeAudioEngine()
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(
            engine,
            microphone = recorder,
            autosaveStore = null,
        )
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            val triggersBeforeRecording = engine.triggered.size

            controller.toggleVocalRecording()

            assertTrue(controller.state.value.recordingSession is RecordingSession.Active)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(engine.triggered.size > triggersBeforeRecording)
            assertEquals(loopPad, engine.triggered.last().first.globalIndex)
            assertEquals(true, engine.triggered.last().second)
        } finally {
            controller.close()
        }
    }

    @Test
    fun selectedVocalLoopIsTriggeredOnceWhileOtherVocalTakesRemainCompanions() {
        val directory = Files.createTempDirectory("choplab-vocal-loop-owner").toFile()
        val store = AtomicProjectStore(directory)
        val engine = FakeAudioEngine()
        val audio = PcmAudio(
            name = "voice.wav",
            samples = ShortArray(2_000) { 8_000 },
            sampleRate = 48_000,
        )
        val loopPadIndex = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val companionPadIndex = loopPadIndex + 1
        store.save(
            SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                selectedBank = SamplerConfig.VOCAL_BANK_INDEX,
                selectedPad = loopPadIndex,
                pads = List(SamplerConfig.PAD_COUNT) { index ->
                    when (index) {
                        loopPadIndex, companionPadIndex -> PadModel(
                            globalIndex = index,
                            audio = audio,
                            startFrame = 0,
                            endFrame = audio.frameCount,
                            contentKind = PadContentKind.VOCAL,
                        )
                        else -> PadModel(index)
                    }
                },
            ),
        )
        val controller = DesktopSamplerController(engine, autosaveStore = store, autosaveDelayMillis = 0L)
        try {
            awaitCondition { !controller.state.value.isLoading }
            engine.triggered.clear()

            controller.toggleBeatLoopControl()

            assertEquals(1, engine.triggered.count { it.first.globalIndex == loopPadIndex })
            assertEquals(true, engine.triggered.single { it.first.globalIndex == loopPadIndex }.second)
            assertEquals(1, engine.triggered.count { it.first.globalIndex == companionPadIndex })
            assertEquals(false, engine.triggered.single { it.first.globalIndex == companionPadIndex }.second)

            controller.toggleBeatLoopControl()

            assertTrue(loopPadIndex in engine.stoppedPads)
            assertTrue(companionPadIndex in engine.stoppedPads)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupProjectPolicySkipsRecoveryWithoutDisablingFutureAutosave() {
        val directory = Files.createTempDirectory("choplab-startup-autosave").toFile()
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 117f))
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        try {
            assertEquals(92f, controller.state.value.bpm)
            assertEquals(false, controller.state.value.isLoading)

            controller.setBpm(139f)

            awaitCondition { runCatching { store.load()?.bpm == 139f }.getOrDefault(false) }
            assertEquals(139f, store.load()?.bpm)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun vocalRecordingStopsSafelyWhenTheBeatOutputCannotStart() {
        val engine = FakeAudioEngine()
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(
            engine,
            microphone = recorder,
            autosaveStore = null,
        )
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            engine.failNextTrigger = true
            recorder.failStop = true

            val startResult = runCatching { controller.toggleVocalRecording() }

            assertTrue(startResult.isSuccess)
            awaitCondition { controller.state.value.recordingSession !is RecordingSession.Active }
            assertEquals(false, recorder.isRecording)
            assertEquals(null, controller.state.value.loopingPadIndex)
            assertTrue(controller.state.value.statusMessage.contains("Windowsの出力デバイスを確認"))
            assertEquals(false, recorder.lastOutput?.exists())
        } finally {
            controller.close()
        }
    }

    @Test
    fun padControlsAndLoopCommandsReachTheDesktopAudioPort() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = false)
            controller.selectPad(SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK)
            controller.setSelectedPadPitch(7f)
            controller.setSelectedPadTone(0.4f)
            controller.setSelectedPadGain(1.2f)
            controller.toggleSelectedPadReverse()

            controller.triggerPad(controller.state.value.selectedPad)
            val hit = engine.triggered.last()
            assertEquals(7f, hit.first.pitchSemitones)
            assertEquals(0.4f, hit.first.tone)
            assertEquals(1.2f, hit.first.gain)
            assertTrue(hit.first.reverse)
            assertEquals(false, hit.second)

            controller.toggleBeatLoopControl()
            assertEquals(PadPlayMode.LOOP, engine.triggered.last().first.playMode)
            assertEquals(true, engine.triggered.last().second)
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedActiveLoopEditKeepsTheOldPadLoopAndHistoryFrontier() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            val before = controller.state.value
            val triggerCount = engine.triggered.size
            engine.stoppedPads.clear()
            engine.failNextTrigger = true

            val edit = runCatching { controller.setSelectedPadPitch(7f) }

            assertTrue(edit.isSuccess)
            assertEquals(before.pads[loopPad].pitchSemitones, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCount, engine.triggered.size)
            assertTrue(engine.stoppedPads.isEmpty())
            assertTrue(
                controller.state.value.statusMessage.startsWith(
                    "ループ音を更新できないため編集を適用しませんでした:",
                ),
            )

            controller.undoEdit()
            assertEquals(PadPlayMode.ONE_SHOT, controller.state.value.pads[loopPad].playMode)
            assertEquals(0f, controller.state.value.pads[loopPad].pitchSemitones)
        } finally {
            controller.close()
        }
    }

    @Test
    fun successfulActiveLoopEditStartsTheCandidateThenCommitsExactlyOneEdit() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            val triggerCount = engine.triggered.size
            engine.stoppedPads.clear()

            controller.setSelectedPadPitch(7f)

            assertEquals(7f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCount + 1, engine.triggered.size)
            assertEquals(7f, engine.triggered.last().first.pitchSemitones)
            assertEquals(true, engine.triggered.last().second)
            assertTrue(engine.stoppedPads.isEmpty())

            controller.setSelectedPadPitch(7f)
            assertEquals(triggerCount + 1, engine.triggered.size)

            controller.undoEdit()
            assertEquals(0f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(PadPlayMode.LOOP, controller.state.value.pads[loopPad].playMode)
        } finally {
            controller.close()
        }
    }

    @Test
    fun fatalLoopReplacementErrorIsNotMisreportedAsARecoverableEditFailure() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            val originalGain = controller.state.value.pads[loopPad].gain
            engine.nextTriggerFailure = AssertionError("test fatal audio error")

            val failure = assertFailsWith<AssertionError> {
                controller.setSelectedPadGain(0.5f)
            }

            assertEquals("test fatal audio error", failure.message)
            assertEquals(originalGain, controller.state.value.pads[loopPad].gain)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
        } finally {
            controller.close()
        }
    }

    @Test
    fun rejectedRecordingTimePadEditDoesNotRetriggerTheLoop() {
        val engine = FakeAudioEngine()
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(
            player = engine,
            microphone = recorder,
            autosaveStore = null,
        )
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            controller.toggleVocalRecording()
            assertTrue(controller.state.value.recordingSession is RecordingSession.Active)
            val triggerCount = engine.triggered.size
            engine.stoppedPads.clear()
            val originalTone = controller.state.value.pads[loopPad].tone

            controller.setSelectedPadTone(0.2f)

            assertEquals(originalTone, controller.state.value.pads[loopPad].tone)
            assertEquals(triggerCount, engine.triggered.size)
            assertTrue(engine.stoppedPads.isEmpty())
            assertEquals("録音をSTOPしてから編集してください", controller.state.value.statusMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun matchingChokeTriggerStopsPublishedLoopSessionBeforePlayingRequestedPad() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val bankStart = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            val loopPad = bankStart + 8
            val requestedPad = bankStart + 9
            assertEquals(1, controller.state.value.pads[loopPad].chokeGroup)
            assertEquals(1, controller.state.value.pads[requestedPad].chokeGroup)

            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            engine.stoppedPads.clear()
            val triggerCountBeforeRequest = engine.triggered.size

            controller.triggerPad(requestedPad)

            assertEquals(null, controller.state.value.loopingPadIndex)
            assertEquals(-1, controller.state.value.loopPlayheadFrame)
            assertEquals(listOf(loopPad), engine.stoppedPads)
            assertEquals(triggerCountBeforeRequest + 1, engine.triggered.size)
            assertEquals(requestedPad, engine.triggered.last().first.globalIndex)
        } finally {
            controller.close()
        }
    }

    @Test
    fun matchingChokeTriggerStopsTheLoopOwnersVocalCompanionSet() {
        val directory = Files.createTempDirectory("choplab-choke-loop-session").toFile()
        val store = AtomicProjectStore(directory)
        val audio = PcmAudio(
            name = "choke-session.wav",
            samples = ShortArray(256) { 6_000 },
            sampleRate = 48_000,
        )
        val loopPad = 4
        val vocalCompanion = 5
        val requestedPad = 6
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                loopPad -> PadModel(index, audio, 0, audio.frameCount, chokeGroup = 1)
                vocalCompanion -> PadModel(
                    index,
                    audio,
                    0,
                    audio.frameCount,
                    contentKind = PadContentKind.VOCAL,
                )
                requestedPad -> PadModel(index, audio, 0, audio.frameCount, chokeGroup = 1)
                else -> PadModel(index)
            }
        }
        store.save(
            SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                pads = pads,
                selectedPad = loopPad,
            ),
        )
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(
            player = engine,
            autosaveStore = store,
            autosaveDelayMillis = 0L,
        )
        try {
            awaitCondition { !controller.state.value.isLoading }
            controller.toggleBeatLoopControl()
            assertEquals(listOf(loopPad, vocalCompanion), engine.triggered.map { it.first.globalIndex })
            engine.stoppedPads.clear()

            controller.triggerPad(requestedPad)

            assertEquals(listOf(vocalCompanion, loopPad), engine.stoppedPads)
            assertEquals(null, controller.state.value.loopingPadIndex)
            assertEquals(requestedPad, engine.triggered.last().first.globalIndex)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun differentChokeGroupKeepsTheLoopSessionAndOrdinaryPolyphony() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val bankStart = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            val loopPad = bankStart + 8
            val requestedPad = bankStart + 4
            assertEquals(1, controller.state.value.pads[loopPad].chokeGroup)
            assertEquals(0, controller.state.value.pads[requestedPad].chokeGroup)

            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            engine.stoppedPads.clear()

            controller.triggerPad(requestedPad)

            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(engine.stoppedPads.isEmpty())
            assertEquals(requestedPad, engine.triggered.last().first.globalIndex)
        } finally {
            controller.close()
        }
    }

    @Test
    fun chokeStopFailureKeepsLoopTruthAndRejectsTheRequestedTrigger() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val bankStart = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            val loopPad = bankStart + 8
            val requestedPad = bankStart + 9
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            val triggerCountBeforeRequest = engine.triggered.size
            engine.failNextStopPad = true

            controller.triggerPad(requestedPad)

            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCountBeforeRequest, engine.triggered.size)
            assertTrue(
                controller.state.value.statusMessage.startsWith(
                    "CHOKEでビートループを停止できないためPADを再生しませんでした:",
                ),
            )
        } finally {
            controller.close()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Timed out waiting for asynchronous desktop state")
            Thread.sleep(20L)
        }
    }

    private class FakeAudioEngine : DesktopSamplerAudioEngine {
        override var isSourcePlaying: Boolean = false
        var sourcePosition: Int = 0
        val triggered = mutableListOf<Pair<PadModel, Boolean>>()
        val stoppedPads = mutableListOf<Int>()
        var failNextTrigger: Boolean = false
        var failNextStopPad: Boolean = false
        var nextTriggerFailure: Throwable? = null
        override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) = Unit
        override fun playFrom(frame: Int) { sourcePosition = frame; isSourcePlaying = true }
        override fun seekSource(frame: Int) { sourcePosition = frame }
        override fun sourceFramePosition(): Int = sourcePosition
        override fun padFramePosition(index: Int): Int? = null
        override fun stop() { isSourcePlaying = false }
        override fun triggerPad(pad: PadModel, forceLoop: Boolean) {
            nextTriggerFailure?.let { failure ->
                nextTriggerFailure = null
                throw failure
            }
            if (failNextTrigger) {
                failNextTrigger = false
                error("test output unavailable")
            }
            triggered += pad to forceLoop
        }
        override fun releasePad(index: Int) = Unit
        override fun stopPad(index: Int) {
            if (failNextStopPad) {
                failNextStopPad = false
                error("test stop unavailable")
            }
            stoppedPads += index
        }
        override fun stopAll() { isSourcePlaying = false }
        override fun close() = Unit
    }

    private class FakeRecorder : DesktopAudioRecorder {
        private var output: File? = null
        val lastOutput: File?
            get() = output
        var failStop: Boolean = false
        override var isRecording: Boolean = false
            private set

        override fun start(file: File): Result<Unit> {
            output = file
            isRecording = true
            if (failStop) {
                file.parentFile?.mkdirs()
                file.writeText("partial", Charsets.UTF_8)
            }
            return Result.success(Unit)
        }

        override fun stop(): Result<File> {
            val file = output ?: return Result.failure(IllegalStateException("recording was not started"))
            if (failStop) {
                isRecording = false
                return Result.failure(IllegalStateException("test recorder stop failure"))
            }
            file.parentFile?.mkdirs()
            val pcm = ByteArray(960) { index -> if (index % 4 < 2) 0x20 else 0xE0.toByte() }
            WavFileWriter(file, sampleRate = 48_000, channelCount = 1).use { writer ->
                writer.writePcm16Bytes(pcm, pcm.size)
            }
            isRecording = false
            return Result.success(file)
        }

        override fun close() {
            isRecording = false
        }
    }
}
