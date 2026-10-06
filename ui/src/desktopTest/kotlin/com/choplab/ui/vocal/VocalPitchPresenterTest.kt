package com.choplab.ui.vocal

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.analysis.*
import com.choplab.core.model.*
import com.choplab.core.vocal.VocalPitchDraft
import com.choplab.core.vocal.*
import com.choplab.engine.*
import com.choplab.ui.*
import com.choplab.ui.analysis.*
import com.choplab.ui.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class VocalPitchPresenterTest {
    @Test fun pitchTransfersTheOneModalWithTakesPracticeAndOnlineWithoutEditing() = runBlocking {
        val f = BoundaryFixture()
        try {
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalTakes))
            assertNotNull(f.presenter.vocalTakes.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertNull(f.presenter.vocalTakes.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            assertNull(f.presenter.vocalPitch.value)
            val practice = assertNotNull(f.presenter.vocalPractice.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertEquals(PracticePhase.CLOSED, practice.state.value.phase)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.ImportOnline))
            assertNull(f.presenter.vocalPitch.value)
            assertNotNull(f.presenter.onlineSource.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertNull(f.presenter.onlineSource.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            assertNull(f.presenter.vocalPitch.value)
            val analysis = assertNotNull(f.presenter.sourceAnalysis.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertNull(f.presenter.sourceAnalysis.value)
            assertEquals(SourceAnalysisPhase.CLOSED, analysis.state.value.phase)
            assertEquals(f.initial, f.studio.document.value.project)
            assertFalse(f.studio.document.value.canUndo)
        } finally { f.close() }
    }

    @Test fun closingOrSwitchingCannotCancelAnOwnedPitchCommit() = runBlocking {
        val f = BoundaryFixture(blockApply = true)
        try {
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            val editor = assertNotNull(f.presenter.vocalPitch.value)
            val applying = async { editor.dispatch(PitchAction.Apply) }
            withTimeout(5000) { f.applyEntered.await() }
            assertEquals(PitchEditorPhase.APPLYING, editor.state.value.phase)
            for (action in listOf(ContinuousEditorAction.CloseVocalPitch, ContinuousEditorAction.OpenVocalTakes,
                ContinuousEditorAction.OpenVocalPractice, ContinuousEditorAction.ImportOnline,
                ContinuousEditorAction.OpenSourceAnalysis, ContinuousEditorAction.RecordVoice,
                ContinuousEditorAction.RecordHits, ContinuousEditorAction.RecordSource,
                ContinuousEditorAction.RecordSystemSource,
                ContinuousEditorAction.Navigate(ContinuousStage.SAVE))) {
                assertFalse(withTimeout(1000) { f.presenter.dispatch(action) }, action.toString())
                assertSame(editor, f.presenter.vocalPitch.value)
                assertEquals(PitchEditorPhase.APPLYING, editor.state.value.phase)
            }
            f.releaseApply.complete(Unit)
            assertTrue(withTimeout(5000) { applying.await() })
            assertEquals(1L, f.studio.document.value.revision)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(f.initial, f.studio.document.value.project)
        } finally { f.close() }
    }

    @Test fun pitchClosesAnalysisAndFencesItsLateResultWithoutEditing() = runBlocking {
        val f = BoundaryFixture(blockAnalysis = true)
        try {
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            val analysis = assertNotNull(f.presenter.sourceAnalysis.value)
            val analysing = async { analysis.dispatch(SourceAnalysisAction.Analyse) }
            withTimeout(5000) { f.analysisEntered.await() }
            assertTrue(withTimeout(1000) { f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch) })
            assertNull(f.presenter.sourceAnalysis.value)
            assertEquals(SourceAnalysisPhase.CLOSED, analysis.state.value.phase)
            val pitch = assertNotNull(f.presenter.vocalPitch.value)
            f.releaseAnalysis.complete(Unit)
            assertFalse(withTimeout(5000) { analysing.await() })
            assertNull(analysis.state.value.result)
            assertSame(pitch, f.presenter.vocalPitch.value)
            assertEquals(f.initial, f.studio.document.value.project)
            assertFalse(f.studio.document.value.canUndo)
        } finally { f.close() }
    }

    @Test fun applyingAnalysisRefusesPitchOutsideTheEditLockAndKeepsItsSingleUndo() = runBlocking {
        val f = BoundaryFixture(blockMonitor = true)
        try {
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            val analysis = assertNotNull(f.presenter.sourceAnalysis.value)
            assertTrue(analysis.dispatch(SourceAnalysisAction.Analyse))
            assertTrue(analysis.dispatch(SourceAnalysisAction.SelectTempo(98_000)))
            val holding = async { f.presenter.dispatch(ContinuousEditorAction.SetSongMonitorGain(.5f)) }
            withTimeout(5000) { f.monitorEntered.await() }
            val applying = async { analysis.dispatch(SourceAnalysisAction.Apply) }
            withTimeout(5000) { analysis.state.first { it.phase == SourceAnalysisPhase.APPLYING } }
            assertFalse(withTimeout(1000) { f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch) })
            assertSame(analysis, f.presenter.sourceAnalysis.value)
            assertNull(f.presenter.vocalPitch.value)
            assertEquals(f.initial, f.studio.document.value.project)
            f.releaseMonitor.complete(Unit)
            assertTrue(holding.await()); assertTrue(applying.await())
            assertEquals(1L, f.studio.document.value.revision)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertNull(f.presenter.sourceAnalysis.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(f.initial, f.studio.document.value.project)
            assertFalse(f.studio.document.value.canUndo)
        } finally { f.close() }
    }

    @Test fun actualStudioSaveWorkDisablesEntryAndRejectsALateCorrectionWithoutConsumingUndo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Asset("a".repeat(64), "wav", 48_044, 48_000, 2, 6000, "Original")
        val initial = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", source.hash, FrameRange(0, source.frames))))
        val saveEntered = CompletableDeferred<Unit>(); val finishSave = CompletableDeferred<Unit>()
        val renderEntered = CompletableDeferred<Unit>(); val finishRender = CompletableDeferred<Unit>()
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = source }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) { saveEntered.complete(Unit); finishSave.await() }
            override suspend fun open(location: Location) = initial
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Not an export")
        }, object : EnginePort {
            override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
            override suspend fun apply(command: EngineCommand) = true
            override fun snapshot() = TransportState(outputAttached = true)
        }), initial)
        val preview = object : VocalPreviewPort {
            override val state = MutableStateFlow(VocalPreviewState())
            override suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit> = error("No audition requested")
            override suspend fun stop() = TtsResult.Success(Unit)
            override fun requestStop() = Unit
            override fun frame() = 0L
        }
        val presenter = ContinuousEditorPresenter(studio, scope, object : ContinuousEditorPorts {
            override val vocalPitch = object : VocalPitchHost {
                override val preview = preview
                override suspend fun original(project: Project, draft: VocalPitchDraft) = source
                override suspend fun render(project: Project, draft: VocalPitchDraft,
                    progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch {
                    renderEntered.complete(Unit); withContext(NonCancellable) { finishRender.await() }
                    return PreparedVocalPitch(Asset("b".repeat(64), "wav", 48_044, 48_000, 2, 6000,
                        "Correction", AssetRole.RENDERED, derivedFrom = source.hash), PitchCorrectionReport(6000, 1000, 2, emptyList(), 4096))
                }
            }
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location? = null
            override suspend fun chooseSave(): Location? = null
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset): List<Float> = emptyList()
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float) = true
        })
        try {
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            val editor = requireNotNull(presenter.vocalPitch.value)
            val pending = async { editor.dispatch(PitchAction.Apply) }
            withTimeout(5000) { renderEntered.await() }
            assertTrue(studio.dispatch(Action.Save(Location("controlled-save"))).accepted)
            withTimeout(5000) { saveEntered.await() }
            assertNotNull(studio.work.value.jobId)
            withTimeout(5000) { presenter.state.first { !it.permits(ContinuousCapability.VOCAL_PITCH) } }
            withTimeout(5000) { editor.state.first { it.problem == PitchEditorProblem.BUSY } }
            assertFalse(presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
            assertFalse(editor.dispatch(PitchAction.PreviewOriginal))
            finishRender.complete(Unit)
            assertFalse(withTimeout(5000) { pending.await() })
            assertEquals(initial, studio.document.value.project)
            assertFalse(studio.document.value.canUndo)
            finishSave.complete(Unit)
            withTimeout(5000) { studio.work.first { it.jobId == null } }
            assertEquals(0L, studio.document.value.revision)
        } finally {
            finishSave.complete(Unit); finishRender.complete(Unit)
            presenter.close(); studio.dispatch(Action.Close); scope.cancel()
        }
    }

    private class BoundaryFixture(blockApply: Boolean = false, blockAnalysis: Boolean = false, blockMonitor: Boolean = false) {
        val source = Asset("a".repeat(64), "wav", 48_044, 48_000, 2, 6000, "Original")
        val initial = Project(assets = frozenListOf(source), source = Source(source.hash, FrameRange(0, source.frames)),
            tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", source.hash, FrameRange(0, source.frames))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val analysisEntered = CompletableDeferred<Unit>(); val releaseAnalysis = CompletableDeferred<Unit>()
        val monitorEntered = CompletableDeferred<Unit>(); val releaseMonitor = CompletableDeferred<Unit>()
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = source }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) = Unit
            override suspend fun open(location: Location) = initial
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("No export")
        }, object : EnginePort {
            override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram {
                if (blockApply && project.pitchCorrections.isNotEmpty()) { applyEntered.complete(Unit); releaseApply.await() }
                return EngineProgram(revision = revision)
            }
            override suspend fun apply(command: EngineCommand) = true
            override fun snapshot() = TransportState(outputAttached = true)
        }), initial)
        val preview = object : VocalPreviewPort {
            override val state = MutableStateFlow(VocalPreviewState())
            override suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit> = error("No audition")
            override suspend fun stop() = TtsResult.Success(Unit)
            override fun requestStop() = Unit
            override fun frame() = 0L
        }
        val presenter = ContinuousEditorPresenter(studio, scope, object : ContinuousEditorPorts {
            override val sourceAnalysisAvailable = true
            override suspend fun analyseSource(asset: Asset, range: FrameRange): SourceMusicResult {
                analysisEntered.complete(Unit)
                if (blockAnalysis) withContext(NonCancellable) { releaseAnalysis.await() }
                val first = (range.start * SourceMusicAnalysis.RATE + asset.sampleRate - 1) / asset.sampleRate
                val end = range.end * SourceMusicAnalysis.RATE / asset.sampleRate
                return SourceMusicResult((end - first).toInt(), frozenListOf(TempoCandidate(98_000, .8)), frozenListOf())
            }
            override val vocalTakes = object : VocalTakePort {
                override val preview = this@BoundaryFixture.preview
                override suspend fun render(project: Project, draft: VocalCompDraft, name: String) = error("No comp")
            }
            override val vocalPractice = object : VocalPracticePort {
                override val preview = this@BoundaryFixture.preview
                override val renderer = object : VocalPracticeRenderer {
                    override suspend fun render(project: Project, revision: Long, request: VocalPracticeRequest,
                        progress: (PracticeProgress) -> Unit) = PracticeResult.Failure(PracticeProblem.NO_AUDIO)
                }
            }
            override val vocalPitch = object : VocalPitchHost {
                override val preview = this@BoundaryFixture.preview
                override suspend fun original(project: Project, draft: VocalPitchDraft) = source
                override suspend fun render(project: Project, draft: VocalPitchDraft,
                    progress: (PitchCorrectionPhase, Int, Int) -> Unit) = PreparedVocalPitch(
                    Asset("b".repeat(64), "wav", 48_044, 48_000, 2, 6000, "Correction", AssetRole.RENDERED, derivedFrom = source.hash),
                    PitchCorrectionReport(6000, 1000, 2, emptyList(), 4096))
            }
            override val onlineSource = OnlineSourceHost { _, _ -> OnlineImportSession(object : OnlineSourcePort {
                override val state = MutableStateFlow(OnlineWorkerState())
                override fun search(query: String, catalog: OnlineCatalog) = false
                override fun inspect(id: String) = false
                override fun selectFormat(id: String) = false
                override fun save(id: String) = false
                override fun cancel() = Unit
                override fun stopAll() = Unit
                override fun close() = Unit
            }) { null } }
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location? = null
            override suspend fun chooseSave(): Location? = null
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset): List<Float> = emptyList()
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float): Boolean {
                monitorEntered.complete(Unit)
                if (blockMonitor) releaseMonitor.await()
                return true
            }
        })
        suspend fun close() {
            releaseApply.complete(Unit); releaseAnalysis.complete(Unit); releaseMonitor.complete(Unit)
            presenter.close(); studio.dispatch(Action.Close); scope.cancel()
        }
    }
}
