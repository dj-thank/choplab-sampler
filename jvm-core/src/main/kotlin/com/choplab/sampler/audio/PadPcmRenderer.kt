package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import kotlin.math.ceil
import kotlin.math.floor

/** Host-side rendering of one PAD voice using the same core controls as the Android engine. */
object PadPcmRenderer {
    fun render(pad: PadModel, outputSampleRate: Int = requireNotNull(pad.audio).sampleRate): ShortArray {
        val audio = requireNotNull(pad.audio) { "PADに音声がありません" }
        require(pad.isAssigned) { "PADの再生範囲がありません" }
        require(outputSampleRate in 8_000..192_000) { "Unsupported output sample rate" }

        val start = pad.startFrame.coerceIn(0, audio.samples.lastIndex)
        val end = pad.endFrame.coerceIn(start + 1, audio.samples.size)
        val sourceStep = SamplerDspPrimitives.sourceStep(
            pitchSemitones = pad.pitchSemitones,
            sourceSampleRate = audio.sampleRate,
            outputSampleRate = outputSampleRate,
        )
        val frameCount = (
            if (pad.reverse) {
                // Reverse starts at the last included frame, not the exclusive end.
                // Count only positions that remain inside the range, as the realtime cursor does.
                (floor((end - start - 1) / sourceStep) + 1.0).toInt()
            } else {
                ceil((end - start) / sourceStep).toInt()
            }
        ).coerceAtLeast(1)
        val result = ShortArray(frameCount)
        var filterState = 0f
        val gain = SamplerDspPrimitives.gain(pad.gain)
        val alpha = SamplerDspPrimitives.toneFilterAlpha(pad.tone, outputSampleRate)

        for (outputFrame in result.indices) {
            val offset = outputFrame * sourceStep
            val position = if (pad.reverse) end - 1.0 - offset else start + offset
            if (position < start || position >= end) break
            val lower = floor(position).toInt().coerceIn(start, end - 1)
            val upper = (lower + 1).coerceAtMost(end - 1)
            val fraction = (position - lower).toFloat()
            val first = audio.samples[lower] / 32_768f
            val second = audio.samples[upper] / 32_768f
            val raw = first + (second - first) * fraction
            val filtered = if (alpha >= 1f) raw else {
                filterState += alpha * (raw - filterState)
                filterState
            }
            val boundary = SamplerDspPrimitives.boundaryEnvelope(
                position = position,
                startFrame = start,
                endFrame = end,
                reverse = pad.reverse,
            )
            result[outputFrame] = (filtered * gain * boundary)
                .coerceIn(-1f, 1f)
                .times(Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
        return result
    }
}
