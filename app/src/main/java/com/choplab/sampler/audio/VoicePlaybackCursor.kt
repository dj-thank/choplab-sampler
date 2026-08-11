package com.choplab.sampler.audio

import com.choplab.sampler.model.PadPlayMode
import kotlin.math.abs

/**
 * Host-testable playback position for one start-inclusive/end-exclusive PAD range.
 * The audio thread owns each instance; advancing it performs no allocation.
 */
internal class VoicePlaybackCursor(
    startFrame: Int,
    endFrame: Int,
    reverse: Boolean,
    playMode: PadPlayMode,
) {
    private var startFrame = 0
    private var endFrame = 1
    private var reverse = false
    private var playMode = PadPlayMode.ONE_SHOT

    init {
        reset(startFrame, endFrame, reverse, playMode)
    }

    var position: Double = 0.0
        private set

    var finished: Boolean = false
        private set

    fun reset(
        startFrame: Int,
        endFrame: Int,
        reverse: Boolean,
        playMode: PadPlayMode,
    ) {
        require(endFrame > startFrame) { "Playback range must not be empty" }
        this.startFrame = startFrame
        this.endFrame = endFrame
        this.reverse = reverse
        this.playMode = playMode
        position = if (reverse) endFrame - 1.0 else startFrame.toDouble()
        finished = false
    }

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
