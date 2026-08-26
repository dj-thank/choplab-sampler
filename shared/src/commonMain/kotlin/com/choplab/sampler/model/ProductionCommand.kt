package com.choplab.sampler.model

/**
 * Platform-independent intent submitted by the deck, MIDI, or a future assist proposal.
 *
 * Only commands with a complete shared contract belong here. Platform capabilities
 * remain explicit [ProductionEffect] values executed by the application adapter.
 */
sealed interface ProductionCommand {
    data class SetSourceRangeStart(val frame: Int) : ProductionCommand
    data class SetSourceRangeEnd(val frame: Int) : ProductionCommand
    data class AddSliceMarker(val frame: Int) : ProductionCommand
    data class MoveSliceMarker(val markerIndex: Int, val frame: Int) : ProductionCommand
    data class SelectSliceAt(val frame: Int) : ProductionCommand
    data object ToggleSelectedPadPerformanceMode : ProductionCommand
    data object CreateQuickSketch : ProductionCommand
    data class SelectPatternVariation(val slot: Int) : ProductionCommand
    data object DuplicateSelectedPatternToOther : ProductionCommand
    data class ToggleSongSectionPattern(val sectionIndex: Int) : ProductionCommand
    data object ToggleSongMode : ProductionCommand
}

/** Whether the accepted command changed durable music, only this session, or nothing. */
enum class ProductionMutation {
    NONE,
    SESSION,
    PROJECT,
}

/** Capability action that a platform adapter must execute after publishing the new state. */
sealed interface ProductionEffect {
    data class StopPad(val index: Int) : ProductionEffect
    data class RefreshPad(val pad: PadModel) : ProductionEffect
    data object RefreshPattern : ProductionEffect
}

data class ProductionCommandResult(
    val state: SamplerUiState,
    val mutation: ProductionMutation,
    val mergeKey: String? = null,
    val effects: List<ProductionEffect> = emptyList(),
)

fun reduceProductionCommand(
    state: SamplerUiState,
    command: ProductionCommand,
): ProductionCommandResult {
    if (command is ProductionCommand.SelectSliceAt) {
        return selectSliceAt(state, command.frame)
    }
    if (state.isLoading) {
        return sessionFeedback(state, "現在の処理が終わってから編集してください")
    }
    if (!editingRequestAllowedDuringRecording(state.recordingSession)) {
        return sessionFeedback(state, "録音をSTOPしてから編集してください")
    }

    return when (command) {
        is ProductionCommand.SetSourceRangeStart -> setSourceRangeStart(state, command.frame)
        is ProductionCommand.SetSourceRangeEnd -> setSourceRangeEnd(state, command.frame)
        is ProductionCommand.AddSliceMarker -> addSliceMarker(state, command.frame)
        is ProductionCommand.MoveSliceMarker -> moveSliceMarker(state, command.markerIndex, command.frame)
        is ProductionCommand.SelectSliceAt -> error("Selection is handled before edit admission")
        ProductionCommand.ToggleSelectedPadPerformanceMode -> toggleSelectedPadPerformanceMode(state)
        ProductionCommand.CreateQuickSketch -> createQuickSketch(state)
        is ProductionCommand.SelectPatternVariation -> arrangementCommandResult(
            state,
            state.selectPatternVariation(command.slot),
        )
        ProductionCommand.DuplicateSelectedPatternToOther -> arrangementCommandResult(
            state,
            state.duplicateSelectedPatternToOther(),
        )
        is ProductionCommand.ToggleSongSectionPattern -> arrangementCommandResult(
            state,
            state.toggleSongSectionPattern(command.sectionIndex),
        )
        ProductionCommand.ToggleSongMode -> arrangementCommandResult(state, state.toggleSongMode())
    }
}

fun canCreateQuickSketch(state: SamplerUiState): Boolean =
    quickSketchPreconditionMessage(state) == null

