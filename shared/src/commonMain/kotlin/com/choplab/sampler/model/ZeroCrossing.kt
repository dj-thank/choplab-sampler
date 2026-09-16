package com.choplab.sampler.model

/** Stereo-aware, bounded trim snapping. PCM and caller-owned range are never mutated. */
fun snapFrameToZeroCrossing(
    audio: PcmAudio,
    targetFrame: Int,
    lowerBound: Int,
    upperBound: Int,
): Int {
    if (audio.frameCount < 2) return targetFrame.coerceIn(lowerBound, upperBound)

    val safeLower = lowerBound.coerceIn(0, audio.frameCount)
    val safeUpper = upperBound.coerceIn(safeLower, audio.frameCount)
    val target = targetFrame.coerceIn(safeLower, safeUpper)
    if (target == 0 || target == audio.frameCount) return target

    val radius = (audio.sampleRate * ZERO_CROSSING_SEARCH_SECONDS)
        .toInt()
        .coerceIn(32, 1_024)
    val from = maxOf(1, safeLower, target - radius)
    val to = minOf(audio.frameCount - 1, safeUpper, target + radius)
    if (from > to) return target

    var bestCrossing = -1
    var bestDistance = Int.MAX_VALUE
    for (frame in from..to) {
        // A signed downmix can cancel an entire anti-phase source. Only accept a
        // crossing shared by every channel; silence is compatible with either sign.
        var crossesZero = true
        var channel = 0
        while (channel < audio.channelCount) {
            val previous = audio.samples[(frame - 1) * audio.channelCount + channel].toInt()
            val current = audio.samples[frame * audio.channelCount + channel].toInt()
            if (!((previous <= 0 && current >= 0) || (previous >= 0 && current <= 0))) {
                crossesZero = false
                break
            }
            channel++
        }
        if (crossesZero) {
            val distance = kotlin.math.abs(frame - target)
            if (distance < bestDistance) {
                bestCrossing = frame
                bestDistance = distance
            }
        }
    }
    if (bestCrossing >= 0) return bestCrossing

    var quietestFrame = target
    var quietestMagnitude = channelMagnitude(audio, target)
    for (frame in from..to) {
        val magnitude = channelMagnitude(audio, frame)
        if (magnitude < quietestMagnitude) {
            quietestFrame = frame
            quietestMagnitude = magnitude
        }
    }
    return quietestFrame
}

private fun channelMagnitude(audio: PcmAudio, frame: Int): Int {
    var magnitude = 0
    var channel = 0
    while (channel < audio.channelCount) {
        magnitude = maxOf(magnitude, kotlin.math.abs(audio.samples[frame * audio.channelCount + channel].toInt()))
        channel++
    }
    return magnitude
}

private const val ZERO_CROSSING_SEARCH_SECONDS = 0.004f
