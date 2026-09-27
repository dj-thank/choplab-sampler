package com.choplab.engine

import kotlin.math.abs
import kotlin.math.pow

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
    /** Source frame being read; fractional while the pitch is not zero. */
    var position = 0.0
        private set
    var playing = false
        private set
    /** Moved by hand instead of playing: see [startScratch]. */
    var scratching = false
        private set
    /** The original was playing when the hand took it, so letting go plays it on once; read only while scratching. */
    private var resumeAfterScratch = false
    /** Source frames per output frame: exactly 1 at zero semitones, so unpitched playback stays sample-exact. */
    private var step = 1.0
    private var scratchStart = 0
    private var scratchEnd = 0
    private var scratchStep = 0.0
    private var motionFrames = 0
    private var moving = false
    /** Fades the scratch in as the hand starts moving and out as it stops, so neither clicks. */
    private val motion = ParameterSmoother(0f)
    val cut = ParameterSmoother(1f)
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
        position = (value?.startFrame ?: 0).toDouble()
        playing = continuePlaying
        scratching = false
    }
    fun play(): Boolean {
        val source = source ?: return false
        if (position >= source.endFrame) position = source.startFrame.toDouble()
        beginTransition()
        playing = true
        scratching = false
        return true
    }
    /**
     * Pauses playback and holds the original at [frame] within [start, end), heard only while moved. What was playing
     * plays on from where the hand lets go ([endScratch]).
     */
    fun startScratch(frame: Double, start: Int, end: Int): Boolean {
        val source = source ?: return false
        val from = maxOf(start, source.startFrame)
        val to = minOf(end, source.endFrame)
        if (to <= from) return false
        beginTransition()
        if (!scratching) resumeAfterScratch = playing
        playing = false
        scratching = true
        scratchStart = from
        scratchEnd = to
        position = frame.coerceIn(from.toDouble(), to - 1.0)
        scratchStep = 0.0; motionFrames = 0; moving = false
        motion.set(0f, 0)
        cut.set(1f, 0)
        return true
    }
    /**
     * Heads for [frame] over [frames] output frames. A hand faster than [EngineCore.MAX_SCRATCH_SPEED] moves at that
     * limit and arrives late; false only when the original is not being scratched.
     */
    fun scratchTo(frame: Double, frames: Int): Boolean {
        if (!scratching) return false
        val target = frame.coerceIn(scratchStart.toDouble(), scratchEnd - 1.0)
        scratchStep = ((target - position) / frames).coerceIn(-EngineCore.MAX_SCRATCH_SPEED, EngineCore.MAX_SCRATCH_SPEED)
        motionFrames = frames
        return true
    }
    /** Lets go: playback the hand paused plays on once from here; otherwise the original stays paused. */
    fun endScratch() {
        if (!scratching) return
        beginTransition()
        scratching = false
        playing = resumeAfterScratch
    }
    fun pause() { beginTransition(); playing = false; scratching = false }
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
        scratching = false
        position = (source?.startFrame ?: 0).toDouble()
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
        if (scratching && source != null) {
            val nowMoving = motionFrames > 0 && abs(scratchStep) > 1e-12
            if (nowMoving != moving) { moving = nowMoving; motion.set(if (nowMoving) 1f else 0f, TRANSITION_FRAMES) }
            val gate = motion.next().toDouble() * cut.next()
            if (gate > 0.0) {
                // Held still, it keeps reading with the last movement's band, so the fade out cannot click.
                targetLeft = interpolator.read(source.asset, position, scratchStep, 0, scratchStart, scratchEnd, false) * gate
                targetRight = interpolator.read(source.asset, position, scratchStep, 1, scratchStart, scratchEnd, false) * gate
            }
            if (moving) {
                position += scratchStep
                motionFrames--
                if (position < scratchStart || position > scratchEnd - 1.0) motionFrames = 0
                position = position.coerceIn(scratchStart.toDouble(), scratchEnd - 1.0)
            }
        } else if (playing && source != null) {
            targetLeft = interpolator.read(source.asset, position, step, 0, source.startFrame, source.endFrame, source.loop)
            targetRight = interpolator.read(source.asset, position, step, 1, source.startFrame, source.endFrame, source.loop)
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
        if (playing && !scratching && source != null) {
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
