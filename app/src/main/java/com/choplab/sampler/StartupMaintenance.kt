package com.choplab.sampler

import com.choplab.sampler.model.SamplerUiState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** The caller supplies an I/O dispatcher. No timer or file access runs during recovery. */
internal suspend fun runStartupMaintenance(
    state: StateFlow<SamplerUiState>,
    cleanup: () -> Unit,
) {
    state.first { !it.isLoading }
    currentCoroutineContext().ensureActive()
    cleanup()
}
