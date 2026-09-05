package com.choplab.sampler.audio

import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.configuredLoopPadIndex

/** Caller owns the playback/focus boundary. A rejected transport must not leave half a beat playing. */
fun startAndroidLayeredTransport(engine: SamplerPlaybackEngine, state: SamplerUiState): Boolean {
    val loop = state.configuredLoopPadIndex()
    if (loop != null && !startAndroidPadLoopSession(engine, state.pads, loop)) return false
    if (!engine.startTransport()) {
        engine.stopAllPlayback()
        return false
    }
    return true
}
