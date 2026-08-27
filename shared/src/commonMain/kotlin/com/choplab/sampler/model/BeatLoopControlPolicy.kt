package com.choplab.sampler.model

/**
 * An owned loop remains stoppable. Starting a new owner is an edit plus playback
 * request and therefore waits for both document and recording ownership.
 */
val SamplerUiState.beatLoopControlEnabled: Boolean
    get() = loopingPadIndex != null || (
        !isLoading &&
            !recordingSession.isActive &&
            pads.getOrNull(selectedPad)?.isAssigned == true
        )