fun minimumChopFrames(sampleRate: Int): Int =
    (sampleRate.coerceAtLeast(1) * MINIMUM_CHOP_SECONDS).toInt().coerceAtLeast(64)

fun snapFrameToZeroCrossing(
    audio: PcmAudio,
    targetFrame: Int,
    lowerBound: Int,
    upperBound: Int,
): Int {
    val samples = audio.samples
    if (samples.size < 2) return targetFrame.coerceIn(lowerBound, upperBound)

    val safeLower = lowerBound.coerceIn(0, samples.size)
    val safeUpper = upperBound.coerceIn(safeLower, samples.size)
    val target = targetFrame.coerceIn(safeLower, safeUpper)
    if (target == 0 || target == samples.size) return target

    val radius = (audio.sampleRate * ZERO_CROSSING_SEARCH_SECONDS)
        .toInt()
        .coerceIn(32, 1_024)
    val from = maxOf(1, safeLower, target - radius)
    val to = minOf(samples.lastIndex, safeUpper, target + radius)
    if (from > to) return target

    var bestCrossing = -1
    var bestDistance = Int.MAX_VALUE
    for (frame in from..to) {
        val previous = samples[frame - 1].toInt()
        val current = samples[frame].toInt()
        val crossesZero =
            (previous <= 0 && current >= 0) || (previous >= 0 && current <= 0)
        if (crossesZero) {
            val distance = kotlin.math.abs(frame - target)
            if (distance < bestDistance) {
                bestCrossing = frame
                bestDistance = distance
            }
        }
    }
    if (bestCrossing >= 0) return bestCrossing

    var quietestFrame = target
    var quietestMagnitude = kotlin.math.abs(samples[target].toInt())
    for (frame in from..to) {
        val magnitude = kotlin.math.abs(samples[frame].toInt())
        if (magnitude < quietestMagnitude) {
            quietestFrame = frame
            quietestMagnitude = magnitude
        }
    }
    return quietestFrame
}

private fun setSourceRangeStart(
    state: SamplerUiState,
    frame: Int,
): ProductionCommandResult {
    val audio = state.currentAudio ?: return sessionFeedback(state, "先に素材を入れてください")
    val minimum = minimumChopFrames(audio.sampleRate)
    val upper = (state.rangeEndFrame - minimum).coerceAtLeast(0)
    val start = snapFrameToZeroCrossing(
        audio = audio,
        targetFrame = frame.coerceIn(0, upper),
        lowerBound = 0,
        upperBound = upper,
    )
    val markers = state.sliceMarkers.filter { it > start && it < state.rangeEndFrame }
    val next = state.copy(
        rangeStartFrame = start,
        sliceMarkers = markers,
        activeSliceIndex = null,
    )
    val projectChanged = start != state.rangeStartFrame || markers != state.sliceMarkers
    return classifiedResult(state, next, projectChanged, mergeKey = "range-start")
}

private fun setSourceRangeEnd(
    state: SamplerUiState,
    frame: Int,
): ProductionCommandResult {
    val audio = state.currentAudio ?: return sessionFeedback(state, "先に素材を入れてください")
    val minimum = minimumChopFrames(audio.sampleRate)
    val lower = (state.rangeStartFrame + minimum).coerceAtMost(audio.frameCount)
    val end = snapFrameToZeroCrossing(
        audio = audio,
        targetFrame = frame.coerceIn(lower, audio.frameCount),
        lowerBound = lower,
        upperBound = audio.frameCount,
    )
    val markers = state.sliceMarkers.filter { it > state.rangeStartFrame && it < end }
    val next = state.copy(
        rangeEndFrame = end,
        sliceMarkers = markers,
        activeSliceIndex = null,
    )
    val projectChanged = end != state.rangeEndFrame || markers != state.sliceMarkers
    return classifiedResult(state, next, projectChanged, mergeKey = "range-end")
}

