package com.choplab.sampler.audio

fun normalizeScratchSpeed(speed: Float): Float =
    if (speed.isFinite()) speed.coerceIn(-4f, 4f) else 0f

class ScratchSpeedSmoother(
    private val smoothing: Double = 0.025,
) {
    private var smoothedSpeed = 0.0

    fun next(targetSpeed: Float): Double {
        if (!smoothedSpeed.isFinite()) smoothedSpeed = 0.0
        val safeTarget = normalizeScratchSpeed(targetSpeed).toDouble()
        smoothedSpeed += (safeTarget - smoothedSpeed) * smoothing
        if (!smoothedSpeed.isFinite()) smoothedSpeed = 0.0
        return smoothedSpeed
    }
}
