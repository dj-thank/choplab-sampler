package com.choplab.ui.analysis

import com.choplab.core.DocumentState
import com.choplab.core.analysis.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class SourceAnalysisControllerTest {
    @Test fun onlyAnExplicitKnownTempoAppliesOnceAndKeepsSourceSwingAndNotes() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            val before = fixture.document.value
            assertTrue(fixture.controller.dispatch(SourceAnalysisAction.Analyse))
            assertEquals(before, fixture.document.value)
            assertFalse(fixture.controller.dispatch(SourceAnalysisAction.Apply))
            assertFalse(fixture.controller.dispatch(SourceAnalysisAction.SelectTempo(200_000)))
            assertTrue(fixture.controller.dispatch(SourceAnalysisAction.SelectTempo(98_000)))
            assertEquals(before, fixture.document.value)
            assertTrue(fixture.controller.dispatch(SourceAnalysisAction.Apply))
            assertEquals(1, fixture.edits.size)
            assertEquals(before.project.tempo.copy(milliBpm = 98_000), fixture.document.value.project.tempo)
            assertEquals(before.project, fixture.document.value.project.copy(tempo = before.project.tempo))
            assertFalse(fixture.controller.dispatch(SourceAnalysisAction.Apply))
            assertTrue(fixture.controller.close())
            assertFalse(fixture.controller.dispatch(SourceAnalysisAction.Analyse))
        } finally { fixture.close() }
    }

    @Test fun cancellationLateCompletionCloseAndChangedDocumentsCannotPublishCandidates() = runBlocking<Unit> {
        for (mode in listOf("cancel", "close", "stale", "recording", "busy")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fixture = Fixture(analyse = { asset, range ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                result(asset, range)
            })
            try {
                val before = fixture.document.value
                val task = async { fixture.controller.dispatch(SourceAnalysisAction.Analyse) }
                withTimeout(5_000) { entered.await() }
                when (mode) {
                    "cancel" -> assertTrue(fixture.controller.dispatch(SourceAnalysisAction.Cancel))
                    "close" -> assertTrue(fixture.controller.close())
                    "stale" -> fixture.document.value = before.copy(revision = 1)
                    "recording" -> fixture.availability.value = SourceAnalysisAvailability.RECORDING
                    "busy" -> fixture.availability.value = SourceAnalysisAvailability.BUSY
                }
                release.complete(Unit)
                assertFalse(task.await(), mode)
                assertNull(fixture.controller.state.value.result, mode)
                assertTrue(fixture.edits.isEmpty(), mode)
                assertEquals(before.project, fixture.document.value.project, mode)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun revisionOrRecordingChangesAtTheApplyBoundaryAreRecheckedAndCloseWaitsForAtomicApply() = runBlocking<Unit> {
        for (mode in listOf("stale", "recording")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fixture = Fixture(beforeApply = { entered.complete(Unit); release.await() })
            try {
                fixture.controller.dispatch(SourceAnalysisAction.Analyse)
                fixture.controller.dispatch(SourceAnalysisAction.SelectTempo(98_000))
                val task = async { fixture.controller.dispatch(SourceAnalysisAction.Apply) }
                withTimeout(5_000) { entered.await() }
                assertFalse(fixture.controller.close(), "An atomic Apply must finish before closing")
                val before = fixture.document.value
                if (mode == "stale") fixture.document.value = before.copy(revision = 1) else fixture.availability.value = SourceAnalysisAvailability.RECORDING
                release.complete(Unit)
                assertFalse(task.await())
                assertEquals(before.project, fixture.document.value.project)
                assertTrue(fixture.edits.isEmpty())
                if (mode == "stale") assertEquals(SourceAnalysisProblem.STALE, fixture.controller.state.value.problem)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun aLongSelectedRangeIsBoundedAndInvalidOrFailedAnalysisCanBeRetriedWithoutApplying() = runBlocking<Unit> {
        var attempts = 0
        val original = Fixture.project().let { p ->
            val asset = p.assets.single().copy(frames = 100 * 48_000)
            p.copy(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(5 * 48_000, 90 * 48_000)))
        }
        val fixture = Fixture(original, analyse = { asset, range ->
            assertEquals(FrameRange(5 * 48_000, 35 * 48_000), range)
            if (++attempts == 1) SourceMusicResult(1, frozenListOf(), frozenListOf()) else result(asset, range)
        })
        try {
            assertFalse(fixture.controller.dispatch(SourceAnalysisAction.Analyse))
            assertEquals(SourceAnalysisProblem.FAILED, fixture.controller.state.value.problem)
            assertTrue(fixture.controller.dispatch(SourceAnalysisAction.Analyse))
            assertEquals(30 * 48_000, fixture.controller.state.value.result?.frames)
            assertTrue(fixture.edits.isEmpty())
        } finally { fixture.close() }
    }

    private class Fixture(initial: Project = project(),
        val analyse: suspend (Asset, FrameRange) -> SourceMusicResult = ::result,
        val beforeApply: suspend () -> Unit = {}) {
        val document = MutableStateFlow(DocumentState(initial, 0))
        val availability = MutableStateFlow(SourceAnalysisAvailability.EDITABLE)
        val edits = mutableListOf<Intent>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = SourceAnalysisController(document, availability, object : SourceAnalysisPorts {
            override suspend fun analyse(asset: Asset, range: FrameRange) = this@Fixture.analyse(asset, range)
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                beforeApply()
                if (document.value.revision != expectedRevision || availability.value != SourceAnalysisAvailability.EDITABLE) return false
                val result = Reducer.reduce(document.value.project, intent).project
                edits += intent; document.value = DocumentState(result, expectedRevision + 1, canUndo = true)
                return true
            }
        }, scope)
        fun close() { controller.dispose(); scope.cancel() }
        companion object {
            fun project(): Project {
                val asset = Asset("a".repeat(64), "wav", 2_304_044, 48_000, 2, 6 * 48_000, "Original")
                return Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, asset.frames)),
                    tempo = com.choplab.engine.Tempo(120_000, 620))
            }
        }
    }
    companion object {
        private fun result(asset: Asset, range: FrameRange) = SourceMusicResult((range.length * 48_000 / asset.sampleRate).toInt(),
            frozenListOf(TempoCandidate(98_000, .9), TempoCandidate(49_000, .8)), frozenListOf(KeyCandidate(0, KeyMode.MAJOR, .8)))
    }
}
