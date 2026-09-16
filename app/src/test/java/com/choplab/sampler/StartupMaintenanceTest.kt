package com.choplab.sampler

import com.choplab.sampler.model.SamplerUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class StartupMaintenanceTest {
    @Test fun loadingDoesNotRunCleanupEvenAfterOtherStateUpdates() = runBlocking {
        val state = MutableStateFlow(SamplerUiState(isLoading = true))
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runStartupMaintenance(state) { calls++ } }
        repeat(20) { state.value = state.value.copy(selectedPad = it); yield() }
        assertEquals(0, calls)
        job.cancelAndJoin()
    }

    @Test fun readinessRunsCleanupExactlyOnce() = runBlocking {
        val state = MutableStateFlow(SamplerUiState(isLoading = true))
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runStartupMaintenance(state) { calls++ } }
        state.value = state.value.copy(isLoading = false)
        job.join()
        state.value = state.value.copy(isLoading = true)
        state.value = state.value.copy(isLoading = false)
        yield()
        assertEquals(1, calls)
    }

    @Test fun alreadyReadyStateDoesNotAddAnArtificialDelay() = runBlocking {
        val state = MutableStateFlow(SamplerUiState(isLoading = false))
        var calls = 0
        runStartupMaintenance(state) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun cancellationDuringLoadingPreventsLateCleanup() = runBlocking {
        val state = MutableStateFlow(SamplerUiState(isLoading = true))
        var called = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runStartupMaintenance(state) { called = true } }
        job.cancelAndJoin()
        state.value = state.value.copy(isLoading = false)
        yield()
        assertFalse(called)
    }

    @Test fun failedRecoveryMayStillReleaseTheMaintenanceGate() = runBlocking {
        val state = MutableStateFlow(SamplerUiState(isLoading = true))
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runStartupMaintenance(state) { calls++ } }
        state.value = state.value.copy(isLoading = false, statusMessage = "synthetic recovery failure")
        job.join()
        assertEquals(1, calls)
    }

    @Test fun cleanupUsesTheCallersDispatcher() = runBlocking {
        val caller = Thread.currentThread()
        runStartupMaintenance(MutableStateFlow(SamplerUiState())) { assertSame(caller, Thread.currentThread()) }
    }
}
