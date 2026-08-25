package com.choplab.sampler.model

/** A retrigger replaces only an older voice owned by the same physical PAD. */
fun samePadVoiceConflictsForRetrigger(activePadIndex: Int, requestedPadIndex: Int): Boolean =
    activePadIndex == requestedPadIndex

/**
 * Returns the one-shot vocal layers that accompany a newly started PAD loop.
 *
 * The loop owner is excluded even when it is itself a vocal take. Otherwise the
 * same PAD would be started once as the loop and again as a companion layer.
 */
fun List<PadModel>.vocalCompanionPadIndicesForLoopStart(loopPadIndex: Int): List<Int> =
    asSequence()
        .filter { pad ->
            pad.globalIndex != loopPadIndex &&
                pad.isAssigned &&
                pad.contentKind == PadContentKind.VOCAL
        }
        .map(PadModel::globalIndex)
        .toList()

data class LoopSessionChokeTransition(
    val state: SamplerUiState,
    val padIndicesToStop: List<Int>,
    val chokeGroup: Int?,
) {
    val stopsLoopSession: Boolean
        get() = chokeGroup != null
}

/**
 * Plans the controller-owned effects when a new PAD chokes the active loop owner.
 *
 * A Beat loop owns the vocal companions started with it. Voice-level choke logic
 * cannot safely infer that session, so controllers stop companions and owner as
 * one session before publishing the requested PAD trigger.
 */
fun SamplerUiState.chokeLoopSessionTransition(triggeredPadIndex: Int): LoopSessionChokeTransition {
    fun unchanged() = LoopSessionChokeTransition(
        state = this,
        padIndicesToStop = emptyList(),
        chokeGroup = null,
    )

    val loopOwnerIndex = loopingPadIndex ?: return unchanged()
    if (triggeredPadIndex == loopOwnerIndex) return unchanged()
    val loopOwner = pads.getOrNull(loopOwnerIndex) ?: return unchanged()
    val triggeredPad = pads.getOrNull(triggeredPadIndex) ?: return unchanged()
    if (!loopOwner.isAssigned || !triggeredPad.isAssigned) return unchanged()
    val group = triggeredPad.chokeGroup.takeIf { it > 0 && loopOwner.chokeGroup == it }
        ?: return unchanged()
    val ownedPads = buildList {
        addAll(pads.vocalCompanionPadIndicesForLoopStart(loopPadIndex = loopOwnerIndex))
        add(loopOwnerIndex)
    }.distinct()

    return LoopSessionChokeTransition(
        state = copy(
            loopingPadIndex = null,
            loopPlayheadFrame = -1,
        ),
        padIndicesToStop = ownedPads,
        chokeGroup = group,
    )
}
