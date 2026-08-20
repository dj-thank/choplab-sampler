package com.choplab.sampler.audio

import kotlin.math.abs
import kotlin.math.pow

const val SCRATCH_GESTURE_IDLE_TIMEOUT_MS = 120L
private const val SCRATCH_SPEED_DEAD_ZONE = 0.08f
private const val SCRATCH_SPEED_CURVE_POWER = 0.82f

fun scratchGestureIsIdle(elapsedMillis: Long): Boolean =
    elapsedMillis.coerceAtLeast(0L) >= SCRATCH_GESTURE_IDLE_TIMEOUT_MS

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
    val raw = normalizeScratchSpeed(pixelsPerSecond / (60f * sensitivityDivisor))
    val magnitude = abs(raw)
    if (magnitude <= SCRATCH_SPEED_DEAD_ZONE) return 0f
    val normalizedMagnitude =
        ((magnitude - SCRATCH_SPEED_DEAD_ZONE) / (4f - SCRATCH_SPEED_DEAD_ZONE))
            .coerceIn(0f, 1f)
    val curvedMagnitude = normalizedMagnitude.pow(SCRATCH_SPEED_CURVE_POWER) * 4f
    return if (raw < 0f) -curvedMagnitude else curvedMagnitude
}

fun scratchDirectionLabel(speed: Float): String = when {
    speed > SCRATCH_SPEED_DEAD_ZONE -> "FORWARD"
    speed < -SCRATCH_SPEED_DEAD_ZONE -> "BACK"
    else -> "HOLD"
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
