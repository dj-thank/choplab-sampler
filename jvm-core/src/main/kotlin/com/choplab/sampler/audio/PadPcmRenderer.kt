package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import kotlin.math.ceil
import kotlin.math.floor

/** Host-side rendering of one PAD voice using the same core controls as the Android engine. */
object PadPcmRenderer {
    fun render(pad: PadModel, outputSampleRate: Int = requireNotNull(pad.audio).sampleRate): ShortArray =
        renderInterleaved(pad, outputSampleRate).samples

    fun renderInterleaved(
        pad: PadModel,
        outputSampleRate: Int = requireNotNull(pad.audio).sampleRate,
    ): RenderedPcm {
        val audio = requireNotNull(pad.audio) { "PADに音声がありません" }
        require(pad.isAssigned) { "PADの再生範囲がありません" }
        require(outputSampleRate in 8_000..192_000) { "Unsupported output sample rate" }

        val start = pad.startFrame.coerceIn(0, audio.frameCount - 1)
        val end = pad.endFrame.coerceIn(start + 1, audio.frameCount)
        val sourceStep = SamplerDspPrimitives.sourceStep(
            pitchSemitones = pad.pitchSemitones,
            sourceSampleRate = audio.sampleRate,
            outputSampleRate = outputSampleRate,
        )
        val frameCount = ceil((end - start) / sourceStep).toInt().coerceAtLeast(1)
        val result = ShortArray(Math.multiplyExact(frameCount, audio.channelCount))
        val filterState = FloatArray(audio.channelCount)
        val gain = SamplerDspPrimitives.gain(pad.gain)
        val alpha = SamplerDspPrimitives.toneFilterAlpha(pad.tone, outputSampleRate)

        for (outputFrame in 0 until frameCount) {
            val offset = outputFrame * sourceStep
            val position = if (pad.reverse) end - 1.0 - offset else start + offset
            if (position < start || position >= end) break
            val lower = floor(position).toInt().coerceIn(start, end - 1)
            val upper = (lower + 1).coerceAtMost(end - 1)
            val fraction = (position - lower).toFloat()
            val boundary = SamplerDspPrimitives.boundaryEnvelope(
                position = position,
                startFrame = start,
                endFrame = end,
                reverse = pad.reverse,
            )
            repeat(audio.channelCount) { channel ->
                val first = audio.sampleAt(lower, channel) / 32_768f
                val second = audio.sampleAt(upper, channel) / 32_768f
                val raw = first + (second - first) * fraction
                val filtered = if (alpha >= 1f) {
                    filterState[channel] = raw
                    raw
                } else {
                    filterState[channel] += alpha * (raw - filterState[channel])
                    filterState[channel]
                }
                result[outputFrame * audio.channelCount + channel] = (filtered * gain * boundary)
                    .coerceIn(-1f, 1f)
                    .times(Short.MAX_VALUE)
                    .toInt()
                    .toShort()
            }
        }
        return RenderedPcm(result, audio.channelCount)
    }
}
