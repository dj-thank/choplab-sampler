package com.choplab.desktop.separation

import com.choplab.sampler.model.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fixed contract of the bundled HT-Demucs FT drums ONNX export. */
object SeparatorSpec {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val SEGMENT_SAMPLES = 343980
    const val OVERLAP_SAMPLES = SEGMENT_SAMPLES / 4
    const val STRIDE_SAMPLES = SEGMENT_SAMPLES - OVERLAP_SAMPLES
    const val MODEL_FILE = "htdemucs_ft_drums_fp16weights.onnx"
    const val INPUT_NAME = "mix"
    const val OUTPUT_NAME = "stems"
    const val DRUM_STEM_INDEX = 0
    val STEM_NAMES = listOf("drums", "bass", "other", "vocals")

    /** Upper bound for one offline separation job (transient float buffers stay modest). */
    const val MAX_JOB_SECONDS = 360
}

/**
 * Pure host-side DSP for drum separation: resampling, channel mapping, chunk
 * planning, overlap-add windows, and PCM conversion. No ONNX dependency, so
 * every function below is covered by fast unit tests.
 */
object SeparatorDsp {
    /** Hann-windowed sinc kernel half-width (taps = 2 * [SINC_HALF_TAPS]). */
    const val SINC_HALF_TAPS = 16

    /**
     * Converts any [PcmAudio] to channel-major float stereo at [SeparatorSpec.SAMPLE_RATE].
     * Mono is duplicated, stereo is preserved. Values are in [-1, 1].
     */
    fun pcmToFloatStereo44100(audio: PcmAudio): Pair<FloatArray, Int> {
        require(audio.frameCount > 0) { "空の音源は分離できません" }
        val channels = Array(audio.channelCount) { ch ->
            FloatArray(audio.frameCount) { frame ->
                audio.sampleAt(frame, ch) / 32768f
            }
        }
        val resampled = if (audio.sampleRate == SeparatorSpec.SAMPLE_RATE) {
            channels
        } else {
            Array(audio.channelCount) { ch ->
                resample(channels[ch], audio.sampleRate, SeparatorSpec.SAMPLE_RATE)
            }
        }
        val frames = resampled[0].size
        val stereo = FloatArray(2 * frames)
        for (i in 0 until frames) {
            stereo[i] = resampled[0][i]
            stereo[frames + i] = resampled[if (resampled.size > 1) 1 else 0][i]
        }
        return stereo to frames
    }

    /**
     * Windowed-sinc resampler (Hann window, [SINC_HALF_TAPS] tap pairs, edge clamp).
     * Transparent when rates match; deterministic for any ratio.
     */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        if (input.isEmpty()) return FloatArray(0)
        if (inRate == outRate) return input.copyOf()
        val ratio = outRate.toDouble() / inRate.toDouble()
        val outSize = (input.size.toDouble() * ratio).roundToInt().coerceAtLeast(1)
        // Anti-alias: widen the kernel when downsampling.
        val cutoff = if (ratio < 1.0) ratio else 1.0
        val taps = SINC_HALF_TAPS
        val output = FloatArray(outSize)
        for (n in 0 until outSize) {
            val center = n / ratio
            val first = (center - taps / cutoff).toInt()
            val last = (center + taps / cutoff).toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (k in first..last) {
                val clamped = k.coerceIn(0, input.size - 1)
                val x = (center - k) * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val windowArg = (center - k) / (taps / cutoff)
                val window = if (abs(windowArg) >= 1.0) 0.0 else 0.5 * (1.0 + cos(PI * windowArg))
                val w = sinc * window
                acc += input[clamped] * w
                weightSum += w
            }
            output[n] = if (weightSum == 0.0) 0f else (acc / weightSum).toFloat()
        }
        return output
    }

    /**
     * Linear crossfade window matching the reference ONNX pipeline: ones in the
     * middle with [overlap]-sample linear ramps at both ends.
     */
    fun transitionWindow(segment: Int, overlap: Int): FloatArray {
        require(segment > 0 && overlap in 1 until segment) { "Invalid window geometry" }
        val window = FloatArray(segment) { 1f }
        for (i in 0 until overlap) {
            val fade = i.toFloat() / (overlap - 1).coerceAtLeast(1)
            window[i] = fade
            window[segment - 1 - i] = fade
        }
        return window
    }

    /** Chunk start offsets covering [totalLen] with fixed [segment]/[stride]. */
    fun chunkStarts(totalLen: Int, segment: Int, stride: Int): List<Int> {
        require(totalLen > 0 && segment > 0 && stride > 0) { "Invalid chunk geometry" }
        val starts = mutableListOf<Int>()
        var start = 0
        while (start < totalLen) {
            starts += start
            start += stride
        }
        return starts
    }

    /** Clips float stereo (channel-major) to PCM-16 stereo [PcmAudio]. */
    fun floatStereoToPcm16(stereo: FloatArray, frames: Int, name: String): PcmAudio {
        require(stereo.size == 2 * frames) { "Stereo buffer size mismatch" }
        val samples = ShortArray(2 * frames)
        for (i in 0 until frames) {
            samples[2 * i] = (stereo[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                .coerceIn(-32768, 32767).toShort()
            samples[2 * i + 1] = (stereo[frames + i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                .coerceIn(-32768, 32767).toShort()
        }
        return PcmAudio(name = name, samples = samples, sampleRate = SeparatorSpec.SAMPLE_RATE, channelCount = 2)
    }
}
