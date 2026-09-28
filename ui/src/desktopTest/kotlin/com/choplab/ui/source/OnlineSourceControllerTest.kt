package com.choplab.ui.source

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class OnlineSourceControllerTest {
    @Test fun stoppedQueuedApplyNeverBeginsImportAndANewExplicitUseCanRetry() = runBlocking<Unit> {
        val queued = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        val port = Port(source)
        var imports = 0
        val controller = OnlineSourceController(port, OnlineSourceApply { _, _ -> imports++; OnlineUseResult.APPLIED }, 0,
            CoroutineScope(coroutineContext + dispatcher))
        try {
            controller.ready()
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
            assertTrue(controller.state.value.applying)
            controller.cancelPendingApply(); controller.stopAll()
            drain()
            assertEquals(0, imports); assertEquals(1, port.stops)
            assertFalse(controller.state.value.applying)
            assertEquals(OnlineProblem.CANCELLED, controller.state.value.issue)
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal)); drain()
            assertEquals(1, imports); assertTrue(controller.state.value.applied)
        } finally { controller.close(); drain() }
    }

    private val format = OnlineAudioFormat("original", "webm", "opus", 48_000, 2, 128_000, false, 1024, "ja", null, null, false)
    private val source = OnlineCandidate("source", "Synthetic source", "Uploader", 2.0, formats = listOf(format))
    private class Port(private val source: OnlineCandidate) : OnlineSourcePort {
        override val state = MutableStateFlow(OnlineWorkerState())
        var searches = 0; var saves = 0; var stops = 0; var cancels = 0; var closes = 0
        override fun search(query: String, catalog: OnlineCatalog): Boolean {
            searches++; state.value = OnlineWorkerState(OnlinePhase.CANDIDATES, candidates = listOf(source)); return true
        }
        override fun inspect(id: String): Boolean { state.value = state.value.copy(phase = OnlinePhase.DETAILS, details = source, saved = null); return true }
        override fun selectFormat(id: String): Boolean {
            if (source.formats.none { it.id == id }) return false
            state.value = state.value.copy(details = source.copy(selectedFormat = id), saved = null); return true
        }
        override fun save(id: String): Boolean {
            saves++; state.value = state.value.copy(phase = OnlinePhase.SAVED, saved = OnlineSaved("hash", source.title)); return true
        }
        override fun cancel() { cancels++; state.value = state.value.copy(phase = OnlinePhase.CANCELLED, busy = false, saved = null) }
        override fun stopAll() { stops++ }
        override fun close() { closes++ }
    }
    private suspend fun OnlineSourceController.ready() {
        assertTrue(dispatch(OnlineSourceAction.Query("synthetic")))
        assertTrue(dispatch(OnlineSourceAction.Search))
        assertTrue(dispatch(OnlineSourceAction.Inspect(source.id)))
        assertFalse(dispatch(OnlineSourceAction.Save))
        assertTrue(dispatch(OnlineSourceAction.Format(format.id)))
        assertTrue(dispatch(OnlineSourceAction.Save))
    }
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(1) }

    @Test fun queryCatalogAndFormatConfirmationCannotApplyOrReuseOldCandidates() = runBlocking<Unit> {
        val port = Port(source)
        var uses = 0
        val controller = OnlineSourceController(port, OnlineSourceApply { _, _ -> uses++; OnlineUseResult.APPLIED }, 8, this)
        try {
            controller.ready()
            assertEquals(1, port.saves); assertEquals(0, uses)
            assertTrue(controller.dispatch(OnlineSourceAction.Query("changed")))
            assertTrue(controller.dispatch(OnlineSourceAction.Query("synthetic")))
            assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal))
            assertFalse(controller.dispatch(OnlineSourceAction.Inspect(source.id)))
            assertEquals(0, uses)
            assertTrue(controller.dispatch(OnlineSourceAction.Search))
            assertTrue(controller.dispatch(OnlineSourceAction.Catalog(OnlineCatalog.MUSIC)))
            assertFalse(controller.dispatch(OnlineSourceAction.Inspect(source.id)))
            assertEquals(2, port.searches)
            controller.ready()
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal))
            waitFor { controller.state.value.applied }
            assertEquals(1, uses)
            assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal))
        } finally { controller.close() }
    }

    @Test fun applyCarriesCapturedRevisionRejectsRecordingAndStaleAndStopNeverWaitsForApply() = runBlocking<Unit> {
        val port = Port(source)
        val availability = MutableStateFlow(OnlineAvailability.EDITABLE)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<OnlineUseResult>()
        var uses = 0
        val controller = OnlineSourceController(port, OnlineSourceApply { id, revision ->
            assertEquals("hash", id); assertEquals(37L, revision); uses++; entered.complete(Unit); finish.await()
        }, 37, this, availability)
        try {
            controller.ready()
            availability.value = OnlineAvailability.RECORDING
            // Recheck the source flow at the action boundary, before the display collector can catch up.
            assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal)); assertEquals(0, uses)
            availability.value = OnlineAvailability.EDITABLE
            waitFor { controller.state.value.availability == OnlineAvailability.EDITABLE }
            assertTrue(controller.dispatch(OnlineSourceAction.UseOriginal)); entered.await()
            assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal))
            assertFalse(controller.requestClose()); assertEquals(0, port.closes)
            controller.stopAll(); assertEquals(1, port.stops)
            assertTrue(controller.state.value.applying)
            finish.complete(OnlineUseResult.STALE_DOCUMENT)
            waitFor { controller.state.value.issue == OnlineProblem.STALE_DOCUMENT }
            assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal)); assertEquals(1, uses)
            assertEquals("hash", port.state.value.saved!!.id)
            assertTrue(controller.requestClose()); controller.close(); assertEquals(1, port.closes)
        } finally { finish.complete(OnlineUseResult.REJECTED); controller.close() }
    }

    @Test fun closeAndCancelNeverApplySavedAudioAndLateWorkerResultsCannotReopenTheView() = runBlocking<Unit> {
        val port = Port(source)
        var uses = 0
        val controller = OnlineSourceController(port, OnlineSourceApply { _, _ -> uses++; OnlineUseResult.APPLIED }, 0, this)
        controller.ready()
        assertTrue(controller.requestClose()); controller.close()
        port.state.value = port.state.value.copy(saved = OnlineSaved("late", "Late"))
        yield()
        assertTrue(controller.state.value.closed); assertEquals(0, uses); assertEquals(1, port.closes)
        assertFalse(controller.dispatch(OnlineSourceAction.UseOriginal))
        val cancelling = OnlineSourceController(port, OnlineSourceApply { _, _ -> error("No apply during cancellation") }, 0, this)
        try {
            port.state.value = OnlineWorkerState(phase = OnlinePhase.DOWNLOADING, busy = true)
            assertTrue(cancelling.dispatch(OnlineSourceAction.Cancel)); assertEquals(1, port.cancels)
            cancelling.stopAll(); assertEquals(1, port.stops)
        } finally { cancelling.close() }
    }
}
