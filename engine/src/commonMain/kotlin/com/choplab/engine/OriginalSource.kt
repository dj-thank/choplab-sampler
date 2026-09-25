package com.choplab.engine

/** Monitor-only source region. It has no PAD identity and is not part of the saved mix graph. */
class OriginalSource(
    val asset: PcmAsset,
    val startFrame: Int = 0,
    val endFrame: Int = asset.frameCount,
    val loop: Boolean = false,
) {
    init { require(startFrame >= 0 && endFrame <= asset.frameCount && endFrame > startFrame) }
}

enum class EngineOutputMode { MONITOR, EXPORT }

/** One preallocated independent source voice, using the shared sample reader and gain smoother. */
internal class OriginalSourceVoice {
    var source: OriginalSource? = null
        private set
    var assetSlot = -1
        private set
    var position = 0
        private set
    var playing = false
        private set
    val monitorGain = ParameterSmoother(1f)
    var outputLeft = 0.0
        private set
    var outputRight = 0.0
        private set
    private var lastDryLeft = 0.0
    private var lastDryRight = 0.0
    private var fromLeft = 0.0
    private var fromRight = 0.0
    private var transitionAge = TRANSITION_FRAMES

    fun set(value: OriginalSource?, slot: Int) {
        val continuePlaying = playing && value != null
        beginTransition()
        source = value
        assetSlot = slot
        position = value?.startFrame ?: 0
        playing = continuePlaying
    }
    fun play(): Boolean {
        val source = source ?: return false
        if (position >= source.endFrame) position = source.startFrame
        beginTransition()
        playing = true
        return true
    }
    fun pause() { beginTransition(); playing = false }
    fun seek(frame: Long): Boolean {
        val source = source ?: return false
        if (frame < source.startFrame || frame > source.endFrame) return false
        beginTransition()
        position = frame.toInt()
        if (position == source.endFrame) playing = false
        return true
    }
    fun stop(immediate: Boolean) {
        if (immediate) {
            lastDryLeft = 0.0; lastDryRight = 0.0
            fromLeft = 0.0; fromRight = 0.0
            transitionAge = TRANSITION_FRAMES
            outputLeft = 0.0; outputRight = 0.0
        } else beginTransition()
        playing = false
        position = source?.startFrame ?: 0
    }
    private fun beginTransition() {
        fromLeft = lastDryLeft
        fromRight = lastDryRight
        transitionAge = 0
    }
    fun render(interpolator: PitchInterpolator) {
        val gain = monitorGain.next().toDouble()
        val source = source
        var targetLeft = 0.0
        var targetRight = 0.0
        if (playing && source != null) {
            targetLeft = interpolator.read(source.asset, position.toDouble(), 1.0, 0,
                source.startFrame, source.endFrame, source.loop)
            targetRight = interpolator.read(source.asset, position.toDouble(), 1.0, 1,
                source.startFrame, source.endFrame, source.loop)
        }
        if (transitionAge < TRANSITION_FRAMES - 1) {
            val blend = smoothUnit(transitionAge.toDouble() / (TRANSITION_FRAMES - 1))
            lastDryLeft = fromLeft + (targetLeft - fromLeft) * blend
            lastDryRight = fromRight + (targetRight - fromRight) * blend
            transitionAge++
        } else {
            lastDryLeft = targetLeft
            lastDryRight = targetRight
            transitionAge = TRANSITION_FRAMES
        }
        outputLeft = lastDryLeft * gain
        outputRight = lastDryRight * gain
        if (playing && source != null) {
            position++
            if (position == source.endFrame) {
                if (source.loop) position = source.startFrame
                else { playing = false; beginTransition() }
            }
        }
    }
    companion object { const val TRANSITION_FRAMES = 96 }
}
