package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PreciseEditingBoundaryTest {
    @Test fun padConfirmationChecksDeadlineContentsRevisionAndSelectionThenClearsWithOneUndo() = runBlocking<Unit> {
        val h = Harness()
        fun confirmation() = PadClearConfirmation(h.studio.document.value.project.pads[0], h.studio.document.value.revision, TimeSource.Monotonic.markNow() + 5.seconds)
        try {
            val before = h.studio.document.value
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, confirmation().copy(expiresAt = TimeSource.Monotonic.markNow() - 1.seconds))))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, confirmation().copy(pad = before.project.pads[0].copy(gain = .1f)))))
            assertEquals(before, h.studio.document.value)
            val old = confirmation()
            assertTrue(h.studio.dispatch(Action.Edit(Intent.SetPad(before.project.pads[0].copy(reverse = true)))).accepted)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, old)))
            val selected = confirmation()
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(1)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, selected)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(0)))
            assertTrue(h.studio.dispatch(Action.New(before.project.copy(id = "other-project"))).accepted)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, selected)))
            val exact = h.studio.document.value
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, confirmation())))
            assertNull(h.studio.document.value.project.pads[0].assetHash)
            assertEquals(exact.revision + 1, h.studio.document.value.revision)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(exact.project, h.studio.document.value.project)
            assertContentEquals(h.original, h.assets.read(h.asset))
        } finally { h.close() }
    }

    @Test fun exactPositionsIgnoreGridPreserveSourceAndGainAndSurviveArchiveWithSamePlaybackFrame() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectClip("clip")))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.BEAT)))
            val max = ContinuousClipEdits.MAX_TIMELINE_FRAMES - 240
            for (frame in listOf(480L, 1L, max)) {
                val before = h.studio.document.value
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetClipPosition("clip", frame, before.revision)))
                val after = h.studio.document.value
                assertEquals(before.revision + 1, after.revision)
                assertEquals(before.project.clips.single().copy(timelineStartFrame = frame), after.project.clips.single())
                assertEquals(before.project.source, after.project.source)
                val port = FileProjectPort(h.assets, resolve = { h.archive })
                port.save(after.project, after.revision, Location("saved"))
                val reopened = port.open(Location("saved"))
                assertEquals(after.project, reopened)
                val program = ProgramCompiler(WavPcmPort(h.assets)).compile(reopened, PlaybackTarget.Arrangement(), after.revision)
                assertEquals(frame, assertNotNull(program.arrangement).clip(0).timelineStartFrame)
                assertEquals(.4f, program.arrangement!!.clip(0).gain)
                program.releasePreparation()
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.Undo))
                assertEquals(before.project, h.studio.document.value.project)
                assertContentEquals(h.original, h.assets.read(h.asset))
            }
        } finally { h.close() }
    }

    @Test fun invalidStaleUnselectedAndBusyExactActionsLeaveTheDocumentUnchanged() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectClip("clip")))
            val before = h.studio.document.value
            for (frame in listOf(-1L, ContinuousClipEdits.MAX_TIMELINE_FRAMES, Long.MAX_VALUE)) {
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetClipPosition("clip", frame, before.revision)))
                assertEquals(before, h.studio.document.value)
            }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectClip(null)))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetClipPosition("clip", 1, before.revision)))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectClip("clip")))
            assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("New revision"))).accepted)
            val changed = h.studio.document.value
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetClipPosition("clip", 1, before.revision)))
            h.saveGate = CompletableDeferred()
            assertTrue(h.studio.dispatch(Action.Save(Location("busy"))).accepted)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SetClipPosition("clip", 1, changed.revision)))
            val confirmation = PadClearConfirmation(changed.project.pads[0], changed.revision, TimeSource.Monotonic.markNow() + 5.seconds)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.ClearPad(0, confirmation)))
            assertEquals(changed, h.studio.document.value)
        } finally { h.saveGate?.complete(Unit); h.close() }
    }

    @Test fun parsingRetainsSingleFramePrecisionAndRejectsInvalidValues() {
        val max = ContinuousClipEdits.MAX_TIMELINE_FRAMES
        assertEquals(480L, ceParsePosition("0.010", true, max))
        assertEquals(1L, ceParsePosition("1", false, max))
        assertEquals(max, ceParsePosition("1800", true, max))
        for (frame in listOf(1L, 2L, 47999L, 80000001L)) assertEquals(frame, ceParsePosition(cePositionSeconds(frame), true, max))
        for (value in listOf("", "-1", "+1", "1e3", "1,000", "NaN", "1801", "0.0000001")) assertNull(ceParsePosition(value, true, max))
        assertNull(ceParsePosition("999999999999", false, max))
        assertNull(ceParsePosition("1.0", false, max))
    }

    private class Harness {
        val directory = Files.createTempDirectory("precise-edit-")
        val archive = directory.resolve("project.choplab")
        val assets = FileAssetStore(directory.resolve("assets"))
        val original = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(960) { .25f }) }.toByteArray()
        val asset = runBlocking {
            val file = directory.resolve("source.wav"); Files.write(file, original)
            WavImportPort(assets, resolve = { file }).import(Location("source"))
        }
        val initial = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, asset.frames)),
            pads = (0..127).map { if (it == 0) Pad(0, asset.hash, FrameRange(0, asset.frames)) else Pad(it) }.frozen(),
            tracks = frozenListOf(Track("track", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "track", asset.hash, FrameRange(48, 288), gain = .4f, timelineStartFrame = 24_000)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var saveGate: CompletableDeferred<Unit>? = null
        val studio = Studio(scope, Services(assets, object : ImportPort { override suspend fun import(location: Location) = asset }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) { saveGate?.await() }
            override suspend fun open(location: Location) = initial
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Unused")
        }, object : EnginePort {
            override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
            override suspend fun apply(command: EngineCommand) = true
            override fun snapshot() = TransportState(outputAttached = true)
        }), initial)
        val presenter = ContinuousEditorPresenter(studio, scope, object : ContinuousEditorPorts {
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location? = null
            override suspend fun chooseSave(): Location? = null
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset) = listOf(.25f)
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float) = true
        })
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel(); directory.toFile().deleteRecursively() }
    }
}
