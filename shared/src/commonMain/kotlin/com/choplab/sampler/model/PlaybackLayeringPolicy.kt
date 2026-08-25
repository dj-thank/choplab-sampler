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
