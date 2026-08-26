package com.choplab.sampler.model

const val PATTERN_VARIATION_COUNT = 2
const val SONG_SECTION_COUNT = 4

/**
 * Bounded first Song tracer: two 16-step variations and four one-bar sections.
 *
 * [SamplerUiState.activeSteps] remains the editable truth for [selectedSlot].
 * Call [SamplerUiState.materializedPatternArrangement] before persistence,
 * playback, export, or changing the selected slot.
 */
data class PatternArrangement(
    val storedStepsBySlot: List<Set<Int>> = List(PATTERN_VARIATION_COUNT) { emptySet() },
    val selectedSlot: Int = 0,
    val songSections: List<Int> = List(SONG_SECTION_COUNT) { 0 },
    val songModeEnabled: Boolean = false,
) {
    init {
        require(storedStepsBySlot.size == PATTERN_VARIATION_COUNT) {
            "Exactly $PATTERN_VARIATION_COUNT pattern variations are required"
        }
        require(selectedSlot in 0 until PATTERN_VARIATION_COUNT) { "Selected pattern is out of bounds" }
        require(songSections.size == SONG_SECTION_COUNT) {
            "Exactly $SONG_SECTION_COUNT Song sections are required"
        }
        require(songSections.all { it in 0 until PATTERN_VARIATION_COUNT }) {
            "Song section references an unknown pattern"
        }
        require(storedStepsBySlot.all { steps -> steps.all(::validPatternStepKey) }) {
            "Pattern contains an out-of-bounds step"
        }
    }
}

fun patternVariationLabel(slot: Int): String = when (slot) {
    0 -> "A"
    1 -> "B"
    else -> error("Pattern slot is out of bounds")
}

fun SamplerUiState.materializedPatternArrangement(): PatternArrangement {
    require(activeSteps.all(::validPatternStepKey)) { "Active pattern contains an out-of-bounds step" }
    val selected = patternArrangement.selectedSlot
    if (patternArrangement.storedStepsBySlot[selected] == activeSteps) return patternArrangement
    val stored = patternArrangement.storedStepsBySlot.toMutableList()
    stored[selected] = activeSteps.toSet()
    return patternArrangement.copy(storedStepsBySlot = stored)
}

fun SamplerUiState.selectPatternVariation(slot: Int): SamplerUiState {
    if (transportPlaying) return arrangementBlockedWhilePlaying()
    if (slot !in 0 until PATTERN_VARIATION_COUNT) {
        return copy(statusMessage = "選べないパターンです")
    }
    val materialized = materializedPatternArrangement()
    if (slot == materialized.selectedSlot) return this
    return copy(
        activeSteps = materialized.storedStepsBySlot[slot],
        patternArrangement = materialized.copy(selectedSlot = slot),
        statusMessage = "パターン${patternVariationLabel(slot)}を編集中です",
    )
}

fun SamplerUiState.duplicateSelectedPatternToOther(): SamplerUiState {
    if (transportPlaying) return arrangementBlockedWhilePlaying()
    val materialized = materializedPatternArrangement()
    val destination = 1 - materialized.selectedSlot
    val stored = materialized.storedStepsBySlot.toMutableList()
    stored[destination] = activeSteps.toSet()
    return copy(
        activeSteps = activeSteps.toSet(),
        patternArrangement = materialized.copy(
            storedStepsBySlot = stored,
            selectedSlot = destination,
        ),
        statusMessage = "現在のビートをパターン${patternVariationLabel(destination)}へコピーしました",
    )
}

fun SamplerUiState.toggleSongSectionPattern(sectionIndex: Int): SamplerUiState {
    if (transportPlaying) return arrangementBlockedWhilePlaying()
    if (sectionIndex !in 0 until SONG_SECTION_COUNT) {
        return copy(statusMessage = "選べないSong sectionです")
    }
    val materialized = materializedPatternArrangement()
    val sections = materialized.songSections.toMutableList()
    sections[sectionIndex] = 1 - sections[sectionIndex]
    return copy(
        patternArrangement = materialized.copy(songSections = sections),
        statusMessage = "${sectionIndex + 1}小節目をパターン${patternVariationLabel(sections[sectionIndex])}にしました",
    )
}

