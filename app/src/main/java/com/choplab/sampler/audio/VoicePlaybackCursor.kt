package com.choplab.sampler.audio

import com.choplab.sampler.model.PadPlayMode
import kotlin.math.abs

/**
 * Host-testable playback position for one start-inclusive/end-exclusive PAD range.
 * The audio thread owns each instance; advancing it performs no allocation.
 */
internal class VoicePlaybackCursor(
    private val startFrame: Int,
    private val endFrame: Int,
    private val reverse: Boolean,
    private val playMode: PadPlayMode,
) {
    init {
        require(endFrame > startFrame) { "Playback range must not be empty" }
    }

    var position: Double = if (reverse) endFrame - 1.0 else startFrame.toDouble()
        private set

    var finished: Boolean = false
        private set

    fun advance(sourceFrames: Double) {
        if (finished) return
        val distance = abs(sourceFrames)
        val next = if (reverse) position - distance else position + distance
        if (playMode == PadPlayMode.LOOP) {
            val length = (endFrame - startFrame).toDouble()
            val relative = (next - startFrame) % length
            position = startFrame + if (relative < 0.0) relative + length else relative
        } else if (next < startFrame || next >= endFrame) {
            finished = true
        } else {
            position = next
        }
    }
}
