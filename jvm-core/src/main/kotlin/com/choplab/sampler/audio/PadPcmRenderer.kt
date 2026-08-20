package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** Host-side rendering of one PAD voice using the same core controls as the Android engine. */
object PadPcmRenderer {
    fun render(pad: PadModel, outputSampleRate: Int = requireNotNull(pad.audio).sampleRate): ShortArray {
        val audio = requireNotNull(pad.audio) { "PADに音声がありません" }
        require(pad.isAssigned) { "PADの再生範囲がありません" }
        require(outputSampleRate in 8_000..192_000) { "Unsupported output sample rate" }

        val start = pad.startFrame.coerceIn(0, audio.samples.lastIndex)
        val end = pad.endFrame.coerceIn(start + 1, audio.samples.size)
        val sourceStep = 2.0.pow(pad.pitchSemitones.coerceIn(-24f, 24f) / 12.0) *
            audio.sampleRate / outputSampleRate.toDouble()
        val frameCount = ceil((end - start) / sourceStep).toInt().coerceAtLeast(1)
        val result = ShortArray(frameCount)
        var filterState = 0f
        val tone = pad.tone.coerceIn(0f, 1f)
        val gain = pad.gain.coerceIn(0f, 1.5f)
        val alpha = if (tone >= 0.995f) 1f else {
            val cutoffHz = 80.0 * 225.0.pow(tone.toDouble())
            (1.0 - exp(-2.0 * PI * cutoffHz / outputSampleRate)).toFloat()
        }

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
            val filtered = if (tone >= 0.995f) raw else {
                filterState += alpha * (raw - filterState)
                filterState
            }
            val fromStart = if (pad.reverse) end - 1.0 - position else position - start
            val toEnd = if (pad.reverse) position - start else end - 1.0 - position
            val boundary = minOf(1.0, fromStart / CLICK_FADE_SOURCE_FRAMES, toEnd / CLICK_FADE_SOURCE_FRAMES)
                .coerceAtLeast(0.0)
                .toFloat()
            result[outputFrame] = (filtered * gain * boundary)
                .coerceIn(-1f, 1f)
                .times(Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
        return result
    }

    private const val CLICK_FADE_SOURCE_FRAMES = 48.0
}
