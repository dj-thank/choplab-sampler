package com.choplab.sampler.model

data class PadAssignmentResult(
    val state: SamplerUiState,
    val changedPads: List<PadModel>,
)

fun selectPlayablePad(state: SamplerUiState, globalIndex: Int): SamplerUiState {
    val target = state.pads.getOrNull(globalIndex) ?: return state
    if (!target.isAssigned) {
        return state.copy(
            statusMessage = "${padDisplayLabel(target)}は空です。音の入ったPADを選んでください",
        )
    }
    return state.copy(
        selectedBank = target.bankIndex,
        selectedPad = target.globalIndex,
        statusMessage = "${padDisplayLabel(target)}を選択しました",
    )
}

fun selectPlayablePadPage(state: SamplerUiState, pageIndex: Int): SamplerUiState {
    if (pageIndex !in 0 until SamplerConfig.PAD_PAGES_PER_BANK) return state
    val pageStart = state.selectedBank * SamplerConfig.PADS_PER_BANK +
        pageIndex * SamplerConfig.PAD_PAGE_SIZE
    val pagePads = state.pads.subList(pageStart, pageStart + SamplerConfig.PAD_PAGE_SIZE)
    val preferredOffset = state.selectedPad % SamplerConfig.PAD_PAGE_SIZE
    val target = pagePads[preferredOffset].takeIf(PadModel::isAssigned)
        ?: pagePads.firstOrNull(PadModel::isAssigned)
        ?: return state.copy(
            statusMessage = "PAD ${pageStart % SamplerConfig.PADS_PER_BANK + 1}–${pageStart % SamplerConfig.PADS_PER_BANK + SamplerConfig.PAD_PAGE_SIZE}には音がありません",
        )
    return state.copy(
        selectedPad = target.globalIndex,
        statusMessage = "${padDisplayLabel(target)}を選択しました",
    )
}

fun selectPlayableBank(state: SamplerUiState, bankIndex: Int): SamplerUiState {
    if (bankIndex !in 0 until SamplerConfig.BANK_COUNT) return state
    val bankStart = bankIndex * SamplerConfig.PADS_PER_BANK
    val bankPads = state.pads.subList(bankStart, bankStart + SamplerConfig.PADS_PER_BANK)
    val preferredOffset = state.selectedPad % SamplerConfig.PADS_PER_BANK
    val role = bankRoleFor(bankIndex)
    val target = bankPads[preferredOffset].takeIf(PadModel::isAssigned)
        ?: bankPads.firstOrNull(PadModel::isAssigned)
        ?: return state.copy(
            statusMessage = "BANK ${role.letter} ${role.japaneseLabel}には音がありません。先にチョップか重ねるで音を入れてください",
        )
    return state.copy(
        selectedBank = bankIndex,
        selectedPad = target.globalIndex,
        statusMessage = "${padDisplayLabel(target)}を選択しました",
    )
}

fun ensurePlayablePadSelected(state: SamplerUiState): SamplerUiState {
    if (state.pads.getOrNull(state.selectedPad)?.isAssigned == true) return state
    val bankStart = state.selectedBank * SamplerConfig.PADS_PER_BANK
    val target = state.pads
        .subList(bankStart, bankStart + SamplerConfig.PADS_PER_BANK)
        .firstOrNull(PadModel::isAssigned)
        ?: state.pads.firstOrNull(PadModel::isAssigned)
        ?: return state.copy(
            statusMessage = "再生できるPADがありません。先にチョップで音を入れてください",
        )
    return state.copy(
        selectedBank = target.bankIndex,
        selectedPad = target.globalIndex,
        statusMessage = "${padDisplayLabel(target)}をビートの操作対象にしました",
    )
}

private fun padDisplayLabel(pad: PadModel): String =
    "${bankRoleFor(pad.bankIndex).letter}-%02d".format(pad.indexInBank + 1)

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
    val selectedIndexInBank = (state.selectedPad - bankStart)
        .coerceIn(0, SamplerConfig.PADS_PER_BANK - 1)
    val writablePadIndices = (0 until SamplerConfig.PADS_PER_BANK)
        .map { offset -> bankStart + (selectedIndexInBank + offset) % SamplerConfig.PADS_PER_BANK }
        .filter { index -> !mutablePads[index].isAssigned }
    val changed = mutableListOf<PadModel>()

    acceptedRanges.zip(writablePadIndices).forEach { (range, globalIndex) ->
        val updated = mutablePads[globalIndex].copy(
            audio = audio,
            startFrame = range.startFrame,
            endFrame = range.endFrame,
            contentKind = PadContentKind.SAMPLE,
        )
        mutablePads[globalIndex] = updated
        changed += updated
    }
    if (changed.isEmpty()) {
        return PadAssignmentResult(
            state.copy(statusMessage = "このBANKは満杯です。音を消さず、空きBANKを選んでください"),
            emptyList(),
        )
    }

    val lastIndexInBank = changed.last().indexInBank
    val nextPad = if (state.autoNextPad) {
        (1..SamplerConfig.PADS_PER_BANK)
            .map { offset -> bankStart + (lastIndexInBank + offset) % SamplerConfig.PADS_PER_BANK }
            .firstOrNull { index -> !mutablePads[index].isAssigned }
            ?: changed.last().globalIndex
    } else {
        state.selectedPad
    }
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
            statusMessage = if (changed.size == acceptedRanges.size) {
                statusMessage
            } else {
                "空きPAD ${changed.size}個だけに保存しました。既存の音は上書きしていません"
            },
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
    if (state.pads[padIndex].isAssigned) {
        return PadAssignmentResult(
            state.copy(statusMessage = "PAD ${state.pads[padIndex].indexInBank + 1}には音があります。上書きしません"),
            emptyList(),
        )
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
