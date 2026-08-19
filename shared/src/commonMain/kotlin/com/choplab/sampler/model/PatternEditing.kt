package com.choplab.sampler.model

enum class RepeatGrid(
    val intervalSteps: Int,
    val statusLabel: String,
) {
    QUARTER(intervalSteps = 4, statusLabel = "4つ打ち"),
    EIGHTH(intervalSteps = 2, statusLabel = "8分"),
    SIXTEENTH(intervalSteps = 1, statusLabel = "16分"),
}

enum class LaneStepState {
    OFF,
    OTHER_SOUND,
    SELECTED_SOUND,
}

fun PadModel.canUsePatternSteps(): Boolean =
    isAssigned && playMode != PadPlayMode.LOOP && contentKind != PadContentKind.VOCAL

fun Set<Int>.togglePadStep(
    pad: PadModel,
    stepIndex: Int,
): Set<Int> {
    require(stepIndex in 0 until SamplerConfig.STEP_COUNT)
    if (!pad.canUsePatternSteps()) return this
    val key = stepKey(pad.globalIndex, stepIndex)
    return if (key in this) this - key else this + key
}

fun Set<Int>.recordPadStep(
    pad: PadModel,
    stepIndex: Int,
): Set<Int> {
    require(stepIndex in 0 until SamplerConfig.STEP_COUNT)
    if (!pad.canUsePatternSteps()) return this
    return this + stepKey(pad.globalIndex, stepIndex)
}

fun laneStepState(
    activeSteps: Set<Int>,
    bankIndex: Int,
    selectedPad: Int,
    stepIndex: Int,
): LaneStepState {
    require(bankIndex in 0 until SamplerConfig.BANK_COUNT)
    require(selectedPad in 0 until SamplerConfig.PAD_COUNT)
    require(stepIndex in 0 until SamplerConfig.STEP_COUNT)
    if (stepKey(selectedPad, stepIndex) in activeSteps) return LaneStepState.SELECTED_SOUND
    return if (activeSteps.any { key ->
            key % SamplerConfig.STEP_COUNT == stepIndex &&
                (key / SamplerConfig.STEP_COUNT) / SamplerConfig.PADS_PER_BANK == bankIndex
        }
    ) {
        LaneStepState.OTHER_SOUND
    } else {
        LaneStepState.OFF
    }
}

fun Set<Int>.replacePadSteps(
    padIndex: Int,
    repeatGrid: RepeatGrid,
): Set<Int> {
    require(padIndex in 0 until SamplerConfig.PAD_COUNT)
    return clearPadSteps(padIndex) +
        (0 until SamplerConfig.STEP_COUNT step repeatGrid.intervalSteps)
            .map { step -> stepKey(padIndex, step) }
}

fun Set<Int>.clearPadSteps(padIndex: Int): Set<Int> {
    require(padIndex in 0 until SamplerConfig.PAD_COUNT)
    val firstKey = stepKey(padIndex, 0)
    val lastKeyExclusive = firstKey + SamplerConfig.STEP_COUNT
    return filterNotTo(linkedSetOf()) { key -> key in firstKey until lastKeyExclusive }
}

fun Set<Int>.activeBanksAtStep(stepIndex: Int): Set<Int> {
    require(stepIndex in 0 until SamplerConfig.STEP_COUNT)
    return asSequence()
        .filter { key -> key % SamplerConfig.STEP_COUNT == stepIndex }
        .map { key -> (key / SamplerConfig.STEP_COUNT) / SamplerConfig.PADS_PER_BANK }
        .filter { bank -> bank in 0 until SamplerConfig.BANK_COUNT }
        .toCollection(linkedSetOf())
}

fun Set<Int>.audibleStepKeys(pads: List<PadModel>): Set<Int> =
    filterTo(linkedSetOf()) { key ->
        val padIndex = key / SamplerConfig.STEP_COUNT
        pads.getOrNull(padIndex)?.canUsePatternSteps() == true
    }

fun Set<Int>.hasAudiblePatternContent(pads: List<PadModel>): Boolean =
    pads.any { pad -> pad.isAssigned && pad.playMode == PadPlayMode.LOOP } ||
        pads.any { pad -> pad.isAssigned && pad.contentKind == PadContentKind.VOCAL } ||
        audibleStepKeys(pads).isNotEmpty()

fun Set<Int>.repeatGridForPad(padIndex: Int): RepeatGrid? {
    require(padIndex in 0 until SamplerConfig.PAD_COUNT)
    val selectedSteps = (0 until SamplerConfig.STEP_COUNT).filterTo(linkedSetOf()) { step ->
        stepKey(padIndex, step) in this
    }
    return RepeatGrid.entries.firstOrNull { repeatGrid ->
        selectedSteps == (0 until SamplerConfig.STEP_COUNT step repeatGrid.intervalSteps).toSet()
    }
}
