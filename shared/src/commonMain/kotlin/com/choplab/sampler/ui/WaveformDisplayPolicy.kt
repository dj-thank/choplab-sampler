package com.choplab.sampler.ui

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val MAX_WAVEFORM_DISPLAY_GAIN = 16f
private const val WAVEFORM_DISPLAY_TARGET = 0.8f
private const val WAVEFORM_NOISE_FLOOR_PCM = 32

/** Display only: never modifies PCM, PAD gain, analysis input, or exported audio. */
internal fun waveformDisplayGain(
    samples: ShortArray,
    checkCancellation: () -> Unit = {},
): Float {
    var peak = 0
    for (index in samples.indices) {
        if (index % 65_536 == 0) checkCancellation()
        // Convert before abs: abs(Short.MIN_VALUE.toShort()) must not overflow.
        val magnitude = abs(samples[index].toInt())
        if (magnitude > peak) peak = magnitude
    }
    if (peak <= WAVEFORM_NOISE_FLOOR_PCM) return 1f
    return sanitizeWaveformDisplayGain(WAVEFORM_DISPLAY_TARGET * 32_768f / peak)
}

internal fun sanitizeWaveformDisplayGain(gain: Float): Float =
    gain.takeIf { it.isFinite() }?.coerceIn(1f, MAX_WAVEFORM_DISPLAY_GAIN) ?: 1f

internal fun waveformDisplayGainLabel(gain: Float): String {
    val tenths = (sanitizeWaveformDisplayGain(gain) * 10f).roundToInt()
    return "表示のみ ×${tenths / 10}.${tenths % 10}"
}
