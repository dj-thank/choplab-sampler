package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
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
        val reverseCursor = if (pad.reverse) {
            VoicePlaybackCursor(start, end, reverse = true, playMode = PadPlayMode.ONE_SHOT)
        } else {
            null
        }
        val frameCount = if (reverseCursor != null) {
            // Count and reset before allocation so duration and PCM use realtime's advance order.
            var count = 0
            while (!reverseCursor.finished) {
                count++
                reverseCursor.advance(sourceStep)
            }
            reverseCursor.reset(start, end, reverse = true, playMode = PadPlayMode.ONE_SHOT)
            count
        } else {
            ceil((end - start) / sourceStep).toInt().coerceAtLeast(1)
        }
        val result = ShortArray(frameCount)
        var filterState = 0f
        val gain = SamplerDspPrimitives.gain(pad.gain)
        val alpha = SamplerDspPrimitives.toneFilterAlpha(pad.tone, outputSampleRate)

        for (outputFrame in result.indices) {
            val position = reverseCursor?.position ?: (start + outputFrame * sourceStep)
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
            reverseCursor?.advance(sourceStep)
        }
        return result
    }
}
