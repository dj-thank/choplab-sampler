package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import kotlin.math.abs
import kotlin.math.max

/** Sampled visual bounds, not a mono downmix or an exhaustive peak meter. */
data class WaveformEnvelope(
    val minimums: FloatArray,
    val maximums: FloatArray,
    val pixelStep: Int,
)

private const val MAX_WAVEFORM_BUCKETS = 8_192

/** Bounded work and storage even for an invalid/extreme viewport. PCM is never modified. */
fun buildWaveformEnvelope(
    samples: ShortArray,
    visibleStart: Int,
    visibleEnd: Int,
    pixelWidth: Int,
    pixelStep: Int = 2,
    channelCount: Int = 1,
): WaveformEnvelope {
    require(channelCount in 1..2 && samples.size % channelCount == 0) {
        "Waveform PCM must contain complete mono or stereo frames"
    }
    val start = visibleStart.coerceIn(0, samples.size / channelCount)
    val end = visibleEnd.coerceIn(start, samples.size / channelCount)
    val step = maxOf(
        pixelStep.coerceAtLeast(1),
        ((pixelWidth.coerceAtLeast(0).toLong() + MAX_WAVEFORM_BUCKETS - 1) / MAX_WAVEFORM_BUCKETS).toInt(),
    )
    if (start == end || pixelWidth <= 0) return emptyEnvelope(step)
    val count = ((pixelWidth.toLong() + step - 1) / step).toInt()
    return sampleBuckets(samples, channelCount, start, end, count, step, pixelWidth, 48)
}

private fun emptyEnvelope(step: Int = 1) = WaveformEnvelope(FloatArray(0), FloatArray(0), step)

/** At most sampleBudget + 1 frames per bucket, including the final frame. */
private fun sampleBuckets(
    samples: ShortArray,
    channels: Int,
    start: Int,
    end: Int,
    count: Int,
    pixelStep: Int,
    pixelWidth: Int,
    sampleBudget: Int,
): WaveformEnvelope {
    val minimums = FloatArray(count)
    val maximums = FloatArray(count)
    val span = end - start
    for (bucket in 0 until count) {
        val x = bucket.toLong() * pixelStep
        val from = (start + span.toLong() * x / pixelWidth).toInt().coerceAtMost(end - 1)
        val to = (start + span.toLong() * minOf(x + pixelStep, pixelWidth.toLong()) / pixelWidth)
            .toInt().coerceIn(from + 1, end)
        val stride = max(1, ((to - from).toLong() + sampleBudget - 1).div(sampleBudget).toInt())
        var minimum = 0f
        var maximum = 0f
        var frame = from
        while (true) {
            var channel = 0
            while (channel < channels) {
                // Extrema across channels keep anti-phase and one-sided audio visible.
                val value = samples[frame * channels + channel] / 32_768f
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                channel++
            }
            if (frame == to - 1) break
            frame = minOf(frame.toLong() + stride, (to - 1).toLong()).toInt()
        }
        minimums[bucket] = minimum
        maximums[bucket] = maximum
    }
    return WaveformEnvelope(minimums, maximums, pixelStep)
}

internal fun buildPadMiniPeaks(pad: PadModel): FloatArray {
    val audio = pad.audio ?: return FloatArray(0)
    if (!pad.isAssigned || audio.frameCount == 0) return FloatArray(0)
    val start = pad.startFrame.coerceIn(0, audio.frameCount - 1)
    val end = pad.endFrame.coerceIn(start + 1, audio.frameCount)
    val envelope = sampleBuckets(audio.samples, audio.channelCount, start, end, 9, 1, 9, 24)
    // Reuse one output array rather than allocating a second normalized copy.
    val peaks = envelope.maximums
    var strongest = 0.08f
    for (index in peaks.indices) {
        peaks[index] = max(abs(envelope.minimums[index]), peaks[index])
        strongest = max(strongest, peaks[index])
    }
    for (index in peaks.indices) peaks[index] = (peaks[index] / strongest).coerceIn(0.12f, 1f)
    return peaks
}

internal fun buildSliceEnvelope(pad: PadModel): WaveformEnvelope {
    val audio = pad.audio ?: return emptyEnvelope()
    if (!pad.isAssigned || audio.frameCount == 0) return emptyEnvelope()
    val start = pad.startFrame.coerceIn(0, audio.frameCount - 1)
    val end = pad.endFrame.coerceIn(start + 1, audio.frameCount)
    val count = minOf(512, end - start)
    val envelope = sampleBuckets(audio.samples, audio.channelCount, start, end, count, 1, count, 32)
    var strongest = 0.05f
    for (index in envelope.minimums.indices) {
        strongest = max(strongest, max(abs(envelope.minimums[index]), envelope.maximums[index]))
    }
    // Primitive arrays avoid per-bucket Pair/boxed Float creation in the timeline.
    for (index in envelope.minimums.indices) {
        envelope.minimums[index] /= strongest
        envelope.maximums[index] /= strongest
    }
    return envelope
}
