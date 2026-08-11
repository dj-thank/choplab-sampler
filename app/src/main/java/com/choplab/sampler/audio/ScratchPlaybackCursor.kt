package com.choplab.sampler.audio

/** Start-inclusive, end-exclusive cursor for direct-manipulation scratch playback. */
class ScratchPlaybackCursor(
    private val startFrame: Int,
    private val endFrame: Int,
    initialFrame: Double,
) {
    init {
        require(endFrame > startFrame) { "Scratch range must contain audio" }
        require(initialFrame.isFinite()) { "Scratch position must be finite" }
    }

    private val length = (endFrame - startFrame).toDouble()

    var position: Double = wrap(initialFrame)
        private set

    fun advance(sourceFrames: Double) {
        if (!sourceFrames.isFinite() || sourceFrames == 0.0) return
        position = wrap(position + sourceFrames)
    }

    private fun wrap(value: Double): Double {
        val relative = value - startFrame
        val wrapped = ((relative % length) + length) % length
        return startFrame + wrapped
    }
}
