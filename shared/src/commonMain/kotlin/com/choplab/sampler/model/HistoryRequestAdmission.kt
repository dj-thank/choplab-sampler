package com.choplab.sampler.model

/** Runtime owner that currently prevents Undo or Redo from consuming history. */
enum class HistoryRequestDenial {
    LOADING,
    RECORDING,
}

/**
 * One admission truth for visible controls, platform adapters and the history owner.
 * Loading takes precedence because an asynchronous project operation owns replacement.
 */
val SamplerUiState.historyRequestDenial: HistoryRequestDenial?
    get() = when {
        isLoading -> HistoryRequestDenial.LOADING
        !editingRequestAllowedDuringRecording(recordingSession) -> HistoryRequestDenial.RECORDING
        else -> null
    }

val SamplerUiState.undoRequestEnabled: Boolean
    get() = canUndo && historyRequestDenial == null

val SamplerUiState.redoRequestEnabled: Boolean
    get() = canRedo && historyRequestDenial == null
