package com.choplab.ui.source

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OnlineSourcePresenterTest {
    @Test fun savedReceiptIsRefusedAfterDocumentChangeWithoutAnotherImportOrUndo() = runBlocking<Unit> {
        val h = Harness()
        try {
            val controller = h.saved()
            // A lyric-only revision changes the document without a transient audio-preparation BUSY state.
            assertTrue(h.studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(LyricLine("line", "Changed", 0, 960))))).accepted)
            val before = h.studio.document.value
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
            eventually { controller.state.value.issue == OnlineProblem.STALE_DOCUMENT }
            assertEquals(0, h.imports.get()); assertEquals(before, h.studio.document.value)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CloseOnline))
            assertNull(h.presenter.onlineSource.value); assertEquals(1, h.closes.get())
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertFalse(h.studio.document.value.canUndo)
        } finally { h.close() }
    }

    @Test fun permissionOpeningAndEveryRecordingKindRejectUseWithoutWaitingForTheMicrophone() = runBlocking<Unit> {
        for (action in listOf(ContinuousEditorAction.RecordSource, ContinuousEditorAction.RecordVoice, ContinuousEditorAction.RecordHits)) {
            val h = Harness()
            val permission = CompletableDeferred<Unit>()
            try {
                val controller = h.saved()
                if (action != ContinuousEditorAction.RecordHits) h.permission = permission
                val starting = async { h.presenter.dispatch(action) }
                eventually { if (action == ContinuousEditorAction.RecordHits) h.presenter.state.value.recordingHits
                    else h.permissionRequested.isCompleted }
                val before = h.studio.document.value
                val attempted = controller.dispatch(OnlineSourceAction.UseOriginal)
                if (attempted) eventually { controller.state.value.issue == OnlineProblem.RECORDING }
                assertEquals(0, h.imports.get()); assertEquals(before, h.studio.document.value)
                permission.complete(Unit); assertTrue(starting.await())
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.ImportOnline))
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.CloseOnline))
                assertEquals(1, h.closes.get())
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopAll))
            } finally { permission.complete(Unit); h.close() }
        }
    }

    @Test fun stopCancelsPreparingImportWithoutWaitingForLateDecoderAndStageCloseReleasesOnce() = runBlocking<Unit> {
        val h = Harness()
        val decoder = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        try {
            val controller = h.saved()
            val before = h.studio.document.value
            h.prepare = { entered.complete(Unit); withContext(NonCancellable) { decoder.await() } }
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
            withTimeout(5_000) { entered.await() }
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.CloseOnline))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            val stops = h.commands.count { it is EngineCommand.Stop }
            controller.stopAll()
            eventually { h.commands.count { it is EngineCommand.Stop } > stops }
            assertFalse(decoder.isCompleted, "Output stop must precede a noncooperative decoder result")
            eventually { !controller.state.value.applying }
            assertEquals(OnlineProblem.CANCELLED, controller.state.value.issue)
            assertEquals(before, h.studio.document.value)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            assertNull(h.presenter.onlineSource.value); assertEquals(1, h.closes.get())
            decoder.complete(Unit)
            h.studio.dispatch(Action.RefreshTransport)
            assertEquals(before, h.studio.document.value)
            controller.close(); assertEquals(1, h.closes.get())
        } finally { decoder.complete(Unit); h.close() }
    }

    @Test fun hostShutdownDropsLateImporterAndClosesItsProviderExactlyOnce() = runBlocking<Unit> {
        val h = Harness()
        val imported = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        try {
            val controller = h.saved()
            val before = h.studio.document.value
            h.importing = { entered.complete(Unit); withContext(NonCancellable) { imported.await() } }
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
            withTimeout(5_000) { entered.await() }
            h.presenter.close()
            eventually { h.studio.work.value.jobId == null }
            assertEquals(1, h.closes.get()); assertTrue(controller.state.value.closed)
            imported.complete(Unit)
            h.studio.dispatch(Action.RefreshTransport)
            assertEquals(before, h.studio.document.value)
        } finally { imported.complete(Unit); h.close() }
    }

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 48_000, "Source")
        val importedAsset = asset.copy(hash = "b".repeat(64), name = "Online source")
        val commands = CopyOnWriteArrayList<EngineCommand>()
        val imports = AtomicInteger(); val closes = AtomicInteger()
        @Volatile var prepare: (suspend () -> Unit)? = null
        @Volatile var importing: (suspend () -> Unit)? = null
        @Volatile var permission: CompletableDeferred<Unit>? = null
        val permissionRequested = CompletableDeferred<Unit>()
        val engine = object : EnginePort {
            override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
            override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram {
                prepare?.let { prepare = null; it() }; return EngineProgram(revision = revision)
            }
            override suspend fun apply(command: EngineCommand): Boolean { commands += command; return true }
            override fun snapshot() = TransportState(outputAttached = true)
        }
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) {}
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort {
            override suspend fun import(location: Location): Asset { imports.incrementAndGet(); importing?.invoke(); return importedAsset }
        }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) {}
            override suspend fun open(location: Location) = Project()
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest) = error("Unused")
        }, engine), Project(assets = frozenListOf(asset), pads = (0..127).map {
            if (it == 0) Pad(it, asset.hash, FrameRange(0, 48_000)) else Pad(it)
        }.frozen()))
        private val candidate = OnlineCandidate("candidate", "Title", "Uploader", 1.0,
            formats = listOf(OnlineAudioFormat("raw", "wav", null, null, null, null, false, null, null, null, null, null)))
        val ports = object : ContinuousEditorPorts {
            override val onlineSource = OnlineSourceHost { _, stop ->
                val port = object : OnlineSourcePort {
                    override val state = MutableStateFlow(OnlineWorkerState())
                    override fun search(query: String, catalog: OnlineCatalog): Boolean {
                        state.value = OnlineWorkerState(OnlinePhase.CANDIDATES, candidates = listOf(candidate)); return true
                    }
                    override fun inspect(id: String): Boolean { state.value = state.value.copy(phase = OnlinePhase.DETAILS, details = candidate); return true }
                    override fun selectFormat(id: String): Boolean { state.value = state.value.copy(details = candidate.copy(selectedFormat = id)); return true }
                    override fun save(id: String): Boolean { state.value = state.value.copy(phase = OnlinePhase.SAVED, saved = OnlineSaved("saved", "Title")); return true }
                    override fun cancel() {}
                    override fun stopAll() = stop()
                    override fun close() { closes.incrementAndGet() }
                }
                OnlineImportSession(port) { OnlineImportSelection(Location("online"), importedAsset.hash) }
            }
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location? = null
            override suspend fun chooseSave(): Location? = null
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset) = emptyList<Float>()
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float) = true
            override val voiceAvailable = true
            override val padRenderAvailable = true
            override suspend fun startVoice(maxSeconds: Int): VoiceStart { permissionRequested.complete(Unit); permission?.await(); return VoiceStart.STARTED }
        }
        val presenter = ContinuousEditorPresenter(studio, scope, ports)
        suspend fun saved(): OnlineSourceController {
            assertTrue(presenter.dispatch(ContinuousEditorAction.ImportOnline))
            val controller = requireNotNull(presenter.onlineSource.value)
            assertTrue(controller.dispatch(OnlineSourceAction.Query("query")))
            assertTrue(controller.dispatch(OnlineSourceAction.Search))
            assertTrue(controller.dispatch(OnlineSourceAction.Inspect(candidate.id)))
            assertTrue(controller.dispatch(OnlineSourceAction.Format("raw")))
            assertTrue(controller.dispatch(OnlineSourceAction.Save))
            eventually { controller.state.value.canUse }
            return controller
        }
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
    companion object {
        private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(2) }
    }
}
