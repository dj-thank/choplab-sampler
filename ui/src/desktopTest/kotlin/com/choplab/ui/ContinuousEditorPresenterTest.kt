package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.PlayMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Presenter/Studio contracts with fake platform ports; not physical audio evidence. */
class ContinuousEditorPresenterTest {
    @Test fun explicitLoopSwitchReplacesThePreviousLoopWithoutChangingTheOriginal() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            val p = h.studio.document.value.project
            assertEquals(PlayMode.ONE_SHOT, p.pads[0].mode)
            assertEquals(PlayMode.LOOP, p.pads[1].mode)
            assertEquals(h.original.hash, p.source?.assetHash)
            val release = h.engine.commands.indexOfLast { it is EngineCommand.Release && it.padId == 0 }
            val trigger = h.engine.commands.indexOfLast { it is EngineCommand.Trigger && it.padId == 1 }
            assertTrue(release >= 0 && trigger > release)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.TogglePadLoop(1)))
            assertEquals(PlayMode.ONE_SHOT, h.studio.document.value.project.pads[1].mode)
        } finally { h.close() }
    }
    @Test fun originalSurvivesPadSelectionAndStageChangesWithoutAutomaticPlacement() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlayOriginal))
            h.ports.originalFrame = 1234
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(1)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            val state = withTimeout(2000) { h.presenter.state.first { it.stage == ContinuousStage.BEAT && it.selectedPadId == 1 } }
            assertEquals(h.original.hash, state.original?.id)
            assertTrue(state.originalPlaying)
            assertEquals(1234L, h.presenter.readout().originalFrame)
            assertEquals(0, h.ports.stops)
            assertTrue(h.studio.document.value.project.clips.isEmpty())
        } finally { h.close() }
    }

    @Test fun monitoringNeverChangesProjectAndArrangementExportIsExplicit() = runBlocking {
        val h = Harness()
        try {
            val initial = h.studio.document.value.project
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetSongMonitorGain(0f)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetOriginalMonitorGain(.4f)))
            assertEquals(initial, h.studio.document.value.project)
            assertEquals(0f, h.ports.songGain)
            assertEquals(.4f, h.ports.originalGain)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 73)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ExportWav))
            withTimeout(2000) { while (h.exportTarget == null) delay(5) }
            assertIs<PlaybackTarget.Arrangement>(h.exportTarget)
            assertEquals(48_073L, h.ports.exportFrames)
        } finally { h.close() }
    }

    @Test fun heldGateIsReleasedOnNavigationAndClipEditUndoRetainsSource() = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            assertTrue(h.engine.commands.any { it is EngineCommand.Release && it.padId == 0 })
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 913)))
            assertEquals(913L, h.studio.document.value.project.clips.single().timelineStartFrame)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertTrue(h.studio.document.value.project.clips.isEmpty())
            assertEquals(h.original.hash, h.studio.document.value.project.source?.assetHash)
        } finally { h.close() }
    }

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val original = Asset("a".repeat(64), "wav", 100, 48_000, 2, 96_000, "Original")
        val chopped = Asset("b".repeat(64), "wav", 100, 48_000, 2, 48_000, "Chop")
        val initial = Project(assets = frozenListOf(original, chopped), source = Source(original.hash, FrameRange(0, 96_000)),
            pads = (0..127).map { if (it < 2) Pad(it, chopped.hash, FrameRange(0, 48_000), mode = PlayMode.GATE) else Pad(it) }.frozen())
        val engine = FakeEngine()
        @Volatile var exportTarget: PlaybackTarget? = null
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = original },
            object : ProjectPort {
                override suspend fun save(project: Project, revision: Long, location: Location) = Unit
                override suspend fun open(location: Location) = initial
            }, object : ExportPort {
                override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Must select arrangement explicitly")
                override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt {
                    exportTarget = target
                    return ExportReceipt(request.frames.toLong(), 48_000, 2, request.bits)
                }
            }, engine), initial)
        val ports = FakePorts()
        val presenter = ContinuousEditorPresenter(studio, scope, ports)
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
    private class FakeEngine : EnginePort {
        val commands = java.util.concurrent.CopyOnWriteArrayList<EngineCommand>()
        override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
        override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long) = EngineProgram(revision = revision)
        override suspend fun apply(command: EngineCommand): Boolean { commands += command; return true }
        override fun snapshot() = TransportState(outputAttached = true)
    }
    private class FakePorts : ContinuousEditorPorts {
        var originalFrame = 0L
        var stops = 0
        var songGain = 1f
        var originalGain = 1f
        var exportFrames = 0L
        override val originalAvailable = true
        override suspend fun chooseAudio(): Location? = null
        override suspend fun chooseOpen(): Location? = null
        override suspend fun chooseSave() = Location("save")
        override suspend fun chooseExport(frames: Long): ExportRequest {
            exportFrames = frames
            return ExportRequest(Location("export"), frames.toInt())
        }
        override suspend fun peaks(asset: Asset) = listOf(.2f, .4f)
        override fun readout() = ContinuousEditorReadout(originalFrame = originalFrame)
        override suspend fun setSongMonitorGain(gain: Float): Boolean { songGain = gain; return true }
        override suspend fun setOriginalMonitorGain(gain: Float): Boolean { originalGain = gain; return true }
        override suspend fun playOriginal(asset: Asset) = true
        override suspend fun stopOriginal(): Boolean { stops++; return true }
    }
}
