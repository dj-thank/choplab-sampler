package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.selectedPadPage
import com.choplab.sampler.persistence.AtomicProjectStore
import java.nio.file.Files
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import com.choplab.sampler.ui.WorkflowStage
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

    private class FakeAudioEngine : DesktopSamplerAudioEngine {
        override var isSourcePlaying: Boolean = false
        var sourcePosition: Int = 0
        val triggered = CopyOnWriteArrayList<Pair<PadModel, Boolean>>()
        val stoppedPads = mutableListOf<Int>()
        var failNextTrigger: Boolean = false
        var failNextStopPad: Boolean = false
        override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) = Unit
        override fun playFrom(frame: Int) { sourcePosition = frame; isSourcePlaying = true }
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
