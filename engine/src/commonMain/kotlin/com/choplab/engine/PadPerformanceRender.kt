package com.choplab.engine

/** Offline only. A recorded press uses the live voice, including ADSR, loop wrap, pitch, tone, gain and pan. */
object PadPerformanceRender {
    /**
     * [releaseAt] is the frame where note-off/choke happened, relative to note-on. Null plays to natural end.
     * [limitFrames] is an explicit timeline/budget boundary. The shared master/limiter is applied later by the mix.
     */
    fun render(pad: Pad, releaseAt: Int?, limitFrames: Int): FloatArray {
        require(limitFrames in 1..PadRender.MAX_FRAMES)
        require(releaseAt == null || releaseAt in 0..limitFrames)
        require(pad.mode != PlayMode.LOOP || releaseAt != null) { "A loop needs an explicit stop" }
        val natural = if (pad.mode == PlayMode.LOOP) limitFrames else PadRender.frames(pad)
        val released = releaseAt?.let { (it.toLong() + pad.releaseFrames).coerceAtMost(limitFrames.toLong()).toInt() }
        val frames = minOf(limitFrames, natural, released ?: limitFrames)
        val output = FloatArray(frames * 2)
        val voice = Voice()
        val interpolator = PitchInterpolator()
        voice.start(pad, 1f, 0)
        for (frame in 0 until frames) {
            if (frame == releaseAt) voice.release()
            voice.render(interpolator)
            output[frame * 2] = voice.outputLeft.toFloat()
            output[frame * 2 + 1] = voice.outputRight.toFloat()
        }
        return output
    }
}
