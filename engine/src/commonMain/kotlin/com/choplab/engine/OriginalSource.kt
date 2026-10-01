package com.choplab.engine

import kotlin.math.pow

/** Monitor-only source region. It has no PAD identity and is not part of the saved mix graph. */
class OriginalSource(
    val asset: PcmAsset,
    val startFrame: Int = 0,
    val endFrame: Int = asset.frameCount,
    val loop: Boolean = false,
    /** Endpoint blend, clamped to half the range; keeps the original frame period. */
    val loopCrossfadeFrames: Int = 0,
) {
    init { require(loopCrossfadeFrames in 0..24_000); require(startFrame >= 0 && endFrame <= asset.frameCount && endFrame > startFrame) }
}

enum class EngineOutputMode { MONITOR, EXPORT }

/** One preallocated independent source voice, using the shared sample reader and gain smoother. */
internal class OriginalSourceVoice {
    var source: OriginalSource? = null
        private set
    var assetSlot = -1
        private set
    /** Source frame being read; fractional while the pitch is not zero. */
    var position = 0.0
        private set
    var playing = false
        private set
    /** Source frames per output frame: exactly 1 at zero semitones, so unpitched playback stays sample-exact. */
    private var step = 1.0
    val monitorGain = ParameterSmoother(1f)
    private val pcmCursor = PcmReadCursor()
    var pcmMiss = false
        private set
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
        position = (value?.startFrame ?: 0).toDouble()
        playing = continuePlaying
    }
    fun play(): Boolean {
        val source = source ?: return false
        if (position >= source.endFrame) position = source.startFrame.toDouble()
        beginTransition()
        playing = true
        return true
    }
    fun pause() { beginTransition(); playing = false }
    fun seek(frame: Long): Boolean {
        val source = source ?: return false
        if (frame < source.startFrame || frame > source.endFrame) return false
        beginTransition()
        position = frame.toDouble()
        if (frame == source.endFrame.toLong()) playing = false
        return true
    }
    /** Keeps the read position; a sounding change is blended like a seek so a new interpolation band cannot click. */
    fun pitch(semitones: Float) {
        val next = if (semitones == 0f) 1.0 else 2.0.pow(semitones / 12.0)
        if (next == step) return
        if (playing) beginTransition()
        step = next
    }
    fun stop(immediate: Boolean) {
        if (immediate) {
            lastDryLeft = 0.0; lastDryRight = 0.0
            fromLeft = 0.0; fromRight = 0.0
            transitionAge = TRANSITION_FRAMES
            outputLeft = 0.0; outputRight = 0.0
        } else beginTransition()
        playing = false
        position = (source?.startFrame ?: 0).toDouble()
    }
    private fun beginTransition() {
        fromLeft = lastDryLeft
        fromRight = lastDryRight
        transitionAge = 0
    }
    fun render(interpolator: PitchInterpolator) {
        pcmMiss = false
        val gain = monitorGain.next().toDouble()
        val source = source
        var targetLeft = 0.0
        var targetRight = 0.0
        if (playing && source != null) {
            pcmCursor.reset()
            targetLeft = interpolator.read(source.asset, position, step, 0, source.startFrame, source.endFrame, source.loop, source.loopCrossfadeFrames, cursor = pcmCursor)
            targetRight = interpolator.read(source.asset, position, step, 1, source.startFrame, source.endFrame, source.loop, source.loopCrossfadeFrames, cursor = pcmCursor)
            pcmMiss = pcmCursor.missing
            pcmCursor.clear()
            if (pcmMiss) { targetLeft = 0.0; targetRight = 0.0 }
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
            position += step
            if (position >= source.endFrame) {
                // A loop keeps the fraction past its end, so a pitched loop runs on without a jump.
                if (source.loop) position = source.startFrame + (position - source.endFrame) % (source.endFrame - source.startFrame)
                else { playing = false; beginTransition() }
            }
        }
    }
    companion object { const val TRANSITION_FRAMES = 96 }
}
