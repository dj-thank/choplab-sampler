package com.choplab.engine

/** Offline only. A recorded press uses the live voice, including ADSR, loop wrap, pitch, tone, gain and pan. */
object PadPerformanceRender {
    /**
     * [releaseAt] is the frame where note-off/choke happened, relative to note-on. Null plays to natural end.
     * [limitFrames] is an explicit timeline/budget boundary. The shared master/limiter is applied later by the mix.
     */
    fun render(pad: Pad, releaseAt: Int?, limitFrames: Int, stopAt: Int? = null,
               prepared: (List<PcmWindow>, () -> Unit) -> Unit = { windows, render -> require(windows.isEmpty()); render() }): FloatArray {
        val frames = frames(pad, releaseAt, limitFrames, stopAt)
        val output = FloatArray(frames * 2)
        val voice = Voice()
        val interpolator = PitchInterpolator()
        voice.start(pad, 1f, 0)
        var first = 0
        while (first < frames) {
            val end = minOf(frames, first + 512)
            prepared(PcmWindow.pad(pad, voice.position, pad.step, end - first)) {
                for (frame in first until end) {
                if (frame == releaseAt) voice.release()
                if (frame == stopAt) voice.release(EngineCore.STEAL_FADE_FRAMES)
                voice.render(interpolator)
                check(!voice.pcmMiss) { "PCM missing during performed PAD render" }
                output[frame * 2] = voice.outputLeft.toFloat()
                output[frame * 2 + 1] = voice.outputRight.toFloat()
                }
            }
            first = end
        }
        return output
    }
    /** Worker admission uses exactly the same output length before allocating its PCM buffer. */
    fun frames(pad: Pad, releaseAt: Int?, limitFrames: Int, stopAt: Int? = null): Int {
        require(limitFrames in 1..PadRender.MAX_FRAMES)
        require(releaseAt == null || releaseAt in 0..limitFrames)
        require(stopAt == null || stopAt in 0..limitFrames)
        require(pad.mode != PlayMode.LOOP || releaseAt != null || stopAt != null) { "A loop needs an explicit stop" }
        val natural = if (pad.mode == PlayMode.LOOP) limitFrames else PadRender.frames(pad)
        val released = releaseAt?.let { (it.toLong() + pad.releaseFrames).coerceAtMost(limitFrames.toLong()).toInt() }
        val stopped = stopAt?.let { (it.toLong() + EngineCore.STEAL_FADE_FRAMES).coerceAtMost(limitFrames.toLong()).toInt() }
        return minOf(limitFrames, natural, released ?: limitFrames, stopped ?: limitFrames)
    }

}
