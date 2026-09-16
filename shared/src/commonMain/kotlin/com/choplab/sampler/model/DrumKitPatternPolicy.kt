package com.choplab.sampler.model

private val drumKitPadRange: IntRange = run {
    val start = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
    start until start + SamplerConfig.DRUM_KIT_PAD_COUNT
}

/** Existing sounds or a saved groove make this a sound change, never a rhythm reset. */
fun SamplerUiState.drumKitNeedsStarterPattern(): Boolean =
    drumKitApplyDecision(pads) == DrumKitApplyDecision.APPLY &&
        materializedPatternArrangement().storedStepsBySlot.none { steps ->
            steps.any { it / SamplerConfig.STEP_COUNT in drumKitPadRange }
        }

/** Seed only the selected variation on first installation, retaining every other lane and variation. */
fun SamplerUiState.withInitialDrumKitPattern(starterSteps: Set<Int>): SamplerUiState {
    require(starterSteps.all { key ->
        key >= 0 && key / SamplerConfig.STEP_COUNT in drumKitPadRange
    }) { "Starter rhythm must use the drum kit slots" }
    if (!drumKitNeedsStarterPattern() || starterSteps.isEmpty()) return this
    val arrangement = materializedPatternArrangement()
    val stored = arrangement.storedStepsBySlot.toMutableList()
    stored[arrangement.selectedSlot] = activeSteps + starterSteps
    return copy(
        activeSteps = stored[arrangement.selectedSlot],
        patternArrangement = arrangement.copy(storedStepsBySlot = stored),
    )
}