private fun addSliceMarker(
    state: SamplerUiState,
    frame: Int,
): ProductionCommandResult {
    val audio = state.currentAudio ?: return sessionFeedback(state, "先に素材を入れてください")
    val minimum = minimumChopFrames(audio.sampleRate)
    if (state.rangeEndFrame.toLong() - state.rangeStartFrame < minimum.toLong() * 2L) {
        return sessionFeedback(state, "選択範囲が短く、チョップ位置を追加できません")
    }
    val lower = state.rangeStartFrame + minimum
    val upper = state.rangeEndFrame - minimum
    val safe = snapFrameToZeroCrossing(
        audio = audio,
        targetFrame = frame.coerceIn(lower, upper),
        lowerBound = lower,
        upperBound = upper,
    )
    val existingPoints = listOf(state.rangeStartFrame) +
        state.sliceMarkers.filter { it in (state.rangeStartFrame + 1) until state.rangeEndFrame } +
        state.rangeEndFrame
    if (existingPoints.any { kotlin.math.abs(it - safe) < minimum }) {
        return sessionFeedback(state, "既存スライスに近すぎます")
    }
    val markers = (state.sliceMarkers + safe).distinct().sorted()
    val next = state.copy(
        sliceMarkers = markers,
        activeSliceIndex = null,
        statusMessage = "チョップ位置を追加しました",
    )
    return classifiedResult(
        before = state,
        after = next,
        projectChanged = markers != state.sliceMarkers,
    )
}

private fun moveSliceMarker(
    state: SamplerUiState,
    markerIndex: Int,
    frame: Int,
): ProductionCommandResult {
    val audio = state.currentAudio ?: return sessionFeedback(state, "先に素材を入れてください")
    if (markerIndex !in state.sliceMarkers.indices) return unchanged(state)

    val minimum = minimumChopFrames(audio.sampleRate)
    val lower = if (markerIndex == 0) {
        state.rangeStartFrame + minimum
    } else {
        state.sliceMarkers[markerIndex - 1] + minimum
    }
    val upper = if (markerIndex == state.sliceMarkers.lastIndex) {
        state.rangeEndFrame - minimum
    } else {
        state.sliceMarkers[markerIndex + 1] - minimum
    }
    if (lower > upper) return unchanged(state)

    val markers = state.sliceMarkers.toMutableList()
    markers[markerIndex] = snapFrameToZeroCrossing(
        audio = audio,
        targetFrame = frame.coerceIn(lower, upper),
        lowerBound = lower,
        upperBound = upper,
    )
    val next = state.copy(
        sliceMarkers = markers,
        statusMessage = "チョップ境界 ${markerIndex + 1} を調整しました",
    )
    return classifiedResult(
        before = state,
        after = next,
        projectChanged = markers != state.sliceMarkers,
        mergeKey = "slice-marker-$markerIndex",
    )
}

private fun selectSliceAt(
    state: SamplerUiState,
    frame: Int,
): ProductionCommandResult {
    val selected = state.sliceRanges().indexOfFirst { range ->
        frame >= range.startFrame && frame < range.endFrame
    }.takeIf { it >= 0 }
    val next = state.copy(activeSliceIndex = selected)
    return if (next == state) unchanged(state) else ProductionCommandResult(
        state = next,
        mutation = ProductionMutation.SESSION,
    )
}

