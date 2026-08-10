package com.choplab.sampler.model

enum class RepeatGrid(
    val intervalSteps: Int,
    val statusLabel: String,
) {
    QUARTER(intervalSteps = 4, statusLabel = "4つ打ち"),
    EIGHTH(intervalSteps = 2, statusLabel = "8分"),
    SIXTEENTH(intervalSteps = 1, statusLabel = "16分"),
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
        pads.getOrNull(padIndex)?.isAssigned == true
    }

fun Set<Int>.repeatGridForPad(padIndex: Int): RepeatGrid? {
    require(padIndex in 0 until SamplerConfig.PAD_COUNT)
    val selectedSteps = (0 until SamplerConfig.STEP_COUNT).filterTo(linkedSetOf()) { step ->
        stepKey(padIndex, step) in this
    }
    return RepeatGrid.entries.firstOrNull { repeatGrid ->
        selectedSteps == (0 until SamplerConfig.STEP_COUNT step repeatGrid.intervalSteps).toSet()
    }
}
