package com.choplab.engine

import kotlin.math.abs

/** One preallocated HAND reader. Shares SOURCE's immutable PCM; never owns or changes its transport. */
internal class OriginalHandVoice {
    private var source: OriginalSource? = null
    var holding = false
        private set
    var position = 0.0
        private set
    private var start = 0
    private var end = 0
    private var step = 0.0
    private var motionFrames = 0
    private var moving = false
    private val motion = ParameterSmoother(0f)
    val cut = ParameterSmoother(1f)
    val monitorGain = ParameterSmoother(1f)
    private var dryLeft = 0.0
    private var dryRight = 0.0
    private var fromLeft = 0.0
    private var fromRight = 0.0
    private var transitionAge = EngineCore.SCRATCH_MOTION_FRAMES
    var outputLeft = 0.0
        private set
    var outputRight = 0.0
        private set
    /** The reserved primary slot also covers the release tail; it is returned once the tail ends. */
    val active: Boolean get() = holding || transitionAge < EngineCore.SCRATCH_MOTION_FRAMES

    fun start(source: OriginalSource, frame: Double, first: Int, last: Int) {
        transition()
        this.source = source
        start = maxOf(first, source.startFrame)
        end = minOf(last, source.endFrame)
        position = frame.coerceIn(start.toDouble(), end - 1.0)
        holding = true
        step = 0.0; motionFrames = 0; moving = false
        motion.set(0f, 0); cut.set(1f, 0)
    }

    fun move(frame: Double, frames: Int): Boolean {
        if (!holding) return false
        val target = frame.coerceIn(start.toDouble(), end - 1.0)
        step = ((target - position) / frames).coerceIn(-EngineCore.MAX_SCRATCH_SPEED, EngineCore.MAX_SCRATCH_SPEED)
        motionFrames = frames
        return true
    }

    fun end(immediate: Boolean = false) {
        if (!holding && !immediate) return
        if (!active) return
        if (immediate) {
            dryLeft = 0.0; dryRight = 0.0; fromLeft = 0.0; fromRight = 0.0
            outputLeft = 0.0; outputRight = 0.0; transitionAge = EngineCore.SCRATCH_MOTION_FRAMES
        } else transition()
        source = null // The release tail holds two scalar samples, not another PCM lease.
        holding = false; moving = false; motionFrames = 0
    }

    private fun transition() {
        fromLeft = dryLeft; fromRight = dryRight; transitionAge = 0
    }

    fun render(interpolator: PitchInterpolator) {
        val gain = monitorGain.next().toDouble()
        var left = 0.0
        var right = 0.0
        val data = source
        if (holding && data != null) {
            val nowMoving = motionFrames > 0 && abs(step) > 1e-12
            if (nowMoving != moving) { moving = nowMoving; motion.set(if (moving) 1f else 0f, EngineCore.SCRATCH_MOTION_FRAMES) }
            val gate = motion.next().toDouble() * cut.next()
            if (gate > 0.0) {
                left = interpolator.read(data.asset, position, step, 0, start, end, false) * gate
                right = interpolator.read(data.asset, position, step, 1, start, end, false) * gate
            }
            if (moving) {
                position += step
                motionFrames--
                if (position < start || position > end - 1.0) motionFrames = 0
                position = position.coerceIn(start.toDouble(), end - 1.0)
            }
        }
        val length = EngineCore.SCRATCH_MOTION_FRAMES
        if (transitionAge < length - 1) {
            val blend = smoothUnit(transitionAge.toDouble() / (length - 1))
            dryLeft = fromLeft + (left - fromLeft) * blend
            dryRight = fromRight + (right - fromRight) * blend
            transitionAge++
        } else {
            dryLeft = left; dryRight = right; transitionAge = length
        }
        outputLeft = dryLeft * gain
        outputRight = dryRight * gain
    }
}