private fun toggleSelectedPadPerformanceMode(state: SamplerUiState): ProductionCommandResult {
    val selectedIndex = state.selectedPad
    val selected = state.pads.getOrNull(selectedIndex) ?: return unchanged(state)
    val updated = selected.copy(
        playMode = if (selected.playMode == PadPlayMode.GATE) {
            PadPlayMode.ONE_SHOT
        } else {
            PadPlayMode.GATE
        },
    )
    val pads = state.pads.toMutableList().also { it[selectedIndex] = updated }
    val ownedLoop = state.loopingPadIndex == selectedIndex
    val next = state.copy(
        pads = pads,
        loopingPadIndex = state.loopingPadIndex?.takeUnless { it == selectedIndex },
        loopPlayheadFrame = if (ownedLoop) -1 else state.loopPlayheadFrame,
    )
    val effects = buildList {
        if (ownedLoop) add(ProductionEffect.StopPad(selectedIndex))
        add(ProductionEffect.RefreshPad(updated))
        if (selected.playMode == PadPlayMode.LOOP) add(ProductionEffect.RefreshPattern)
    }
    return ProductionCommandResult(
        state = next,
        mutation = ProductionMutation.PROJECT,
        effects = effects,
    )
}

private fun createQuickSketch(state: SamplerUiState): ProductionCommandResult =
    when (val evaluation = quickSketchEvaluation(state)) {
        is QuickSketchEvaluation.Rejected -> sessionFeedback(state, evaluation.message)
        is QuickSketchEvaluation.Ready -> {
            val audio = requireNotNull(state.currentAudio)
            val pads = state.pads.toMutableList()
            evaluation.ranges.forEachIndexed { index, range ->
                pads[index] = pads[index].copy(
                    audio = audio,
                    startFrame = range.startFrame,
                    endFrame = range.endFrame,
                    contentKind = PadContentKind.SAMPLE,
                )
            }
            val melodySteps = evaluation.ranges.indices
                .mapTo(mutableSetOf()) { index -> stepKey(index, index * 2) }
            val updatedPads = pads.take(QUICK_SKETCH_SLICE_COUNT)
            ProductionCommandResult(
                state = state.copy(
                    sliceMarkers = evaluation.ranges.dropLast(1).map(SliceRange::endFrame),
                    activeSliceIndex = 0,
                    manualChopEnabled = false,
                    selectedBank = 0,
                    selectedPad = 0,
                    pads = pads,
                    activeSteps = state.activeSteps + melodySteps,
                    statusMessage = "8つの下書きを作りました。気に入らなければ元に戻せます",
                ),
                mutation = ProductionMutation.PROJECT,
                effects = updatedPads.map(ProductionEffect::RefreshPad) + ProductionEffect.RefreshPattern,
            )
        }
    }

private sealed interface QuickSketchEvaluation {
    data class Ready(val ranges: List<SliceRange>) : QuickSketchEvaluation
    data class Rejected(val message: String) : QuickSketchEvaluation
}

private fun quickSketchEvaluation(state: SamplerUiState): QuickSketchEvaluation {
    quickSketchPreconditionMessage(state)?.let { message ->
        return QuickSketchEvaluation.Rejected(message)
    }
    val audio = requireNotNull(state.currentAudio)
    val start = state.rangeStartFrame.coerceIn(0, audio.frameCount)
    val end = state.rangeEndFrame.coerceIn(start, audio.frameCount)
    val minimum = minimumChopFrames(audio.sampleRate)
    val boundaries = ArrayList<Int>(QUICK_SKETCH_SLICE_COUNT + 1)
    boundaries += start
    val length = end - start
    for (index in 1 until QUICK_SKETCH_SLICE_COUNT) {
        val lower = boundaries.last() + minimum
        val remainingSlices = QUICK_SKETCH_SLICE_COUNT - index
        val upper = end - minimum * remainingSlices
        if (lower > upper) {
            return QuickSketchEvaluation.Rejected(QUICK_SKETCH_UNSAFE_MESSAGE)
        }
        val ideal = start + (length.toLong() * index / QUICK_SKETCH_SLICE_COUNT).toInt()
        val boundary = snapFrameToZeroCrossing(
            audio = audio,
            targetFrame = ideal.coerceIn(lower, upper),
            lowerBound = lower,
            upperBound = upper,
        )
        if (boundary !in lower..upper || boundary <= boundaries.last()) {
            return QuickSketchEvaluation.Rejected(QUICK_SKETCH_UNSAFE_MESSAGE)
        }
        boundaries += boundary
    }
    boundaries += end
    val ranges = boundaries.zipWithNext(::SliceRange)
    if (ranges.size != QUICK_SKETCH_SLICE_COUNT || ranges.any { it.length < minimum }) {
        return QuickSketchEvaluation.Rejected(QUICK_SKETCH_UNSAFE_MESSAGE)
    }
    return QuickSketchEvaluation.Ready(ranges)
}

