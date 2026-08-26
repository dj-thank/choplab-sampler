package com.choplab.sampler.audio

/** Reusable primitive frame for realtime/offline stereo rendering without per-frame allocation. */
class MutableStereoFrame(
    var left: Float = 0f,
    var right: Float = 0f,
) {
    fun set(left: Float, right: Float) {
        this.left = left
        this.right = right
    }

    fun clear() = set(0f, 0f)
}

/** Interleaved PCM-16 plus the frame shape required by host output adapters. */
class RenderedPcm(
    val samples: ShortArray,
    val channelCount: Int,
) {
    init {
        require(channelCount in 1..2) { "Only mono and stereo PCM are supported" }
        require(samples.size % channelCount == 0) { "PCM samples must contain complete frames" }
    }

    val frameCount: Int
        get() = samples.size / channelCount
}
