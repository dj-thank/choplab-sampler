package com.choplab.sampler.audio

/**
 * Called on the audio thread after draining commands. Silence still reaches AudioTrack;
 * only empty per-sample mixing is skipped. A running transport must advance even when
 * no voice is active, and release tails/scratch sources must never be mistaken for idle.
 * The scan allocates nothing and checks at most the fixed-size voice pool once per block.
 */
internal fun activeRenderFrameCount(
    blockFrames: Int,
    transportRunning: Boolean,
    sourceActive: Boolean,
    scratchActive: Boolean,
    voices: Array<SamplerEngine.Voice>,
): Int {
    if (transportRunning || sourceActive || scratchActive) return blockFrames
    var index = 0
    while (index < voices.size) {
        if (voices[index].active) return blockFrames
        index++
    }
    return 0
}