private fun quickSketchPreconditionMessage(state: SamplerUiState): String? {
    if (state.isLoading) return "現在の処理が終わってから編集してください"
    if (!editingRequestAllowedDuringRecording(state.recordingSession)) {
        return "録音をSTOPしてから編集してください"
    }
    val audio = state.currentAudio
        ?: return "先に素材を入れてください。制作は変更していません"
    if (state.pads.size < SamplerConfig.PADS_PER_BANK ||
        state.pads.take(SamplerConfig.PADS_PER_BANK).any(PadModel::isAssigned)
    ) {
        return "BANK Aに音があるため、下書きは作らず制作を変更していません"
    }
    val hasMelodyStep = state.activeSteps.any { key ->
        val padIndex = key / SamplerConfig.STEP_COUNT
        padIndex in 0 until SamplerConfig.PADS_PER_BANK
    }
    if (hasMelodyStep) {
        return "BANK Aに配置があるため、下書きは作らず制作を変更していません"
    }
    if (state.sliceMarkers.isNotEmpty()) {
        return "手動のチョップ位置があるため、下書きは作らず制作を変更していません"
    }
    val start = state.rangeStartFrame.coerceIn(0, audio.frameCount)
    val end = state.rangeEndFrame.coerceIn(start, audio.frameCount)
    val minimum = minimumChopFrames(audio.sampleRate)
    if (end.toLong() - start < minimum.toLong() * QUICK_SKETCH_SLICE_COUNT) {
        return "選択範囲が短く、8つの下書きを作れません。制作は変更していません"
    }
    return null
}

private fun classifiedResult(
    before: SamplerUiState,
    after: SamplerUiState,
    projectChanged: Boolean,
    mergeKey: String? = null,
): ProductionCommandResult = when {
    projectChanged -> ProductionCommandResult(
        state = after,
        mutation = ProductionMutation.PROJECT,
        mergeKey = mergeKey,
    )
    after != before -> ProductionCommandResult(
        state = after,
        mutation = ProductionMutation.SESSION,
    )
    else -> unchanged(before)
}

private fun arrangementCommandResult(
    before: SamplerUiState,
    after: SamplerUiState,
): ProductionCommandResult {
    val projectChanged = before.activeSteps != after.activeSteps ||
        before.patternArrangement != after.patternArrangement
    return when {
        projectChanged -> ProductionCommandResult(
            state = after,
            mutation = ProductionMutation.PROJECT,
            effects = listOf(ProductionEffect.RefreshPattern),
        )
        after != before -> ProductionCommandResult(
            state = after,
            mutation = ProductionMutation.SESSION,
        )
        else -> unchanged(before)
    }
}

private fun sessionFeedback(
    state: SamplerUiState,
    message: String,
): ProductionCommandResult {
    val next = state.copy(statusMessage = message)
    return if (next == state) unchanged(state) else ProductionCommandResult(
        state = next,
        mutation = ProductionMutation.SESSION,
    )
}

private fun unchanged(state: SamplerUiState): ProductionCommandResult = ProductionCommandResult(
    state = state,
    mutation = ProductionMutation.NONE,
)

private const val MINIMUM_CHOP_SECONDS = 0.008f
private const val ZERO_CROSSING_SEARCH_SECONDS = 0.004f
private const val QUICK_SKETCH_SLICE_COUNT = 8
private const val QUICK_SKETCH_UNSAFE_MESSAGE =
    "安全な8つの境界を作れないため、制作は変更していません"
