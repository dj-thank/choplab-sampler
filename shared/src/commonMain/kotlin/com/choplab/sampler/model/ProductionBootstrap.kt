package com.choplab.sampler.model

enum class ProjectLaunchTarget {
    CAPTURE,
    CHOP,
    BEAT,
}

fun inferProjectLaunchTarget(
    state: SamplerUiState,
    starterOnly: Boolean = false,
): ProjectLaunchTarget = when {
    starterOnly && state.currentAudio != null -> ProjectLaunchTarget.CHOP
    starterOnly -> ProjectLaunchTarget.CAPTURE
    state.hasAudiblePlaybackPatternContent() -> ProjectLaunchTarget.BEAT
    state.currentAudio != null -> ProjectLaunchTarget.CHOP
    state.pads.any(PadModel::isAssigned) -> ProjectLaunchTarget.BEAT
    else -> ProjectLaunchTarget.CAPTURE
}

fun starterDrumKitInstallationAllowed(state: SamplerUiState): Boolean =
    state.pads.none(PadModel::isAssigned) &&
        state.activeSteps.isEmpty() &&
        state.materializedPatternArrangement() == PatternArrangement()

sealed interface ScratchReturnTarget {
    data object None : ScratchReturnTarget
    data object Transport : ScratchReturnTarget
    data class PadLoop(val padIndex: Int) : ScratchReturnTarget
}

fun selectScratchReturnTarget(state: SamplerUiState): ScratchReturnTarget {
    state.loopingPadIndex
        ?.takeIf { index -> state.pads.getOrNull(index)?.isAssigned == true }
        ?.let { return ScratchReturnTarget.PadLoop(it) }
    return if (state.transportPlaying && state.hasAudiblePlaybackPatternContent()) {
        ScratchReturnTarget.Transport
    } else {
        ScratchReturnTarget.None
    }
}

fun scratchReturnTargetIsValid(
    target: ScratchReturnTarget,
    state: SamplerUiState,
): Boolean = when (target) {
    ScratchReturnTarget.None -> false
    ScratchReturnTarget.Transport -> state.hasAudiblePlaybackPatternContent()
    is ScratchReturnTarget.PadLoop -> state.pads.getOrNull(target.padIndex)?.isAssigned == true
}