fun SamplerUiState.toggleSongMode(): SamplerUiState {
    if (transportPlaying) return arrangementBlockedWhilePlaying()
    val materialized = materializedPatternArrangement()
    val enabled = !materialized.songModeEnabled
    return copy(
        patternArrangement = materialized.copy(songModeEnabled = enabled),
        statusMessage = if (enabled) {
            "Song mode: 4小節の並びを再生・書き出します"
        } else {
            "Pattern mode: 選択中の1小節を繰り返します"
        },
    )
}

fun SamplerUiState.patternSequenceForPlayback(): List<Set<Int>> {
    val materialized = materializedPatternArrangement()
    return if (materialized.songModeEnabled) {
        materialized.songSections.map(materialized.storedStepsBySlot::get)
    } else {
        listOf(activeSteps)
    }
}

fun SamplerUiState.patternSequenceForExport(repeatedPatternBars: Int = SONG_SECTION_COUNT): List<Set<Int>> {
    require(repeatedPatternBars in 1..64) { "Export bar count is out of bounds" }
    val playback = patternSequenceForPlayback()
    return if (materializedPatternArrangement().songModeEnabled) {
        playback
    } else {
        List(repeatedPatternBars) { activeSteps }
    }
}

fun SamplerUiState.hasAnyPatternSteps(): Boolean =
    materializedPatternArrangement().storedStepsBySlot.any(Set<Int>::isNotEmpty)

fun SamplerUiState.hasAudiblePlaybackPatternContent(): Boolean =
    patternSequenceForPlayback().any { steps -> steps.hasAudiblePatternContent(pads) }

fun SamplerUiState.audiblePlaybackStepCount(): Int =
    patternSequenceForPlayback().sumOf { steps -> steps.audibleStepKeys(pads).size }

fun SamplerUiState.removePadFromEveryPattern(padIndex: Int): SamplerUiState {
    require(padIndex in 0 until SamplerConfig.PAD_COUNT) { "PAD is out of bounds" }
    val materialized = materializedPatternArrangement()
    val stored = materialized.storedStepsBySlot.map { steps ->
        steps.filterNotTo(linkedSetOf()) { key -> key / SamplerConfig.STEP_COUNT == padIndex }
    }
    return copy(
        activeSteps = stored[materialized.selectedSlot],
        patternArrangement = materialized.copy(storedStepsBySlot = stored),
    )
}

fun SamplerUiState.clearEveryPattern(): SamplerUiState {
    val materialized = materializedPatternArrangement()
    val cleared = List(PATTERN_VARIATION_COUNT) { emptySet<Int>() }
    return copy(
        activeSteps = emptySet(),
        patternArrangement = materialized.copy(storedStepsBySlot = cleared),
    )
}

fun SamplerUiState.replaceBankStepsAcrossPatterns(
    bankStart: Int,
    bankEndExclusive: Int,
    selectedPatternReplacement: Set<Int> = emptySet(),
): SamplerUiState {
    require(bankStart in 0 until bankEndExclusive && bankEndExclusive <= SamplerConfig.PAD_COUNT) {
        "Bank range is out of bounds"
    }
    require(selectedPatternReplacement.all(::validPatternStepKey)) { "Replacement pattern is invalid" }
    val materialized = materializedPatternArrangement()
    val stored = materialized.storedStepsBySlot.mapIndexed { slot, steps ->
        val retained = steps.filterNotTo(linkedSetOf()) { key ->
            key / SamplerConfig.STEP_COUNT in bankStart until bankEndExclusive
        }
        if (slot == materialized.selectedSlot) retained + selectedPatternReplacement else retained
    }
    return copy(
        activeSteps = stored[materialized.selectedSlot],
        patternArrangement = materialized.copy(storedStepsBySlot = stored),
    )
}

private fun SamplerUiState.arrangementBlockedWhilePlaying(): SamplerUiState =
    copy(statusMessage = "ビートを停止してからパターンやSongの並びを変更してください")

private fun validPatternStepKey(key: Int): Boolean =
    key in 0 until SamplerConfig.PAD_COUNT * SamplerConfig.STEP_COUNT
