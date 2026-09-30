package com.choplab.engine

/** Worker-only pre-master capture of a held repeat, including the original PAD's stereo and envelope. */
object NoteRepeatRender {
    fun frames(pad: Pad, releaseAt: Int, limitFrames: Int, stopAt: Int? = null): Int {
        require(limitFrames in 1..PadRender.MAX_FRAMES && releaseAt in 0..limitFrames)
        require(stopAt == null || stopAt in 0..limitFrames)
        return minOf(limitFrames.toLong(), releaseAt.toLong() + pad.releaseFrames,
            stopAt?.let { it.toLong() + EngineCore.STEAL_FADE_FRAMES } ?: Long.MAX_VALUE).toInt()
    }

    fun render(pad: Pad, tempo: Tempo, ticks: Int, releaseAt: Int, limitFrames: Int, stopAt: Int? = null,
               prepared: (List<PcmWindow>, () -> Unit) -> Unit = { windows, render -> require(windows.isEmpty()); render() }): FloatArray {
        val frames = frames(pad, releaseAt, limitFrames, stopAt)
        val clock = NoteRepeatClock().apply { reset(tempo, ticks) }
        val voices = arrayOf(Voice(), Voice())
        val interpolator = PitchInterpolator()
        val output = FloatArray(frames * 2)
        var current = 0
        var held = true
        check(clock.pulse())
        voices[current].start(pad, 1f, 0)
        var first = 0
        while (first < frames) {
            if (first == releaseAt || first == stopAt) {
                held = false
                for (voice in voices) voice.release(if (first == stopAt) EngineCore.STEAL_FADE_FRAMES else pad.releaseFrames)
            }
            if (held && clock.pulse()) {
                voices[current].release(EngineCore.STEAL_FADE_FRAMES)
                current = 1 - current
                voices[current].start(pad, 1f, first.toLong())
            }
            var end = minOf(frames, first + 512)
            if (held) end = minOf(end, first + clock.framesUntilPulse(), releaseAt)
            if (stopAt != null && stopAt > first) end = minOf(end, stopAt)
            check(end > first)
            val windows = buildList {
                for (voice in voices) if (voice.pad != null) addAll(PcmWindow.pad(pad, voice.position, pad.step, end - first))
            }
            prepared(windows) {
                for (frame in first until end) {
                    var left = 0.0; var right = 0.0
                    for (voice in voices) {
                        voice.render(interpolator)
                        check(!voice.pcmMiss) { "PCM missing during repeated PAD render" }
                        left += voice.outputLeft; right += voice.outputRight
                    }
                    output[frame * 2] = left.toFloat(); output[frame * 2 + 1] = right.toFloat()
                }
            }
            clock.advance(end - first)
            first = end
        }
        return output
    }
}
