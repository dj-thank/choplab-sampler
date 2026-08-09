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
    if (audio.frameCount < 2) return PadAssignmentResult(state, emptyList())
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

/**
 * Assigns the source playhead to a pad and keeps live chops contiguous in time.
 *
 * Only pads in the currently visible bank that reference the current audio are
 * reflowed. Per-pad performance settings are intentionally preserved.
 */
fun assignLiveChopToPad(
    state: SamplerUiState,
    padIndex: Int,
    startFrame: Int,
): PadAssignmentResult {
    val audio = state.currentAudio ?: return PadAssignmentResult(state, emptyList())
    if (audio.frameCount < 2) return PadAssignmentResult(state, emptyList())
    val bank = state.selectedBank.coerceIn(0, SamplerConfig.BANK_COUNT - 1)
    val bankStart = bank * SamplerConfig.PADS_PER_BANK
    val bankEndExclusive = bankStart + SamplerConfig.PADS_PER_BANK
    if (padIndex !in bankStart until bankEndExclusive) {
        return PadAssignmentResult(state, emptyList())
    }

    val selectionEnd = state.rangeEndFrame
        .takeIf { it in 1..audio.frameCount }
        ?: audio.frameCount
    val selectionStart = state.rangeStartFrame.coerceIn(0, selectionEnd - 1)
    val safeStart = startFrame.coerceIn(selectionStart, selectionEnd - 1)
    val mutablePads = state.pads.toMutableList()
    mutablePads[padIndex] = mutablePads[padIndex].copy(
        audio = audio,
        startFrame = safeStart,
        endFrame = selectionEnd,
    )

    val livePadIndices = (bankStart until bankEndExclusive)
        .filter { index ->
            val pad = mutablePads[index]
            pad.audio?.id == audio.id && pad.startFrame in selectionStart until selectionEnd
        }
        .sortedWith(compareBy({ mutablePads[it].startFrame }, { it }))

    livePadIndices.forEach { index ->
        val pad = mutablePads[index]
        val nextStart = livePadIndices
            .asSequence()
            .map { mutablePads[it].startFrame }
            .firstOrNull { it > pad.startFrame }
            ?: selectionEnd
        mutablePads[index] = pad.copy(endFrame = nextStart.coerceAtLeast(pad.startFrame + 1))
    }

    val markers = (state.sliceMarkers + safeStart)
        .filter { it > selectionStart && it < selectionEnd }
        .distinct()
        .sorted()

    return PadAssignmentResult(
        state = state.copy(
            pads = mutablePads,
            selectedBank = bank,
            selectedPad = padIndex,
            sliceMarkers = markers,
            statusMessage = "PAD ${padIndex - bankStart + 1} に ${formatLiveChopTime(safeStart, audio.sampleRate)} を刻みました",
        ),
        changedPads = livePadIndices.map(mutablePads::get),
    )
}

private fun formatLiveChopTime(frame: Int, sampleRate: Int): String {
    val totalSeconds = frame.toDouble() / sampleRate.coerceAtLeast(1)
    val minutes = (totalSeconds / 60.0).toInt()
    val seconds = totalSeconds - minutes * 60.0
    return "%d:%04.1f".format(minutes, seconds)
}
