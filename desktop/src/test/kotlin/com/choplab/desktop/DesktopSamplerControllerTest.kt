package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.desktop.persistence.DesktopProjectFiles
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.ScratchReturnTarget
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.selectedPadPage
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
import java.util.concurrent.TimeUnit
import com.choplab.sampler.ui.WorkflowStage
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
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

            requireNotNull(closeThread).join(2_000L)
            assertFalse(requireNotNull(closeThread).isAlive)
            assertEquals(replacement.name, store.load()?.currentAudio?.name)
        } finally {
            engine.releaseBlockedLoad()
            controller?.close()
            closeThread?.join(2_000L)
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

            assertFalse(controller.state.value.sourcePlaying)
            assertFalse(engine.isSourcePlaying)
            assertTrue(controller.state.value.statusMessage.contains("test output unavailable"))
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

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
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
        val stoppedPads = mutableListOf<Int>()
        var failNextTrigger: Boolean = false
        var failNextStopPad: Boolean = false
        var failNextStopAll: Boolean = false
        var failClose: Boolean = false
        @Volatile var failNextLoad: Boolean = false
        @Volatile var blockNextLoad: Boolean = false
        @Volatile var stopAllCalls: Int = 0
        @Volatile var closeCalls: Int = 0
        @Volatile var loadPcmCalls: Int = 0
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
            if (!blockNextLoad) return
            blockNextLoad = false
            loadEntered.countDown()
            check(loadRelease.await(2L, TimeUnit.SECONDS)) { "Timed out holding the asynchronous project load" }
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
        override fun triggerPad(pad: PadModel, forceLoop: Boolean) {
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
        override fun stopAll() {
            isSourcePlaying = false
            stopAllCalls++
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
