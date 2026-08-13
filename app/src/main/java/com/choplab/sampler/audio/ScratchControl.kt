package com.choplab.sampler.audio

fun normalizeScratchSpeed(speed: Float): Float =
    if (speed.isFinite()) speed.coerceIn(-4f, 4f) else 0f

fun scratchSpeedFromGesture(
    deltaPixels: Float,
    elapsedMillis: Long,
    sensitivityDivisor: Float,
): Float {
    if (!deltaPixels.isFinite() || !sensitivityDivisor.isFinite() || sensitivityDivisor <= 0f) {
        return 0f
    }
    val safeElapsed = elapsedMillis.coerceAtLeast(1L)
    val pixelsPerSecond = deltaPixels * 1_000f / safeElapsed
    // Preserve the previous 60 Hz feel while removing pointer-event-rate dependence.
    return normalizeScratchSpeed(pixelsPerSecond / (60f * sensitivityDivisor))
}

fun scratchProgress(frame: Int, startFrame: Int, endFrame: Int): Float {
    if (endFrame <= startFrame) return 0f
    return ((frame - startFrame).toFloat() / (endFrame - startFrame))
        .coerceIn(0f, 1f)
}

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
