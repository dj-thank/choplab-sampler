package com.choplab.core

import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudioTest {
    @Test fun lyricsOnlyEditsUndoAndRedoLeaveAudioRevisionAloneUntilTheNextAudioEdit() = runTest {
        val engine = Engine()
        val initial = Project(assets = frozenListOf(asset), pads = (0..127).map {
            if (it == 0) com.choplab.core.model.Pad(0, asset.hash, FrameRange(0, asset.frames)) else com.choplab.core.model.Pad(it)
        }.frozen())
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), initial,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
        val audioRevision = engine.snapshot().programRevision
        engine.commands.clear()
        repeat(3) { index ->
            assertTrue(studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(LyricLine("line", "line $index", 0, 960))))).accepted)
            assertNull(studio.work.value.jobId)
            assertNull(studio.work.value.preparationId)
        }
        assertTrue(studio.dispatch(Action.Undo).accepted)
        assertTrue(studio.dispatch(Action.Redo).accepted)
        assertEquals(5L, studio.document.value.revision)
        assertEquals(audioRevision, engine.snapshot().programRevision)
        assertTrue(engine.commands.isEmpty(), "Lyric timing must not replace a playing audio graph")
        val current = studio.document.value
        val stale = studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf()), expectedRevision = 0))
        assertFalse(stale.accepted)
        assertIs<Notice.StaleCompletion>(stale.notice)
        assertEquals(current, studio.document.value, "A late confirmed import cannot overwrite the new document")
        assertTrue(studio.dispatch(Action.Edit(Intent.SetPad(initial.pads[0].copy(gain = .5f)))).accepted)
        assertEquals(6L, studio.document.value.revision)
        assertEquals(6L, engine.snapshot().programRevision)
        assertEquals("line 2", studio.document.value.project.lyrics.single().text)
        studio.dispatch(Action.Close)
    }
    private val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 7, "sample.wav")
    private class MemoryAssets : AssetStore {
        override suspend fun containsVerified(asset: Asset) = true
        override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
        override suspend fun read(asset: Asset) = ByteArray(0)
    }
    private class Engine : EnginePort {
        val commands = mutableListOf<EngineCommand>()
        var deny = false
        var waitForever = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var ignorePrepareCancellation = false
        var failPrepare = false
        var cancelPrepare = false
        private var state = TransportState()
        private val compiler = ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset) = PcmAsset.fromInterleaved(FloatArray(asset.frames.toInt() * 2) { 0.2f })
        })
        override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram = prepare(project, PlaybackTarget.Pattern(patternId), revision)
        override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram {
            prepareGate?.let { gate -> if (ignorePrepareCancellation) withContext(NonCancellable) { gate.await() } else gate.await() }
            if (failPrepare) error("Decode failed")
            if (cancelPrepare) throw CancellationException("Prepare deadline")
            return compiler.compile(project, target, revision)
        }
        override suspend fun apply(command: EngineCommand): Boolean {
            if (waitForever) awaitCancellation()
            commands += command
            if (deny) return false
            state = state.copy(frame = state.frame + 1, programRevision = if (command is EngineCommand.SwapProgram) command.program.revision else state.programRevision,
                playing = if (command is EngineCommand.StartSequence) true else if (command is EngineCommand.Stop) false else state.playing)
            return true
        }
        override fun snapshot() = state
    }
    private class Projects : ProjectPort {
        var stored = Project(id = "opened")
        override suspend fun save(project: Project, revision: Long, location: Location) { stored = project }
        override suspend fun open(location: Location) = stored
    }
    private fun services(engine: Engine, importer: ImportPort, projects: ProjectPort = Projects()) = Services(MemoryAssets(), importer, projects, object : ExportPort {
        override suspend fun export(project: Project, patternId: String, request: ExportRequest) = ExportReceipt(request.frames.toLong() + request.tailFrames, 48_000, 2, request.bits)
    }, engine)

    @Test fun cancelledAndLateImportsCannotReplaceCurrentSource() = runTest {
        val pending = CompletableDeferred<Asset>()
        val studio = Studio(this, services(Engine(), object : ImportPort {
            override suspend fun import(location: Location): Asset = withContext(NonCancellable) { pending.await() }
        }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(studio.dispatch(Action.Import(Location("source"))).accepted)
        runCurrent()
        studio.dispatch(Action.CancelWork)
        studio.dispatch(Action.New(Project(id = "replacement")))
        pending.complete(asset)
        advanceUntilIdle()
        assertEquals("replacement", studio.document.value.project.id)
        assertNull(studio.document.value.project.source)
        assertNull(studio.work.value.jobId)
        studio.dispatch(Action.Close)
    }

    @Test fun revisionFenceRejectsAWorkerThatFinishedAfterAnEdit() = runTest {
        val pending = CompletableDeferred<Asset>()
        val studio = Studio(this, services(Engine(), object : ImportPort { override suspend fun import(location: Location) = pending.await() }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        studio.dispatch(Action.Import(Location("source")))
        studio.dispatch(Action.Edit(Intent.Rename("Edited during import")))
        pending.complete(asset); advanceUntilIdle()
        assertEquals("Edited during import", studio.document.value.project.title)
        assertNull(studio.document.value.project.source)
        assertEquals(1L, studio.document.value.revision)
        studio.dispatch(Action.Close)
    }

    @Test fun fakePortsProductionFlowAndSelectionStaySeparate() = runTest {
        val engine = Engine(); val projects = Projects()
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }, projects), preparationDispatcher = StandardTestDispatcher(testScheduler))
        studio.dispatch(Action.Import(Location("source"))); advanceUntilIdle()
        studio.dispatch(Action.Edit(Intent.EqualChop(2)))
        studio.dispatch(Action.Edit(Intent.AssignSlice(1, 16)))
        studio.dispatch(Action.Edit(Intent.SetNote("pattern-1", Note(0, 16), true)))
        val revision = studio.document.value.revision
        studio.dispatch(Action.SelectPad(16))
        assertEquals(revision, studio.document.value.revision)
        assertEquals(16, studio.selection.value.padId)
        assertTrue(studio.document.value.audiblePending) // A fake/detached engine is no audio device.
        studio.dispatch(Action.Save(Location("project"))); advanceUntilIdle()
        assertEquals(revision, studio.document.value.savedRevision)
        studio.dispatch(Action.New(Project(id = "empty")))
        studio.dispatch(Action.Open(Location("project"))); advanceUntilIdle()
        assertEquals(projects.stored, studio.document.value.project)
        studio.dispatch(Action.Export(ExportRequest(Location("out"), 960)))
        advanceUntilIdle()
        assertTrue(engine.commands.zipWithNext().all { (a, b) -> a.orderId < b.orderId && a.effectiveFrame <= b.effectiveFrame })
        studio.dispatch(Action.Close)
        assertFalse(studio.dispatch(Action.Edit(Intent.Rename("Closed"))).accepted)
    }

    @Test fun engineFailureAndTimeoutLeaveUndoUnconsumed() = runTest {
        val engine = Engine()
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        studio.dispatch(Action.Edit(Intent.Rename("Edited")))
        val before = studio.document.value
        engine.deny = true
        assertFalse(studio.dispatch(Action.Undo).accepted)
        assertEquals(before, studio.document.value)
        engine.deny = false; engine.waitForever = true
        assertFalse(studio.dispatch(Action.Undo).accepted)
        assertEquals(before, studio.document.value)
        engine.waitForever = false
        assertTrue(studio.dispatch(Action.Undo).accepted)
        assertEquals("Untitled", studio.document.value.project.title)
        studio.dispatch(Action.Close)
    }

    @Test fun waveformUsesIndependentChannelsAndEvictsToBudget() {
        val data = PcmAsset.fromInterleaved(floatArrayOf(0.5f, -0.8f, 0.9f, -0.2f, -0.4f, 0.3f))
        val cache = WaveformCache(32)
        val key = WaveformCache.Key("a".repeat(64), 2)
        val peaks = cache.build(key, data)
        assertEquals(0.5f, peaks.minimum(0, 0)); assertEquals(0.9f, peaks.maximum(0, 0))
        assertEquals(-0.8f, peaks.minimum(0, 1)); assertEquals(-0.2f, peaks.maximum(0, 1))
        cache.build(WaveformCache.Key("b".repeat(64), 3), data)
        assertNull(cache.get(key)); assertTrue(cache.residentBytes <= 32)
    }

    @Test fun aDocumentRescuedFromAnEarlierAppsFileOpensUnsavedAndSaysWhatWasRescued() = runTest {
        val rescued = Project(id = "rescued", title = "old beat", assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 7)),
            pads = (0..127).map { if (it == 0) com.choplab.core.model.Pad(0, asset.hash, FrameRange(0, 7), "sample.wav") else com.choplab.core.model.Pad(it) }.frozen())
        var earlierApp = true
        val projects = object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) = Unit
            override suspend fun open(location: Location): Project = error("Studio opens through openDocument")
            override suspend fun openDocument(location: Location) =
                if (earlierApp) OpenedProject(rescued, Notice.Rescued(audio = 1, unplaced = 0)) else OpenedProject(Project(id = "saved"))
        }
        val studio = Studio(this, services(Engine(), object : ImportPort { override suspend fun import(location: Location) = asset }, projects),
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        val notices = mutableListOf<Notice>()
        backgroundScope.launch { studio.notices.collect { notices += it } }
        studio.dispatch(Action.Edit(Intent.Rename("Before")))
        assertTrue(studio.dispatch(Action.Open(Location("old.choplab"))).accepted)
        advanceUntilIdle(); runCurrent() // The notice collector is background work.
        assertEquals(rescued, studio.document.value.project)
        // A new document: saving must choose a file; the earlier app's file never counts as where it was saved.
        assertNull(studio.document.value.savedRevision)
        assertEquals(listOf<Notice>(Notice.Rescued(1, 0)), notices)
        notices.clear(); earlierApp = false
        studio.dispatch(Action.Open(Location("saved.choplab"))); advanceUntilIdle(); runCurrent()
        assertEquals("saved", studio.document.value.project.id)
        assertEquals(studio.document.value.revision, studio.document.value.savedRevision)
        assertEquals(listOf<Notice>(Notice.Completed(Operation.OPEN)), notices)
        studio.dispatch(Action.Close)
    }

    @Test fun cancellingAnEditBeingPreparedPostsOneNotice() = runTest {
        val engine = Engine().apply { prepareGate = CompletableDeferred() }
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        val notices = mutableListOf<Notice>()
        backgroundScope.launch { studio.notices.collect { notices += it } }
        val editing = async { studio.dispatch(Action.Edit(Intent.Rename("Cancelled"))) }
        runCurrent()
        assertNotNull(studio.work.value.preparationId)
        assertTrue(studio.dispatch(Action.CancelWork).accepted)
        // The one waiting for the edit is told, and the notice flow says it once: a copy arriving late must not repeat it.
        assertEquals(Notice.Cancelled(Operation.EDIT), editing.await().notice)
        runCurrent()
        assertEquals(listOf<Notice>(Notice.Cancelled(Operation.EDIT)), notices)
        engine.prepareGate?.complete(Unit); advanceUntilIdle()
        studio.dispatch(Action.Close)
    }

    @Test fun suspendedPrepareDoesNotBlockStopOrCancelAndCannotCommitLate() = runTest {
        val engine = Engine().apply { prepareGate = CompletableDeferred(); ignorePrepareCancellation = true }
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        val firstGate = engine.prepareGate!!
        val editing = async { studio.dispatch(Action.Edit(Intent.Rename("Must not commit"))) }
        runCurrent()
        assertNotNull(studio.work.value.preparationId)
        assertTrue(withTimeout(100) { studio.dispatch(Action.Stop) }.accepted)
        assertFalse(editing.await().accepted)
        assertTrue(engine.commands.last() is EngineCommand.Stop)
        firstGate.complete(Unit); advanceUntilIdle()
        assertEquals("Untitled", studio.document.value.project.title)
        assertEquals(0L, studio.document.value.revision)
        assertFalse(studio.document.value.canUndo)

        val secondGate = CompletableDeferred<Unit>(); engine.prepareGate = secondGate
        val cancelled = async { studio.dispatch(Action.Edit(Intent.Rename("Also cancelled"))) }
        runCurrent()
        assertTrue(withTimeout(100) { studio.dispatch(Action.CancelWork) }.accepted)
        assertFalse(cancelled.await().accepted)
        secondGate.complete(Unit); advanceUntilIdle()
        assertEquals("Untitled", studio.document.value.project.title)
        assertTrue(engine.commands.none { it is EngineCommand.SwapProgram })
        engine.prepareGate = null
        studio.dispatch(Action.Close)
    }

    @Test fun silenceStopsTheSoundButLetsPreparingWorkFinish() = runTest {
        val engine = Engine().apply { prepareGate = CompletableDeferred() }
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        // An edit is waiting for its Program when the host goes quiet (hidden, audio focus lost).
        val gate = engine.prepareGate!!
        val editing = async { studio.dispatch(Action.Edit(Intent.Rename("Kept"))) }
        runCurrent()
        assertNotNull(studio.work.value.preparationId)
        assertTrue(withTimeout(100) { studio.dispatch(Action.Silence) }.accepted)
        assertTrue(engine.commands.last() is EngineCommand.Stop)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(editing.await().accepted)
        assertEquals("Kept", studio.document.value.project.title)

        // An import whose Program is still being prepared is kept as well.
        val importing = CompletableDeferred<Unit>(); engine.prepareGate = importing
        assertTrue(studio.dispatch(Action.Import(Location("picked"))).accepted)
        runCurrent()
        assertNotNull(studio.work.value.preparationId, "The import reached its preparation")
        assertTrue(withTimeout(100) { studio.dispatch(Action.Silence) }.accepted)
        importing.complete(Unit); advanceUntilIdle()
        assertEquals(asset.hash, studio.document.value.project.source?.assetHash)
        assertEquals(WorkState(), studio.work.value)
        engine.prepareGate = null
        studio.dispatch(Action.Close)
    }

    @Test fun aScratchedPadReachesTheEngineAndAnEmptyOneIsRefused() = runTest {
        val engine = Engine()
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }),
            Project(assets = frozenListOf(asset), pads = (0..127).map { if (it == 3) com.choplab.core.model.Pad(3, asset.hash, FrameRange(0, 7)) else com.choplab.core.model.Pad(it) }.frozen()),
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(studio.dispatch(Action.ScratchStart(4, 0.0)).accepted, "PAD 5 holds no sound")
        assertTrue(engine.commands.none { it is EngineCommand.ScratchStart })
        for (action in listOf(Action.ScratchStart(3, 2.0), Action.ScratchMove(5.5, 480), Action.ScratchCut(0f), Action.ScratchEnd)) {
            assertTrue(studio.dispatch(action).accepted, "$action")
        }
        val sent = engine.commands.takeLast(4)
        assertEquals(3, (sent[0] as EngineCommand.ScratchStart).padId)
        assertEquals(2.0, (sent[0] as EngineCommand.ScratchStart).sourceFrame)
        assertEquals(5.5, (sent[1] as EngineCommand.ScratchPosition).sourceFrame)
        assertEquals(480, (sent[1] as EngineCommand.ScratchPosition).durationFrames)
        assertEquals(0f, (sent[2] as EngineCommand.ScratchCut).gain)
        assertIs<EngineCommand.ScratchEnd>(sent[3])
        // Increasing order, like every other command Studio sends.
        assertEquals(engine.commands.map { it.orderId }.sorted(), engine.commands.map { it.orderId })
        // Once the engine has ended the scratch, the hand's moves are refused without a notice; a refused hold is told.
        val notices = mutableListOf<Notice>()
        backgroundScope.launch { studio.notices.collect { notices += it } }
        runCurrent()
        engine.deny = true
        assertFalse(studio.dispatch(Action.ScratchMove(6.0, 480)).accepted)
        assertFalse(studio.dispatch(Action.ScratchCut(1f)).accepted)
        runCurrent()
        assertTrue(notices.isEmpty(), "$notices")
        assertFalse(studio.dispatch(Action.ScratchStart(3, 0.0)).accepted)
        runCurrent()
        assertEquals(listOf<Notice>(Notice.Rejected(Rejection.ENGINE_REFUSED)), notices)
        engine.deny = false
        studio.dispatch(Action.Close)
    }

    @Test fun replacementAndCallerCancellationFencePendingEdits() = runTest {
        val engine = Engine().apply { prepareGate = CompletableDeferred(); ignorePrepareCancellation = true }
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        val gate = engine.prepareGate!!
        val old = async { studio.dispatch(Action.Edit(Intent.Rename("Old edit"))) }
        runCurrent()
        engine.prepareGate = null
        assertTrue(studio.dispatch(Action.New(Project(id = "replacement"))).accepted)
        assertFalse(old.await().accepted)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("replacement", studio.document.value.project.id)
        assertEquals("Untitled", studio.document.value.project.title)

        val cancelledGate = CompletableDeferred<Unit>(); engine.prepareGate = cancelledGate
        val caller = launch { studio.dispatch(Action.Edit(Intent.Rename("Abandoned caller"))) }
        runCurrent(); caller.cancelAndJoin(); runCurrent()
        cancelledGate.complete(Unit); advanceUntilIdle()
        assertEquals("Untitled", studio.document.value.project.title)
        engine.prepareGate = null
        studio.dispatch(Action.Close)
    }

    @Test fun prepareFailureDoesNotConsumeUndoOrLeaveWorkBusy() = runTest {
        val engine = Engine()
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(studio.dispatch(Action.Edit(Intent.Rename("Edited"))).accepted)
        val before = studio.document.value
        engine.failPrepare = true
        assertFalse(studio.dispatch(Action.Undo).accepted)
        assertEquals(before, studio.document.value)
        assertNull(studio.work.value.jobId); assertNull(studio.work.value.preparationId)
        engine.failPrepare = false
        engine.cancelPrepare = true
        assertFalse(studio.dispatch(Action.Undo).accepted)
        assertEquals(before, studio.document.value)
        assertNull(studio.work.value.preparationId)
        engine.cancelPrepare = false
        assertTrue(studio.dispatch(Action.Undo).accepted)
        studio.dispatch(Action.Close)
    }

    @Test fun invalidImportedDocumentIsRejectedWithoutLeavingBusyState() = runTest {
        val invalid = asset.copy(role = AssetRole.PCM_CACHE, required = false, derivedFrom = "b".repeat(64))
        val studio = Studio(this, services(Engine(), object : ImportPort { override suspend fun import(location: Location) = invalid }), preparationDispatcher = StandardTestDispatcher(testScheduler))
        studio.dispatch(Action.Import(Location("bad"))); advanceUntilIdle()
        assertNull(studio.work.value.jobId)
        assertNull(studio.document.value.project.source)
        assertTrue(studio.dispatch(Action.Edit(Intent.Rename("Still editable"))).accepted)
        studio.dispatch(Action.Close)
    }

    @Test fun explicitArrangementSurvivesEditsUndoAndNewWithoutImplicitPatternPlayback() = runTest {
        val engine = Engine()
        val track = Track("t", "Source", TrackKind.SOURCE)
        val clip = Clip("c", "t", asset.hash, FrameRange(0, asset.frames), timelineStartFrame = 17)
        val initial = Project(assets = frozenListOf(asset), tracks = frozenListOf(track), clips = frozenListOf(clip))
        val studio = Studio(this, services(engine, object : ImportPort { override suspend fun import(location: Location) = asset }), initial, preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertIs<PlaybackTarget.Pattern>(studio.selection.value.playbackTarget)
        val recording = PlaybackTarget.Arrangement(minimumFrames = 48_000)
        assertTrue(studio.dispatch(Action.SelectPlaybackTarget(recording)).accepted)
        assertEquals(0L, studio.document.value.revision)
        assertTrue(studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(track), frozenListOf(clip.copy(timelineStartFrame = 65)), frozenListOf()))).accepted)
        assertEquals(recording, studio.selection.value.playbackTarget)
        assertTrue(studio.dispatch(Action.Undo).accepted)
        assertEquals(17L, studio.document.value.project.clips.single().timelineStartFrame)
        val restored = engine.commands.filterIsInstance<EngineCommand.SwapProgram>().last().program
        assertNull(restored.pattern); assertEquals(17L, restored.arrangement!!.clip(0).timelineStartFrame)
        assertEquals(48_000L, restored.arrangement!!.durationFrames, "Edits and Undo retain the live recording clock")
        studio.dispatch(Action.Pause); studio.dispatch(Action.Seek(3)); studio.dispatch(Action.Resume)
        assertTrue(engine.commands.takeLast(3)[0] is EngineCommand.Pause)
        assertTrue(engine.commands.takeLast(3)[1] is EngineCommand.Seek)
        assertTrue(engine.commands.takeLast(3)[2] is EngineCommand.Resume)
        studio.dispatch(Action.New(Project(id = "blank")))
        val blank = engine.commands.filterIsInstance<EngineCommand.SwapProgram>().last().program
        assertNull(blank.pattern); assertEquals(0, blank.arrangement!!.clipCount)
        assertEquals(0L, blank.arrangement!!.durationFrames, "A new project must discard the previous runtime clock")
        assertEquals(PlaybackTarget.Arrangement(), studio.selection.value.playbackTarget)
        studio.dispatch(Action.SelectPattern("pattern-1"))
        assertIs<PlaybackTarget.Pattern>(studio.selection.value.playbackTarget)
        studio.dispatch(Action.Close)
    }
}
