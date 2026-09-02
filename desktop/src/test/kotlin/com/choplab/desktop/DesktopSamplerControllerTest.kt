package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopLoopSessionStartupException
import com.choplab.desktop.audio.DesktopPreparedLoopSession
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.desktop.audio.DesktopStartedLoopSession
import com.choplab.desktop.persistence.DesktopProjectFiles
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PatternArrangement
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.ScratchReturnTarget
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.selectedPadPage
import com.choplab.sampler.model.materializedPatternArrangement
import com.choplab.sampler.persistence.AtomicProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.choplab.sampler.ui.PadTriggerOwnership
import com.choplab.sampler.ui.WorkflowStage
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
    fun transportStartsWithEveryAudibleStepZeroHitExactlyOnce() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val stepZeroPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.clearAllPattern()
            controller.selectPad(stepZeroPad)
            controller.toggleStep(0)
            controller.setBpm(40f)

            controller.toggleTransport()
            awaitCondition { engine.triggered.any { it.first.globalIndex == stepZeroPad } }
            controller.toggleTransport()

            assertEquals(1, engine.triggered.count { it.first.globalIndex == stepZeroPad })
            assertFalse(controller.state.value.transportPlaying)
            assertEquals(-1, controller.state.value.currentStep)
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedTransportRestartAfterScratchRestoresRecordArm() {
        val controller = controller()
        try {
            val stepZeroPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.clearAllPattern()
            controller.selectPad(stepZeroPad)
            controller.toggleStep(0)
            controller.toggleRecordArm()
            controller.transportWorkerStarter = { error("test transport start unavailable") }

            val resumed = controller.resumeAfterScratch(ScratchReturnTarget.Transport)

            assertFalse(resumed)
            assertTrue(controller.state.value.recordArmed)
            assertFalse(controller.state.value.transportPlaying)
            assertEquals(-1, controller.state.value.currentStep)
            assertTrue(controller.state.value.statusMessage.startsWith("スクラッチ後のビート再開失敗"))
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
    fun recordingTimeHistoryRequestPreservesTheProjectAndFrontier() {
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = recorder,
            autosaveStore = null,
        )
        try {
            controller.setBpm(126f)
            controller.toggleMicrophoneRecording()
            val before = controller.state.value
            assertEquals(
                RecordingSession.Active(RecordingKind.SOURCE_MICROPHONE, RecordingPhase.RECORDING),
                before.recordingSession,
            )

            controller.undoEdit()

            val denied = controller.state.value
            assertEquals(126f, denied.bpm)
            assertTrue(denied.canUndo)
            assertFalse(denied.canRedo)
            assertEquals(before.recordingSession, denied.recordingSession)
            assertEquals(before.loopingPadIndex, denied.loopingPadIndex)
            assertEquals("録音をSTOPしてから編集してください", denied.statusMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun loadingTimeHistoryRequestPreservesTheProjectAndFrontier() {
        val directory = Files.createTempDirectory("choplab-history-loading").toFile()
        val source = directory.resolve("loading-history.wav")
        WavFileWriter(source, sampleRate = 48_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(64) { it.toShort() })
        }
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            autosaveStore = null,
            recoverAutosaveOnStart = false,
        )
        try {
            controller.setBpm(126f)
            controller.loadWav(source)
            engine.awaitBlockedLoad()
            val before = controller.state.value
            assertTrue(before.isLoading)
            assertTrue(before.canUndo)

            controller.undoEdit()

            val denied = controller.state.value
            assertEquals(126f, denied.bpm)
            assertTrue(denied.isLoading)
            assertTrue(denied.canUndo)
            assertFalse(denied.canRedo)
            assertEquals(before.loopingPadIndex, denied.loopingPadIndex)
            assertEquals("現在の処理が終わってから編集してください", denied.statusMessage)
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            directory.deleteRecursively()
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
    fun closeFlushesTheLatestEditBeforeTheAutosaveDebounceExpires() {
        val directory = Files.createTempDirectory("choplab-controller-close-autosave").toFile()
        val store = AtomicProjectStore(directory)
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = false,
        )
        try {
            controller.setBpm(137f)

            controller.close()

            assertEquals(137f, store.load()?.bpm)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeFlushesTheLatestEditEvenWhenAudioTeardownThrows() {
        val directory = Files.createTempDirectory("choplab-controller-close-teardown-failure").toFile()
        val store = AtomicProjectStore(directory)
        val engine = FakeAudioEngine().apply {
            failNextStopAll = true
            failClose = true
        }
        val microphone = FakeRecorder().apply { failClose = true }
        val controller = DesktopSamplerController(
            engine,
            microphone = microphone,
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = false,
        )
        try {
            controller.setBpm(146f)

            val closeResult = runCatching { controller.close() }

            assertTrue(closeResult.isSuccess)
            assertEquals(146f, store.load()?.bpm)
            assertEquals(1, engine.stopAllCalls)
            assertEquals(1, engine.closeCalls)
            assertEquals(1, microphone.closeCalls)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeWaitsForTheRunningAutosaveWithoutWritingTheSameSnapshotTwice() {
        val directory = Files.createTempDirectory("choplab-controller-running-autosave").toFile()
        val store = AtomicProjectStore(directory)
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        lateinit var closeThread: Thread
        try {
            synchronized(store) {
                controller.setBpm(143f)
                awaitAutosaveBlockedOnStore()

                closeThread = thread(name = "ChopLab-Test-Close") { controller.close() }
                awaitThreadWaiting(closeThread)
            }
            closeThread.join(2_000L)

            assertFalse(closeThread.isAlive)
            assertEquals(143f, store.load()?.bpm)
            assertTrue(directory.resolve("autosave.choplab").isFile)
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
            assertFalse(directory.resolve("autosave.previous2.choplab").exists())
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeStopsLiveIoBeforeWaitingForTheRunningAutosave() {
        val directory = Files.createTempDirectory("choplab-controller-close-order").toFile()
        val store = AtomicProjectStore(directory)
        val engine = FakeAudioEngine()
        val microphone = FakeRecorder()
        val systemAudio = FakeRecorder()
        val controller = DesktopSamplerController(
            engine,
            microphone = microphone,
            systemAudio = systemAudio,
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        lateinit var closeThread: Thread
        try {
            synchronized(store) {
                controller.setBpm(144f)
                awaitAutosaveBlockedOnStore()
                controller.toggleMicrophoneRecording()
                controller.toggleTransport()
                engine.stopAllCalls = 0

                closeThread = thread(name = "ChopLab-Test-Ordered-Close") { controller.close() }
                awaitCondition {
                    microphone.closeCalls == 1 && systemAudio.closeCalls == 1 && engine.closeCalls == 1
                }
                awaitThreadWaiting(closeThread)

                assertFalse(microphone.isRecording)
                assertEquals(1, microphone.closeCalls)
                assertEquals(1, systemAudio.closeCalls)
                assertEquals(1, engine.stopAllCalls)
                assertEquals(1, engine.closeCalls)
                assertFalse(controller.state.value.transportPlaying)
            }
            closeThread.join(2_000L)

            assertFalse(closeThread.isAlive)
            assertEquals(144f, store.load()?.bpm)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closePersistsNewerAsyncProjectAfterWaitingForOlderRunningAutosave() {
        val directory = Files.createTempDirectory("choplab-controller-close-newer-project").toFile()
        val store = AtomicProjectStore(directory)
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        lateinit var closeThread: Thread
        try {
            val audio = PcmAudio(
                name = "newer-project.wav",
                samples = ShortArray(64) { it.toShort() },
                sampleRate = 48_000,
            )
            val newerProject = DesktopProjectFiles.save(
                directory.resolve("newer-project.choplab"),
                SamplerUiState(
                    currentAudio = audio,
                    rangeEndFrame = audio.frameCount,
                    bpm = 151f,
                ),
            )
            synchronized(store) {
                controller.setBpm(143f)
                awaitAutosaveBlockedOnStore()

                controller.openProject(newerProject)
                engine.awaitBlockedLoad()
                assertEquals(151f, controller.state.value.bpm)

                closeThread = thread(name = "ChopLab-Test-Newer-Project-Close") { controller.close() }
                awaitCondition { closeThread.state == Thread.State.BLOCKED }
                engine.releaseBlockedLoad()
                awaitThreadWaiting(closeThread)
            }
            closeThread.join(2_000L)

            assertFalse(closeThread.isAlive)
            assertEquals(151f, store.load()?.bpm)
            assertEquals(
                143f,
                DesktopProjectFiles.load(directory.resolve("autosave.previous.choplab")).bpm,
            )
            assertFalse(directory.resolve("autosave.previous2.choplab").exists())
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeInvalidatesLoadBeforeCapturingItsPublishedProject() {
        val directory = Files.createTempDirectory("choplab-controller-close-load").toFile()
        val store = AtomicProjectStore(directory)
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val source = directory.resolve("close-race.wav")
        WavFileWriter(source, sampleRate = 48_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(64) { it.toShort() })
        }
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        lateinit var closeThread: Thread
        try {
            controller.loadWav(source)
            engine.awaitBlockedLoad()
            assertEquals(null, controller.state.value.currentAudio)

            closeThread = thread(name = "ChopLab-Test-Load-Close") { controller.close() }
            awaitCondition { closeThread.state == Thread.State.BLOCKED }
            engine.releaseBlockedLoad()
            closeThread.join(2_000L)

            assertFalse(closeThread.isAlive)
            val recovered = requireNotNull(store.load())
            assertEquals(source.name, recovered.currentAudio?.name)
            assertEquals(64, recovered.currentAudio?.frameCount)
            assertEquals(64, recovered.rangeEndFrame)
            // Launch target/revision are runtime routing hints and intentionally are
            // not fields in the project archive; ProjectArchiveCodecTest binds that.
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeRetriesTheLatestAutosaveAfterATransientStoreFailure() {
        val root = Files.createTempDirectory("choplab-controller-close-retry").toFile()
        val blockedDirectory = root.resolve("autosave-target").apply {
            writeText("not a directory")
        }
        val store = AtomicProjectStore(blockedDirectory)
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
        )
        try {
            controller.setBpm(149f)
            awaitCondition { controller.state.value.statusMessage.startsWith("自動保存失敗:") }
            assertTrue(blockedDirectory.delete())
            assertTrue(blockedDirectory.mkdir())

            controller.close()

            assertEquals(149f, store.load()?.bpm)
            assertFalse(blockedDirectory.resolve("autosave.previous.choplab").exists())
        } finally {
            controller.close()
            root.deleteRecursively()
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
    fun failedExplicitStartupProjectDoesNotReplaceTheExistingAutosaveWithAPlaceholder() {
        val directory = Files.createTempDirectory("choplab-explicit-startup-failure").toFile()
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 117f), revision = 4L)
        val primary = directory.resolve("autosave.choplab")
        val originalArchive = primary.readBytes()
        val invalidStartup = directory.resolve("invalid-startup.wav").apply {
            writeText("not a wav", Charsets.UTF_8)
        }
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = false,
            preserveAutosaveUntilInitialProjectReplacement = true,
        )
        try {
            controller.loadWav(invalidStartup)
            awaitCondition { controller.state.value.statusMessage.startsWith("WAV読込失敗:") }

            controller.close()

            assertEquals(117f, store.load()?.bpm)
            assertTrue(originalArchive.contentEquals(primary.readBytes()))
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeWaitsForStartupRecoveryWithoutRotatingAnUnchangedArchive() {
        val directory = Files.createTempDirectory("choplab-close-startup-recovery").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "startup-recovery.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
                bpm = 127f,
            ),
            revision = 7L,
        )
        val originalArchive = directory.resolve("autosave.choplab").readBytes()
        val engine = FakeAudioEngine()
        var controller: DesktopSamplerController? = null
        lateinit var closeThread: Thread
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 0L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()
                assertTrue(created.state.value.isLoading)

                closeThread = thread(name = "ChopLab-Test-Recovery-Close") { created.close() }
                awaitThreadWaiting(closeThread)
                assertTrue(closeThread.isAlive)
            }
            closeThread.join(2_000L)

            assertFalse(closeThread.isAlive)
            assertEquals(0, engine.loadPcmCalls)
            assertEquals(127f, store.load()?.bpm)
            assertTrue(originalArchive.contentEquals(directory.resolve("autosave.choplab").readBytes()))
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
            assertFalse(directory.resolve("autosave.previous2.choplab").exists())
        } finally {
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun saveAndExportStatusWorkCannotCancelRecoveredAudioHydration() {
        val directory = Files.createTempDirectory("choplab-recovery-save-export").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "recovered-for-save.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val manualProject = directory.resolve("manual-save.choplab")
        val beatExport = directory.resolve("manual-export.wav")
        val engine = FakeAudioEngine()
        val statusWorkStarted = CountDownLatch(1)
        var controller: DesktopSamplerController? = null
        var recoveryObserver: Job? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 0L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()
                recoveryObserver = CoroutineScope(Dispatchers.Unconfined).launch {
                    created.state
                        .filter { it.statusMessage == "前回の自動保存を復元しました" }
                        .first()
                    created.saveProject(manualProject)
                    created.exportBeat(beatExport)
                    statusWorkStarted.countDown()
                }
            }

            assertTrue(statusWorkStarted.await(2L, TimeUnit.SECONDS))
            awaitCondition { engine.loadedAudioNames == listOf(recoveredAudio.name) }

            assertTrue(manualProject.isFile)
            assertTrue(beatExport.isFile)
            assertEquals(recoveredAudio.name, controller?.state?.value?.currentAudio?.name)
        } finally {
            recoveryObserver?.cancel()
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveredAudioHydrationCannotOverwriteAPitchEditPublishedBeforeItRuns() {
        val directory = Files.createTempDirectory("choplab-recovery-pitch-edit").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "recovered-before-pitch.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val engine = FakeAudioEngine()
        val pitchEdited = CountDownLatch(1)
        var controller: DesktopSamplerController? = null
        var recoveryObserver: Job? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 60_000L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()
                recoveryObserver = CoroutineScope(Dispatchers.Unconfined).launch {
                    created.state
                        .filter { it.statusMessage == "前回の自動保存を復元しました" }
                        .first()
                    created.setMasterPitch(7f)
                    pitchEdited.countDown()
                }
            }

            assertTrue(pitchEdited.await(2L, TimeUnit.SECONDS))
            awaitCondition { engine.loadedPitchSemitones.isNotEmpty() }
            Thread.sleep(100L)

            assertEquals(7f, controller?.state?.value?.masterPitchSemitones)
            assertEquals(listOf(7f), engine.loadedPitchSemitones)
            controller?.playSourceFrom(0)
            assertEquals(1, engine.playFromCalls)
            assertTrue(controller?.state?.value?.sourcePlaying == true)
        } finally {
            recoveryObserver?.cancel()
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetRevokesRecoveredAudioHydrationAdmittedBeforeSourceLoad() {
        val directory = Files.createTempDirectory("choplab-reset-recovery-hydration").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "discarded-recovery-hydration.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val engine = FakeAudioEngine()
        val hydrationAdmitted = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val saveBarrier = directory.resolve("reset-save-barrier.choplab")
        var controller: DesktopSamplerController? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 60_000L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                created.recoveredHydrationAdmission = {
                    hydrationAdmitted.countDown()
                    check(releaseHydration.await(2L, TimeUnit.SECONDS)) {
                        "Timed out holding admitted recovered hydration"
                    }
                }
                awaitAutosaveBlockedOnStore()
            }

            assertTrue(hydrationAdmitted.await(2L, TimeUnit.SECONDS))
            controller?.resetProject()
            releaseHydration.countDown()
            controller?.saveProject(saveBarrier)
            awaitCondition(saveBarrier::isFile)

            assertEquals(0, engine.loadPcmCalls)
            assertNull(controller?.state?.value?.currentAudio)
        } finally {
            releaseHydration.countDown()
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeDoesNotWaitForAnAdmittedRecoveredAudioDeviceLoad() {
        val directory = Files.createTempDirectory("choplab-close-recovery-hydration").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "blocked-recovery-hydration.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = true,
        )
        val closeFinished = CountDownLatch(1)
        var closeThread: Thread? = null
        try {
            engine.awaitBlockedLoad()
            val closing = thread(name = "ChopLab-Recovery-Hydration-Close") {
                controller.close()
                closeFinished.countDown()
            }
            closeThread = closing

            assertTrue(closeFinished.await(1L, TimeUnit.SECONDS))
            closing.join(1_000L)
            assertFalse(closing.isAlive)
            awaitCondition { engine.closeCalls == 1 }
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            closeThread?.join(2_000L)
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourcePlayDuringRecoveredAudioHydrationReportsPendingThenSucceeds() {
        val directory = Files.createTempDirectory("choplab-recovery-hydration-play").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "pending-recovery-hydration.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = true,
        )
        try {
            engine.awaitBlockedLoad()

            controller.playSourceFrom(0)

            assertEquals(0, engine.playFromCalls)
            assertFalse(controller.state.value.sourcePlaying)
            assertEquals("音声の再生を準備しています", controller.state.value.statusMessage)

            engine.releaseBlockedLoad()
            awaitCondition { controller.state.value.statusMessage == "前回の自動保存を復元しました" }

            controller.playSourceFrom(0)

            assertEquals(1, engine.playFromCalls)
            assertTrue(controller.state.value.sourcePlaying)
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedUserLoadDuringStartupFallsBackToRecoveredProject() {
        val directory = Files.createTempDirectory("choplab-recovery-failed-user-load").toFile()
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 129f), revision = 8L)
        val originalArchive = directory.resolve("autosave.choplab").readBytes()
        val invalidWav = directory.resolve("invalid-user-selection.wav").apply {
            writeText("not a wav", Charsets.UTF_8)
        }
        var controller: DesktopSamplerController? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    FakeAudioEngine(),
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 0L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()

                created.loadWav(invalidWav)
                awaitCondition { created.state.value.statusMessage.startsWith("WAV読込失敗:") }
            }

            val created = requireNotNull(controller)
            awaitCondition { created.state.value.bpm == 129f && !created.state.value.isLoading }
            created.close()

            assertEquals(129f, created.state.value.bpm)
            assertEquals(129f, store.load()?.bpm)
            assertTrue(originalArchive.contentEquals(directory.resolve("autosave.choplab").readBytes()))
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
        } finally {
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulUserLoadSurvivesAStaleStartupRecoveryFailureDuringClose() {
        val directory = Files.createTempDirectory("choplab-recovery-stale-failure").toFile()
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 129f), revision = 8L)
        val replacement = directory.resolve("successful-user-selection.wav")
        WavFileWriter(replacement, sampleRate = 48_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(64) { it.toShort() })
        }
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        var controller: DesktopSamplerController? = null
        var closeThread: Thread? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 60_000L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()
                directory.resolve("autosave.choplab")
                    .writeText("corrupt startup archive", Charsets.UTF_8)

                created.loadWav(replacement)
                engine.awaitBlockedLoad()
                closeThread = thread(name = "ChopLab-Stale-Recovery-Failure-Close") {
                    created.close()
                }
                awaitThreadWaiting(requireNotNull(closeThread))
                engine.releaseBlockedLoad()
                awaitCondition { created.state.value.currentAudio?.name == replacement.name }
            }

            requireNotNull(closeThread).join(5_000L)
            assertFalse(
                requireNotNull(closeThread).isAlive,
                "Close must finish after the stale recovery and admitted source load are released",
            )
            assertEquals(replacement.name, store.load()?.currentAudio?.name)
        } finally {
            engine.releaseBlockedLoad()
            controller?.close()
            closeThread?.join(5_000L)
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveredAudioHydrationCannotSupersedeAUserLoadStartedAfterPublication() {
        val directory = Files.createTempDirectory("choplab-recovery-user-load").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "recovered-startup.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 5L,
        )
        val replacement = directory.resolve("user-selected.wav")
        WavFileWriter(replacement, sampleRate = 48_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(4_000_000) { index -> (index * 31).toShort() })
        }
        val engine = FakeAudioEngine()
        val userLoadStarted = CountDownLatch(1)
        var controller: DesktopSamplerController? = null
        var recoveryObserver: Job? = null
        try {
            synchronized(store) {
                val created = DesktopSamplerController(
                    engine,
                    microphone = FakeRecorder(),
                    systemAudio = FakeRecorder(),
                    autosaveStore = store,
                    autosaveDelayMillis = 0L,
                    recoverAutosaveOnStart = true,
                )
                controller = created
                awaitAutosaveBlockedOnStore()
                recoveryObserver = CoroutineScope(Dispatchers.Unconfined).launch {
                    created.state
                        .filter { it.statusMessage == "前回の自動保存を復元しました" }
                        .first()
                    created.loadWav(replacement)
                    userLoadStarted.countDown()
                }
            }

            assertTrue(userLoadStarted.await(2L, TimeUnit.SECONDS))
            val created = requireNotNull(controller)
            awaitCondition { created.state.value.currentAudio?.name == replacement.name }

            assertEquals(listOf(replacement.name), engine.loadedAudioNames)
            assertEquals(replacement.name, created.state.value.currentAudio?.name)
        } finally {
            recoveryObserver?.cancel()
            controller?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupRecoveryReportsRecoveredAudioDeviceFailureWithoutDiscardingTheSource() {
        val directory = Files.createTempDirectory("choplab-recovery-device-failure").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "device-failure.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 6L,
        )
        val engine = FakeAudioEngine().apply { failNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = true,
        )
        try {
            awaitCondition {
                controller.state.value.statusMessage.startsWith(
                    "音声は読込済みですが再生機器を開けません:",
                )
            }

            assertEquals(recoveredAudio.name, controller.state.value.currentAudio?.name)
            assertTrue(controller.state.value.statusMessage.contains("test output unavailable"))
            assertEquals(listOf(recoveredAudio.name), engine.loadedAudioNames)
            val deviceFailureStatus = controller.state.value.statusMessage

            val playbackResult = runCatching {
                controller.playSourceFrom(0)
                controller.toggleSourcePlayback()
            }

            assertTrue(playbackResult.isSuccess)
            assertEquals(0, engine.playFromCalls)
            assertFalse(controller.state.value.sourcePlaying)
            assertEquals(deviceFailureStatus, controller.state.value.statusMessage)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedPitchReloadStopsThePreviouslyPlayingSource() {
        val directory = Files.createTempDirectory("choplab-pitch-reload-failure").toFile()
        val store = AtomicProjectStore(directory)
        val recoveredAudio = PcmAudio(
            name = "playing-before-pitch.wav",
            samples = ShortArray(32) { it.toShort() },
            sampleRate = 48_000,
        )
        store.save(
            SamplerUiState(
                currentAudio = recoveredAudio,
                rangeEndFrame = recoveredAudio.frameCount,
            ),
            revision = 6L,
        )
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = true,
        )
        try {
            awaitCondition { engine.loadPcmCalls == 1 }
            controller.playSourceFrom(0)
            assertTrue(controller.state.value.sourcePlaying)
            assertTrue(engine.isSourcePlaying)
            engine.failNextLoad = true

            controller.setMasterPitch(5f)

            // The key change stops old-key playback immediately and reports the render failure
            // from the I/O worker instead of freezing the UI thread while a long source renders.
            assertFalse(controller.state.value.sourcePlaying)
            assertFalse(engine.isSourcePlaying)
            awaitCondition { controller.state.value.statusMessage.contains("test output unavailable") }
            assertFalse(controller.state.value.sourcePlaying)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rapidMasterPitchChangesResumePlaybackAtTheLatestKey() {
        val directory = Files.createTempDirectory("choplab-rapid-master-pitch").toFile()
        val store = AtomicProjectStore(directory)
        store.save(
            SamplerUiState(
                currentAudio = PcmAudio(
                    name = "rapid-pitch.wav",
                    samples = ShortArray(64) { it.toShort() },
                    sampleRate = 8_000,
                ),
                rangeEndFrame = 64,
            ),
            revision = 1L,
        )
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = true,
        )
        try {
            awaitCondition { engine.loadPcmCalls == 1 }
            engine.sourcePosition = 23
            controller.playSourceFrom(23)
            assertTrue(controller.state.value.sourcePlaying)
            engine.blockNextLoad = true

            controller.setMasterPitch(2f)
            engine.awaitBlockedLoad()
            controller.setMasterPitch(4f)
            engine.releaseBlockedLoad()

            awaitCondition { engine.loadedPitchSemitones.lastOrNull() == 4f }
            awaitCondition { controller.state.value.statusMessage == controller.masterPitchAppliedMessage(4f) }
            assertEquals(4f, controller.state.value.masterPitchSemitones)
            assertTrue(controller.state.value.sourcePlaying)
            assertTrue(engine.isSourcePlaying)
            assertEquals(2, engine.playFromCalls)
            assertEquals(23, engine.sourcePosition)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun stopAllDuringMasterPitchRenderPreventsLatePlaybackResume() {
        val directory = Files.createTempDirectory("choplab-stop-pending-master-pitch").toFile()
        val store = AtomicProjectStore(directory)
        store.save(
            SamplerUiState(
                currentAudio = PcmAudio(
                    name = "stop-pending-pitch.wav",
                    samples = ShortArray(64) { it.toShort() },
                    sampleRate = 8_000,
                ),
                rangeEndFrame = 64,
            ),
            revision = 1L,
        )
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(
            engine,
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 60_000L,
            recoverAutosaveOnStart = true,
        )
        try {
            awaitCondition { engine.loadPcmCalls == 1 }
            controller.playSourceFrom(19)
            engine.blockNextLoad = true

            controller.setMasterPitch(3f)
            engine.awaitBlockedLoad()
            controller.stopAllSounds()
            engine.releaseBlockedLoad()

            awaitCondition { engine.loadPcmCompletedCalls == 2 }
            assertFalse(controller.state.value.sourcePlaying)
            assertFalse(engine.isSourcePlaying)
            assertEquals(1, engine.playFromCalls)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun closeDoesNotPersistAStartupRecoveryErrorPlaceholder() {
        val directory = Files.createTempDirectory("choplab-close-recovery-error").toFile()
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 131f), revision = 9L)
        val primary = directory.resolve("autosave.choplab")
        val validArchive = primary.readBytes()
        primary.writeText("corrupt startup archive", Charsets.UTF_8)
        val controller = DesktopSamplerController(
            FakeAudioEngine(),
            microphone = FakeRecorder(),
            systemAudio = FakeRecorder(),
            autosaveStore = store,
            autosaveDelayMillis = 0L,
            recoverAutosaveOnStart = true,
        )
        try {
            awaitCondition {
                controller.state.value.statusMessage.startsWith("自動保存を復元できません:")
            }
            primary.writeBytes(validArchive)

            controller.close()

            assertEquals(131f, store.load()?.bpm)
            assertTrue(validArchive.contentEquals(primary.readBytes()))
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
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
    fun olderPointerReleaseClosesOnlyItsVoiceAfterANewerTrigger() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = false)
            val padIndex = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(padIndex)
            controller.toggleSelectedPadPlayMode()
            assertEquals(PadPlayMode.GATE, controller.state.value.pads[padIndex].playMode)

            val pointerOwnership = controller.triggerPadWithOwnership(padIndex)
            val newerOwnership = controller.triggerPadWithOwnership(padIndex)
            controller.releasePadIfOwned(padIndex, pointerOwnership)

            assertEquals(2, engine.triggered.count { it.first.globalIndex == padIndex })
            assertNotEquals(pointerOwnership, newerOwnership)
            assertTrue(engine.releasedPads.isEmpty())
            assertEquals(listOf(padIndex to pointerOwnership), engine.releasedOwnedPads)

            controller.releasePad(padIndex)
            assertEquals(listOf(padIndex), engine.releasedPads)
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
    fun activeLoopUndoAndRedoReplaceTheSameOwnerWithoutStoppingTheLoop() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            controller.setSelectedPadPitch(7f)
            val triggerCount = engine.triggered.size
            val stopAllCount = engine.stopAllCount
            engine.stoppedPads.clear()

            controller.undoEdit()

            assertEquals(0f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCount + 1, engine.triggered.size)
            assertEquals(0f, engine.triggered.last().first.pitchSemitones)
            assertTrue(engine.triggered.last().second)
            assertEquals(stopAllCount, engine.stopAllCount)
            assertTrue(engine.stoppedPads.isEmpty())

            controller.redoEdit()

            assertEquals(7f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCount + 2, engine.triggered.size)
            assertEquals(7f, engine.triggered.last().first.pitchSemitones)
            assertTrue(engine.triggered.last().second)
            assertEquals(stopAllCount, engine.stopAllCount)
            assertTrue(engine.stoppedPads.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedActiveLoopUndoKeepsTheEditedLoopAndHistoryFrontier() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            controller.setSelectedPadPitch(7f)
            val triggerCount = engine.triggered.size
            val stopAllCount = engine.stopAllCount
            engine.stoppedPads.clear()
            engine.failNextTrigger = true

            controller.undoEdit()

            assertEquals(7f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(controller.state.value.canUndo)
            assertFalse(controller.state.value.canRedo)
            assertEquals(triggerCount, engine.triggered.size)
            assertEquals(stopAllCount, engine.stopAllCount)
            assertTrue(engine.stoppedPads.isEmpty())
            assertTrue(
                controller.state.value.statusMessage.startsWith(
                    "ループ音を戻せないためUndoを適用しませんでした:",
                ),
            )

            controller.undoEdit()
            assertEquals(0f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(controller.state.value.canRedo)

            val undoState = controller.state.value
            val triggerCountAfterUndo = engine.triggered.size
            engine.failNextTrigger = true
            controller.redoEdit()

            assertEquals(undoState.pads, controller.state.value.pads)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertTrue(controller.state.value.canRedo)
            assertEquals(triggerCountAfterUndo, engine.triggered.size)
            assertTrue(
                controller.state.value.statusMessage.startsWith(
                    "ループ音をやり直せないためRedoを適用しませんでした:",
                ),
            )

            controller.redoEdit()
            assertEquals(7f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
        } finally {
            controller.close()
        }
    }

    @Test
    fun undoPastTheLoopOwnerUsesTheExistingDisruptiveHistoryPath() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            controller.setSelectedPadPitch(7f)

            controller.undoEdit()
            val stopAllCount = engine.stopAllCount
            controller.undoEdit()

            assertEquals(null, controller.state.value.loopingPadIndex)
            assertEquals(PadPlayMode.ONE_SHOT, controller.state.value.pads[loopPad].playMode)
            assertEquals(stopAllCount + 1, engine.stopAllCount)
        } finally {
            controller.close()
        }
    }

    @Test
    fun activeLoopUndoWithAnUnchangedOwnerPadDoesNotRetriggerAudio() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            val patternPad = loopPad + 1
            controller.selectPad(patternPad)
            val stepWasActive = stepKey(patternPad, 0) in controller.state.value.activeSteps
            controller.toggleStep(0)
            assertEquals(!stepWasActive, stepKey(patternPad, 0) in controller.state.value.activeSteps)
            val triggerCount = engine.triggered.size
            val stopAllCount = engine.stopAllCount
            engine.stoppedPads.clear()

            controller.undoEdit()

            assertEquals(stepWasActive, stepKey(patternPad, 0) in controller.state.value.activeSteps)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(triggerCount, engine.triggered.size)
            assertEquals(stopAllCount, engine.stopAllCount)
            assertTrue(engine.stoppedPads.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun fatalActiveLoopUndoErrorDoesNotConsumeHistoryOrRewriteStatus() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleBeatLoopControl()
            controller.setSelectedPadPitch(7f)
            val before = controller.state.value
            engine.nextTriggerFailure = AssertionError("test fatal undo audio error")

            val failure = assertFailsWith<AssertionError> { controller.undoEdit() }

            assertEquals("test fatal undo audio error", failure.message)
            assertEquals(before, controller.state.value)
            assertTrue(controller.state.value.canUndo)
            assertFalse(controller.state.value.canRedo)

            controller.undoEdit()
            assertEquals(0f, controller.state.value.pads[loopPad].pitchSemitones)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
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
    fun failedDesktopTriggerDoesNotSupersedeAnOlderGateOwner() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = false)
            val padIndex = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(padIndex)
            controller.toggleSelectedPadPlayMode()
            assertEquals(PadPlayMode.GATE, controller.state.value.pads[padIndex].playMode)
            val olderOwnership = controller.triggerPadWithOwnership(padIndex)
            engine.failNextTrigger = true

            assertFailsWith<IllegalStateException> {
                controller.triggerPadWithOwnership(padIndex)
            }
            controller.releasePadIfOwned(padIndex, olderOwnership)

            assertTrue(engine.releasedPads.isEmpty())
            assertEquals(listOf(padIndex to olderOwnership), engine.releasedOwnedPads)
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

    @Test
    fun loadingDeniedInitialBeatLoopDoesNoAudioOrProjectWork() {
        val directory = Files.createTempDirectory("choplab-loop-loading-denial").toFile()
        val source = directory.resolve("loading-loop.wav")
        WavFileWriter(source, sampleRate = 48_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(64) { it.toShort() })
        }
        val engine = FakeAudioEngine().apply { blockNextLoad = true }
        val controller = DesktopSamplerController(
            engine,
            autosaveStore = null,
            recoverAutosaveOnStart = false,
        )
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.loadWav(source)
            engine.awaitBlockedLoad()
            val before = controller.state.value

            controller.toggleBeatLoopControl()

            val after = controller.state.value
            assertTrue(after.isLoading)
            assertEquals(before.pads, after.pads)
            assertEquals(before.loopingPadIndex, after.loopingPadIndex)
            assertEquals(before.canUndo, after.canUndo)
            assertEquals(0, engine.exclusiveStartCount)
            assertEquals("現在の処理が終わってから編集してください", after.statusMessage)
        } finally {
            engine.releaseBlockedLoad()
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun recordingDeniedInitialBeatLoopDoesNoAudioOrProjectWork() {
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
            controller.toggleMicrophoneRecording()
            assertTrue(controller.state.value.recordingSession is RecordingSession.Active)
            val before = controller.state.value
            val stopAllBefore = engine.stopAllCount

            controller.toggleBeatLoopControl()

            val after = controller.state.value
            assertEquals(before.pads, after.pads)
            assertEquals(before.loopingPadIndex, after.loopingPadIndex)
            assertEquals(before.recordingSession, after.recordingSession)
            assertEquals(before.canUndo, after.canUndo)
            assertEquals(stopAllBefore, engine.stopAllCount)
            assertEquals(0, engine.exclusiveStartCount)
            assertEquals("録音をSTOPしてから編集してください", after.statusMessage)
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedInitialBeatLoopStartKeepsTransportProjectAndHistoryFrontier() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleTransport()
            val before = controller.state.value
            val stopAllBefore = engine.stopAllCount
            engine.failNextExclusiveStart = true

            val result = runCatching { controller.toggleBeatLoopControl() }

            assertTrue(result.isSuccess)
            val after = controller.state.value
            assertEquals(before.pads, after.pads)
            assertEquals(before.transportPlaying, after.transportPlaying)
            assertEquals(before.currentStep, after.currentStep)
            assertEquals(before.loopingPadIndex, after.loopingPadIndex)
            assertEquals(before.canUndo, after.canUndo)
            assertEquals(before.canRedo, after.canRedo)
            assertEquals(stopAllBefore, engine.stopAllCount)
            assertEquals(0, engine.exclusiveRetireCount)
            assertTrue(after.statusMessage.startsWith("ビートループを開始できませんでした:"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedInitialLoopRetirementAbandonsStartedCandidatesAndPreservesState() {
        val engine = FakeAudioEngine().apply { failNextExclusiveRetire = true }
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            val before = controller.state.value

            val failure = assertFailsWith<IllegalStateException> {
                controller.toggleBeatLoopControl()
            }

            assertEquals("test loop retirement failure", failure.message)
            assertEquals(before, controller.state.value)
            assertEquals(1, engine.exclusiveAbandonCount)
            assertTrue(engine.triggered.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun slowFailedInitialBeatLoopPreparationDoesNotPauseExistingTransport() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.fillSelectedPadPattern(com.choplab.sampler.model.RepeatGrid.SIXTEENTH)
            controller.setBpm(240f)
            controller.toggleTransport()
            awaitCondition { engine.triggered.isNotEmpty() }
            engine.triggered.clear()

            val preparationEntered = CountDownLatch(1)
            val releasePreparation = CountDownLatch(1)
            engine.exclusiveStartHook = {
                preparationEntered.countDown()
                check(releasePreparation.await(2, TimeUnit.SECONDS))
            }
            engine.failNextExclusiveStart = true
            val start = executor.submit { controller.toggleBeatLoopControl() }

            assertTrue(preparationEntered.await(2, TimeUnit.SECONDS))
            Thread.sleep(400L)
            val transportContinued = engine.triggered.isNotEmpty()
            releasePreparation.countDown()
            start.get(2, TimeUnit.SECONDS)

            assertTrue(transportContinued)
            assertTrue(controller.state.value.transportPlaying)
            assertTrue(controller.state.value.statusMessage.startsWith("ビートループを開始できませんでした:"))
        } finally {
            executor.shutdownNow()
            controller.close()
        }
    }

    @Test
    fun failedVocalCompanionStartupPublishesNoPartialLoopSession() {
        val directory = Files.createTempDirectory("choplab-loop-session-start-failure").toFile()
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
        val durableBefore = directory.resolve("autosave.choplab").readBytes()
        val controller = DesktopSamplerController(engine, autosaveStore = store, autosaveDelayMillis = 0L)
        try {
            awaitCondition { !controller.state.value.isLoading }
            engine.triggered.clear()
            val before = controller.state.value
            val stopAllBefore = engine.stopAllCount
            engine.failTriggerPad = companionPadIndex

            controller.toggleBeatLoopControl()

            val after = controller.state.value
            assertEquals(before.pads, after.pads)
            assertEquals(before.loopingPadIndex, after.loopingPadIndex)
            assertEquals(before.canUndo, after.canUndo)
            assertEquals(before.canRedo, after.canRedo)
            assertEquals(stopAllBefore, engine.stopAllCount)
            assertEquals(0, engine.exclusiveRetireCount)
            assertTrue(engine.triggered.isEmpty())
            assertTrue(after.statusMessage.startsWith("ビートループを開始できませんでした:"))

            controller.close()
            assertTrue(durableBefore.contentEquals(directory.resolve("autosave.choplab").readBytes()))
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun fatalInitialBeatLoopStartupPropagatesWithoutChangingProduction() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleTransport()
            val before = controller.state.value
            engine.nextTriggerFailure = AssertionError("test fatal initial loop error")

            val failure = assertFailsWith<AssertionError> {
                controller.toggleBeatLoopControl()
            }

            assertEquals("test fatal initial loop error", failure.message)
            assertEquals(before, controller.state.value)
            assertEquals(0, engine.exclusiveRetireCount)
        } finally {
            controller.close()
        }
    }

    @Test
    fun unexpectedInitialBeatLoopAdapterExceptionPropagatesWithoutChangingProduction() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleTransport()
            val before = controller.state.value
            engine.nextTriggerFailure = IllegalStateException("test contract violation")

            val failure = assertFailsWith<IllegalStateException> {
                controller.toggleBeatLoopControl()
            }

            assertEquals("test contract violation", failure.message)
            assertEquals(before, controller.state.value)
            assertEquals(0, engine.exclusiveRetireCount)
        } finally {
            controller.close()
        }
    }

    @Test
    fun successfulInitialBeatLoopStartCommitsOneEditAfterTheCandidateSession() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.toggleTransport()
            engine.triggered.clear()
            val stopAllBefore = engine.stopAllCount
            assertFalse(controller.state.value.canUndo)

            controller.toggleBeatLoopControl()

            val started = controller.state.value
            assertFalse(started.transportPlaying)
            assertEquals(loopPad, started.loopingPadIndex)
            assertEquals(PadPlayMode.LOOP, started.pads[loopPad].playMode)
            assertTrue(started.canUndo)
            assertFalse(started.canRedo)
            assertEquals(stopAllBefore, engine.stopAllCount)
            assertEquals(1, engine.exclusiveRetireCount)
            assertEquals(loopPad, engine.triggered.single().first.globalIndex)
            assertTrue(engine.triggered.single().second)

            controller.undoEdit()

            val undone = controller.state.value
            assertEquals(null, undone.loopingPadIndex)
            assertEquals(PadPlayMode.ONE_SHOT, undone.pads[loopPad].playMode)
            assertFalse(undone.canUndo)
            assertTrue(undone.canRedo)
        } finally {
            controller.close()
        }
    }

    @Test
    fun loopHandoffBlocksLateTransportVoicesUntilStopIsPublished() {
        val engine = FakeAudioEngine()
        val controller = DesktopSamplerController(engine, autosaveStore = null)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val loopPad = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            controller.selectPad(loopPad)
            controller.fillSelectedPadPattern(com.choplab.sampler.model.RepeatGrid.SIXTEENTH)
            controller.toggleTransport()
            awaitCondition { engine.triggered.isNotEmpty() }
            engine.triggered.clear()

            val handoffEntered = CountDownLatch(1)
            val releaseHandoff = CountDownLatch(1)
            engine.exclusiveRetireHook = {
                handoffEntered.countDown()
                check(releaseHandoff.await(2, TimeUnit.SECONDS))
            }
            val handoff = executor.submit { controller.toggleBeatLoopControl() }

            assertTrue(handoffEntered.await(2, TimeUnit.SECONDS))
            Thread.sleep(350L)
            releaseHandoff.countDown()
            handoff.get(2, TimeUnit.SECONDS)

            assertFalse(controller.state.value.transportPlaying)
            assertEquals(loopPad, controller.state.value.loopingPadIndex)
            assertEquals(listOf(loopPad to true), engine.triggered.map { it.first.globalIndex to it.second })
        } finally {
            executor.shutdownNow()
            controller.close()
        }
    }

    @Test
    fun h13CapturePadWhileStoppedAuditionsWithoutOverwritingTheAssignedChop() = withH13CaptureFixture { controller, engine, _ ->
        val before = controller.state.value
        assertFalse(before.sourcePlaying)
        assertEquals(16_000, before.pads[1].startFrame)
        assertEquals(32_000, before.pads[1].endFrame)

        controller.capturePad(1)

        assertEquals(16_000, controller.state.value.pads[1].startFrame)
        assertEquals(32_000, controller.state.value.pads[1].endFrame)
        assertTrue(before.pads == controller.state.value.pads, "Stopped PAD audition must preserve every assignment")
        assertEquals(before.canUndo, controller.state.value.canUndo)
        assertEquals(1, controller.state.value.selectedPad)
        assertEquals(1, engine.triggered.single().first.globalIndex)
    }

    @Test
    fun h13CapturePadWhileSourcePlaysStillCapturesTheObservedPosition() = withH13CaptureFixture { controller, engine, _ ->
        controller.playSourceFrom(24_000)
        assertTrue(controller.state.value.sourcePlaying)
        assertFalse(controller.state.value.pads[2].isAssigned)

        controller.capturePad(2)

        assertTrue(controller.state.value.pads[2].isAssigned)
        assertEquals(24_000, controller.state.value.pads[2].startFrame)
        // Existing live-chop contract: the last chop extends to the Source selection end.
        assertEquals(80_000, controller.state.value.pads[2].endFrame)
        assertTrue(controller.state.value.sourcePlaying)
        assertTrue(engine.triggered.isEmpty(), "Live capture must not start a PAD audition")
    }

    @Test
    fun h13CapturePadDuringRecordingNeitherEditsNorAuditions() = withH13CaptureFixture { controller, engine, recorder ->
        controller.toggleMicrophoneRecording()
        awaitCondition { recorder.isRecording && controller.state.value.recordingSession is RecordingSession.Active }
        val before = controller.state.value

        controller.capturePad(1)

        assertTrue(before.pads == controller.state.value.pads, "Recording must keep the assigned Chop unchanged")
        assertEquals(before.recordingSession, controller.state.value.recordingSession)
        assertEquals(before.canUndo, controller.state.value.canUndo)
        assertTrue(engine.triggered.isEmpty())
        assertEquals("録音をSTOPしてから編集してください", controller.state.value.statusMessage)
    }

    @Test
    fun h13CapturePadDuringPendingSourceImportNeitherEditsNorAuditions() = withH13CaptureFixture { controller, engine, _ ->
        val input = File.createTempFile("h13-pending-source-", ".wav", File(System.getProperty("java.io.tmpdir")))
        WavFileWriter(input, sampleRate = 8_000, channelCount = 1).use { writer ->
            writer.writePcm16(ShortArray(8_000), 8_000)
        }
        engine.blockNextLoad = true
        try {
            controller.loadWav(input)
            engine.awaitBlockedLoad()
            val before = controller.state.value
            assertTrue(before.isLoading)

            controller.capturePad(1)

            assertTrue(before.pads == controller.state.value.pads, "Pending import must preserve the current Chop")
            assertEquals(before.canUndo, controller.state.value.canUndo)
            assertTrue(engine.triggered.isEmpty())
            assertEquals("現在の処理が終わってから編集してください", controller.state.value.statusMessage)
        } finally {
            engine.releaseBlockedLoad()
            awaitCondition { !controller.state.value.isLoading }
            input.delete()
        }
    }

    @Test
    fun h13CapturePadWhileStoppedOnlySelectsAnUnassignedPad() = withH13CaptureFixture { controller, engine, _ ->
        val before = controller.state.value

        controller.capturePad(2)

        assertEquals(2, controller.state.value.selectedPad)
        assertFalse(controller.state.value.pads[2].isAssigned)
        assertTrue(before.pads == controller.state.value.pads)
        assertEquals(before.canUndo, controller.state.value.canUndo)
        assertTrue(engine.triggered.isEmpty())
    }

    @Test
    fun h13StoppedLoopCaptureUsesTheManagedLoopSession() = withH13CaptureFixture(
        targetPlayMode = PadPlayMode.LOOP,
    ) { controller, engine, _ ->
        controller.capturePad(1)

        assertEquals(1, controller.state.value.loopingPadIndex)
        assertEquals(listOf(1 to true), engine.triggered.map { it.first.globalIndex to it.second })
        assertEquals(1, engine.exclusiveStartCount)

        controller.releasePad(1)

        assertEquals(1, controller.state.value.loopingPadIndex)
    }

    @Test
    fun h13GateCaptureReleaseCannotCloseANewerKeyboardVoice() = withH13CaptureFixture(
        targetPlayMode = PadPlayMode.GATE,
    ) { controller, engine, _ ->
        val mouseOwnership = controller.capturePadWithOwnership(1)
        val keyboardOwnership = controller.triggerPadWithOwnership(1)

        controller.releasePadIfOwned(1, mouseOwnership)

        assertEquals(1L, mouseOwnership)
        assertEquals(2L, keyboardOwnership)
        assertTrue(engine.releasedPads.isEmpty(), "A capture release must not perform a broad GATE release")
        assertEquals(listOf(1 to 1L), engine.releasedOwnedPads)
    }

    @Test
    fun h13CaptureAuditionFailureIsReportedWithoutEscapingTheGesture() = withH13CaptureFixture { controller, engine, _ ->
        controller.selectPad(1)
        engine.failNextTrigger = true
        val before = controller.state.value

        controller.capturePad(1)

        assertEquals(1, controller.state.value.selectedPad)
        assertTrue(before.pads == controller.state.value.pads)
        assertEquals(before.canUndo, controller.state.value.canUndo)
        assertEquals("PAD 2を再生できませんでした: test output unavailable", controller.state.value.statusMessage)
    }

    @Test
    fun h13FailedGateCaptureReleaseDoesNotCloseALaterKeyboardVoice() = withH13CaptureFixture(
        targetPlayMode = PadPlayMode.GATE,
    ) { controller, engine, _ ->
        engine.failNextTrigger = true
        val failedOwnership = controller.capturePadWithOwnership(1)
        val keyboardOwnership = controller.triggerPadWithOwnership(1)

        controller.releasePadIfOwned(1, failedOwnership)

        assertEquals(PadTriggerOwnership.NONE, failedOwnership)
        assertEquals(1L, keyboardOwnership)
        assertTrue(engine.releasedPads.isEmpty())
        assertTrue(engine.releasedOwnedPads.isEmpty())
    }

    @Test
    fun h13CaptureAuditionFatalErrorStillEscapes() = withH13CaptureFixture { controller, engine, _ ->
        engine.nextTriggerFailure = AssertionError("test fatal capture error")

        val failure = assertFailsWith<AssertionError> {
            controller.capturePad(1)
        }

        assertEquals("test fatal capture error", failure.message)
    }

    @Test
    fun h13TrimPreviewOfLoopPadUsesOneBoundedOneShotVoice() = withH13CaptureFixture(
        targetPlayMode = PadPlayMode.LOOP,
    ) { controller, engine, _ ->
        controller.previewPad(1)

        assertEquals(null, controller.state.value.loopingPadIndex)
        assertEquals(0, engine.exclusiveStartCount)
        assertEquals(listOf(1 to false), engine.triggered.map { it.first.globalIndex to it.second })
        assertEquals(PadPlayMode.ONE_SHOT, engine.triggered.single().first.playMode)
    }

    @Test
    fun h13PostStartGateFailureStopsTheUnreturnedCandidate() = withH13CaptureFixture(
        targetPlayMode = PadPlayMode.GATE,
    ) { controller, engine, _ ->
        engine.failAfterNextTrigger = true

        val failedOwnership = controller.capturePadWithOwnership(1)
        controller.releasePadIfOwned(1, failedOwnership)

        assertEquals(PadTriggerOwnership.NONE, failedOwnership)
        assertEquals(listOf(1), engine.stoppedPads)
        assertTrue(engine.releasedPads.isEmpty())
        assertTrue(engine.releasedOwnedPads.isEmpty())
        assertEquals("PAD 2を再生できませんでした: test post-start retirement failure", controller.state.value.statusMessage)
    }

    private fun withH13CaptureFixture(
        targetPlayMode: PadPlayMode = PadPlayMode.ONE_SHOT,
        block: (DesktopSamplerController, FakeAudioEngine, FakeRecorder) -> Unit,
    ) {
        val directory = Files.createTempDirectory(File(System.getProperty("java.io.tmpdir")).toPath(), "h13-capture-").toFile()
        val audio = PcmAudio(name = "H13 synthetic source", samples = ShortArray(80_000) { 1_000 }, sampleRate = 8_000)
        val initial = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            selectedPad = 0,
            activeSteps = emptySet(),
            pads = List(SamplerConfig.PAD_COUNT) { index ->
                when (index) {
                    0 -> PadModel(0, audio, 8_000, 12_000)
                    1 -> PadModel(1, audio, 16_000, 32_000, playMode = targetPlayMode)
                    else -> PadModel(index)
                }
            },
        )
        val engine = FakeAudioEngine()
        val recorder = FakeRecorder()
        val controller = DesktopSamplerController(engine, microphone = recorder, systemAudio = FakeRecorder(), autosaveStore = null)
        try {
            val project = DesktopProjectFiles.save(File(directory, "input.choplab"), initial)
            controller.openProject(project)
            awaitCondition { !controller.state.value.isLoading }
            assertEquals(16_000, controller.state.value.pads[1].startFrame)
            assertEquals(32_000, controller.state.value.pads[1].endFrame)
            block(controller, engine, recorder)
        } finally {
            controller.close()
            directory.deleteRecursively()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Timed out waiting for asynchronous desktop state")
            Thread.sleep(20L)
        }
    }

    private fun awaitAutosaveBlockedOnStore() = awaitCondition {
        Thread.getAllStackTraces().keys.any { thread ->
            thread.name == "ChopLab-Windows-Autosave" && thread.state == Thread.State.BLOCKED
        }
    }

    private fun awaitThreadWaiting(thread: Thread) = awaitCondition { thread.state == Thread.State.WAITING }

    private class FakeAudioEngine : DesktopSamplerAudioEngine {
        private val loadEntered = CountDownLatch(1)
        private val loadRelease = CountDownLatch(1)
        override var isSourcePlaying: Boolean = false
        var sourcePosition: Int = 0
        val triggered = CopyOnWriteArrayList<Pair<PadModel, Boolean>>()
        val releasedPads = CopyOnWriteArrayList<Int>()
        val releasedOwnedPads = CopyOnWriteArrayList<Pair<Int, Long>>()
        val stoppedPads = mutableListOf<Int>()
        private var nextVoiceOwnership: Long = 0L
        var failNextTrigger: Boolean = false
        var failAfterNextTrigger: Boolean = false
        var failNextExclusiveStart: Boolean = false
        var failNextExclusiveRetire: Boolean = false
        var failTriggerPad: Int? = null
        var failNextStopPad: Boolean = false
        var failNextStopAll: Boolean = false
        var failClose: Boolean = false
        var nextTriggerFailure: Throwable? = null
        var exclusiveStartHook: (() -> Unit)? = null
        var exclusiveRetireHook: (() -> Unit)? = null
        @Volatile var exclusiveStartCount: Int = 0
        @Volatile var exclusiveRetireCount: Int = 0
        @Volatile var exclusiveAbandonCount: Int = 0
        @Volatile var failNextLoad: Boolean = false
        @Volatile var blockNextLoad: Boolean = false
        @Volatile var stopAllCalls: Int = 0
        @Volatile var stopAllCount: Int = 0
        @Volatile var closeCalls: Int = 0
        @Volatile var loadPcmCalls: Int = 0
        @Volatile var loadPcmCompletedCalls: Int = 0
        @Volatile var playFromCalls: Int = 0
        val loadedAudioNames = Collections.synchronizedList(mutableListOf<String>())
        val loadedPitchSemitones = Collections.synchronizedList(mutableListOf<Float>())
        override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) {
            loadPcmCalls++
            loadedAudioNames += audio.name
            loadedPitchSemitones += pitchSemitones
            if (failNextLoad) {
                failNextLoad = false
                error("test output unavailable")
            }
            if (blockNextLoad) {
                blockNextLoad = false
                loadEntered.countDown()
                check(loadRelease.await(2L, TimeUnit.SECONDS)) { "Timed out holding the asynchronous project load" }
            }
            loadPcmCompletedCalls++
        }
        fun awaitBlockedLoad() {
            check(loadEntered.await(2L, TimeUnit.SECONDS)) { "Timed out waiting for the asynchronous project load" }
        }
        fun releaseBlockedLoad() = loadRelease.countDown()
        override fun playFrom(frame: Int) {
            playFromCalls++
            sourcePosition = frame
            isSourcePlaying = true
        }
        override fun seekSource(frame: Int) { sourcePosition = frame }
        override fun sourceFramePosition(): Int = sourcePosition
        override fun padFramePosition(index: Int): Int? = null
        override fun stop() { isSourcePlaying = false }
        override fun triggerPad(pad: PadModel, forceLoop: Boolean): Long {
            nextTriggerFailure?.let { failure ->
                nextTriggerFailure = null
                throw failure
            }
            if (failNextTrigger) {
                failNextTrigger = false
                error("test output unavailable")
            }
            triggered += pad to forceLoop
            nextVoiceOwnership += 1L
            if (failAfterNextTrigger) {
                failAfterNextTrigger = false
                // Match JavaSoundWavPlayer's fail-closed post-start contract: the engine
                // abandons its candidate before surfacing the retirement failure.
                stoppedPads += pad.globalIndex
                error("test post-start retirement failure")
            }
            return nextVoiceOwnership
        }
        override fun prepareExclusiveLoopSession(
            loopPad: PadModel,
            companionPads: List<PadModel>,
        ): DesktopPreparedLoopSession {
            exclusiveStartCount++
            val candidates = listOf(loopPad to true) + companionPads.map { it to false }
            return DesktopPreparedLoopSession {
                exclusiveStartHook?.invoke()
                nextTriggerFailure?.let { failure ->
                    nextTriggerFailure = null
                    throw failure
                }
                if (failNextExclusiveStart) {
                    failNextExclusiveStart = false
                    throw DesktopLoopSessionStartupException(
                        IllegalStateException("test output unavailable"),
                    )
                }
                failTriggerPad?.let { failingPad ->
                    if (candidates.any { it.first.globalIndex == failingPad }) {
                        failTriggerPad = null
                        throw DesktopLoopSessionStartupException(
                            IllegalStateException("test companion output unavailable"),
                        )
                    }
                }
                candidates.forEach { (pad, forceLoop) ->
                    triggered += pad to forceLoop
                    nextVoiceOwnership += 1L
                }
                object : DesktopStartedLoopSession {
                    override fun retirePriorPlayback() {
                        exclusiveRetireHook?.invoke()
                        if (failNextExclusiveRetire) {
                            failNextExclusiveRetire = false
                            error("test loop retirement failure")
                        }
                        triggered.clear()
                        triggered.addAll(candidates)
                        exclusiveRetireCount++
                        isSourcePlaying = false
                    }

                    override fun abandonCandidates() {
                        triggered.clear()
                        exclusiveAbandonCount++
                    }
                }
            }
        }
        override fun releasePad(index: Int) {
            releasedPads += index
        }
        override fun releasePadIfOwned(index: Int, ownership: Long) {
            releasedOwnedPads += index to ownership
        }
        override fun stopPad(index: Int) {
            if (failNextStopPad) {
                failNextStopPad = false
                error("test stop unavailable")
            }
            stoppedPads += index
        }
        override fun stopAll() {
            isSourcePlaying = false
            stopAllCalls++
            stopAllCount++
            if (failNextStopAll) {
                failNextStopAll = false
                error("test stop-all unavailable")
            }
        }
        override fun close() {
            closeCalls++
            if (failClose) error("test close unavailable")
        }
    }

    private class FakeRecorder : DesktopAudioRecorder {
        private var output: File? = null
        val lastOutput: File?
            get() = output
        var failStop: Boolean = false
        var failClose: Boolean = false
        @Volatile var closeCalls: Int = 0
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
            closeCalls++
            if (failClose) error("test recorder close unavailable")
        }
    }
}
