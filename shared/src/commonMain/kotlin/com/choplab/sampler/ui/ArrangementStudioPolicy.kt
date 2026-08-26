package com.choplab.sampler.ui

import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.materializedPatternArrangement
import com.choplab.sampler.model.patternVariationLabel

data class ArrangementStudioPresentation(
    val selectedSlot: Int,
    val selectedPatternLabel: String,
    val copyLabel: String,
    val copyDestinationLabel: String,
    val copyNeedsConfirmation: Boolean,
    val sectionSlots: List<Int>,
    val sectionLabels: List<String>,
    val songModeEnabled: Boolean,
    val playbackModeLabel: String,
    val editEnabled: Boolean,
    val guidance: String,
)

fun arrangementStudioPresentation(state: SamplerUiState): ArrangementStudioPresentation {
    val arrangement = state.materializedPatternArrangement()
    val selected = patternVariationLabel(arrangement.selectedSlot)
    val destination = patternVariationLabel(1 - arrangement.selectedSlot)
    val enabled = !state.transportPlaying
    return ArrangementStudioPresentation(
        selectedSlot = arrangement.selectedSlot,
        selectedPatternLabel = "パターン${selected}を編集中",
        copyLabel = "${selected}を${destination}へコピー",
        copyDestinationLabel = destination,
        copyNeedsConfirmation = arrangement.storedStepsBySlot[1 - arrangement.selectedSlot]
            .let { destinationSteps -> destinationSteps.isNotEmpty() && destinationSteps != state.activeSteps },
        sectionSlots = arrangement.songSections,
        sectionLabels = arrangement.songSections.mapIndexed { index, slot ->
            "${index + 1}小節 ${patternVariationLabel(slot)}"
        },
        songModeEnabled = arrangement.songModeEnabled,
        playbackModeLabel = if (arrangement.songModeEnabled) "Song 4小節" else "Pattern 1小節",
        editEnabled = enabled,
        guidance = if (enabled) {
            "A/Bを選び、閉じて16ステップを編集。4小節の並びは再生・WAV書出しへ反映されます"
        } else {
            "ビートを停止してからA/Bや4小節の並びを変更してください"
        },
    )
}
