package com.choplab.sampler.model

/** Bounded project edit history. Audio buffers are shared because the MVP never mutates PCM in place. */
class EditHistory(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val undoEntries = ArrayDeque<Entry>()
    private val redoEntries = ArrayDeque<Entry>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val canUndo: Boolean
        get() = undoEntries.isNotEmpty()

    val canRedo: Boolean
        get() = redoEntries.isNotEmpty()

    fun record(state: SamplerUiState, mergeKey: String? = null) {
        if (mergeKey != null && undoEntries.lastOrNull()?.mergeKey == mergeKey) return
        undoEntries.addLast(Entry(state.historySnapshot(), mergeKey))
        while (undoEntries.size > maxEntries) undoEntries.removeFirst()
        redoEntries.clear()
    }

    fun undo(current: SamplerUiState): SamplerUiState? {
        val target = undoEntries.removeLastOrNull() ?: return null
        redoEntries.addLast(Entry(current.historySnapshot(), mergeKey = null))
        while (redoEntries.size > maxEntries) redoEntries.removeFirst()
        return target.state
    }

    fun redo(current: SamplerUiState): SamplerUiState? {
        val target = redoEntries.removeLastOrNull() ?: return null
        undoEntries.addLast(Entry(current.historySnapshot(), mergeKey = null))
        while (undoEntries.size > maxEntries) undoEntries.removeFirst()
        return target.state
    }

    fun reset() {
        undoEntries.clear()
        redoEntries.clear()
    }

    private data class Entry(val state: SamplerUiState, val mergeKey: String?)

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 40
    }
}

private fun SamplerUiState.historySnapshot(): SamplerUiState = copy(
    isLoading = false,
    statusMessage = "",
    transportPlaying = false,
    recordArmed = false,
    currentStep = -1,
    microphoneRecording = false,
    systemAudioRecording = false,
    sourcePlaying = false,
    canUndo = false,
    canRedo = false,
)
