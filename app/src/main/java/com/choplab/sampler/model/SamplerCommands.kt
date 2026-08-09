package com.choplab.sampler.model

data class PadAssignmentResult(
    val state: SamplerUiState,
    val changedPads: List<PadModel>,
)

fun assignRangesToPads(
    state: SamplerUiState,
    ranges: List<SliceRange>,
    statusMessage: String,
): PadAssignmentResult {
    val audio = state.currentAudio ?: return PadAssignmentResult(state, emptyList())
    val acceptedRanges = ranges
        .asSequence()
        .filter { it.startFrame >= 0 && it.endFrame <= audio.frameCount && it.endFrame > it.startFrame }
        .take(SamplerConfig.PADS_PER_BANK)
        .toList()
    if (acceptedRanges.isEmpty()) return PadAssignmentResult(state, emptyList())

    val mutablePads = state.pads.toMutableList()
    val selectedBank = state.selectedBank.coerceIn(0, SamplerConfig.BANK_COUNT - 1)
    val bankStart = selectedBank * SamplerConfig.PADS_PER_BANK
    var indexInBank = (state.selectedPad - bankStart).coerceIn(0, SamplerConfig.PADS_PER_BANK - 1)
    val changed = mutableListOf<PadModel>()

    acceptedRanges.forEach { range ->
        val globalIndex = bankStart + indexInBank
        val updated = mutablePads[globalIndex].copy(
            audio = audio,
            startFrame = range.startFrame,
            endFrame = range.endFrame,
        )
        mutablePads[globalIndex] = updated
        changed += updated
        indexInBank = (indexInBank + 1) % SamplerConfig.PADS_PER_BANK
    }

    val nextPad = if (state.autoNextPad) bankStart + indexInBank else state.selectedPad
    val nextSlice = if (
        state.autoNextPad && acceptedRanges.size == 1 && state.activeSliceIndex != null
    ) {
        (state.activeSliceIndex + 1).takeIf { it < state.sliceRanges().size }
            ?: state.activeSliceIndex
    } else {
        state.activeSliceIndex
    }

    return PadAssignmentResult(
        state = state.copy(
            pads = mutablePads,
            selectedBank = selectedBank,
            selectedPad = nextPad,
            activeSliceIndex = nextSlice,
            statusMessage = statusMessage,
        ),
        changedPads = changed,
    )
}
