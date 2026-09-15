package com.choplab.sampler.separation

import com.choplab.sampler.model.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fixed contract of the HT-Demucs FT drums ONNX export used on Windows and Android. */
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

    /** Commit-pinned source and digest; they must match `scripts/prepare_separator_model.py`. */
    const val MODEL_COMMIT = "55f929d333054c69ae0e829b15e8f8826a39d6eb"
    const val MODEL_URL = "https://huggingface.co/StemSplitio/htdemucs-ft-drums-onnx/resolve/$MODEL_COMMIT/$MODEL_FILE"
    const val MODEL_SHA256 = "047764dff888cfb87da917013377d4ec7a134f7419cbe486d9c339aa17975ddd"
    const val MODEL_BYTES = 165_612_636L

    /** Upper bound for one offline separation job. */
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

    /** Output length of [resample] for [inputFrames] frames. */
    fun resampledLength(inputFrames: Int, inRate: Int, outRate: Int): Int {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        if (inputFrames <= 0) return 0
        if (inRate == outRate) return inputFrames
        val ratio = outRate.toDouble() / inRate.toDouble()
        return (inputFrames.toDouble() * ratio).roundToInt().coerceAtLeast(1)
    }

    /**
     * Windowed-sinc resampler (Hann window, [SINC_HALF_TAPS] tap pairs, edge clamp).
     * Transparent when rates match; deterministic for any ratio.
     */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        if (input.isEmpty()) return FloatArray(0)
        if (inRate == outRate) return input.copyOf()
        val outSize = resampledLength(input.size, inRate, outRate)
        val output = FloatArray(outSize)
        for (n in 0 until outSize) {
            output[n] = resampledSampleAt(n, input.size, inRate, outRate) { input[it] }
        }
        return output
    }

    /**
     * One output sample of [resample], reading input frames on demand. Streaming readers use
     * it to produce exactly the same values without materializing the whole resampled song.
     */
    inline fun resampledSampleAt(n: Int, inputSize: Int, inRate: Int, outRate: Int, input: (Int) -> Float): Float {
        val ratio = outRate.toDouble() / inRate.toDouble()
        val center = n / ratio
        // Anti-alias: widen the kernel when downsampling.
        val cutoff = if (ratio < 1.0) ratio else 1.0
        val taps = SINC_HALF_TAPS
        val first = (center - taps / cutoff).toInt()
        val last = (center + taps / cutoff).toInt()
        var acc = 0.0
        var weightSum = 0.0
        for (k in first..last) {
            val clamped = k.coerceIn(0, inputSize - 1)
            val x = (center - k) * cutoff
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val windowArg = (center - k) / (taps / cutoff)
            val window = if (abs(windowArg) >= 1.0) 0.0 else 0.5 * (1.0 + cos(PI * windowArg))
            val w = sinc * window
            acc += input(clamped) * w
            weightSum += w
        }
        return if (weightSum == 0.0) 0f else (acc / weightSum).toFloat()
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

    /** One clipped PCM-16 sample, identical to [floatStereoToPcm16]. */
    fun toPcm16(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    /** Clips float stereo (channel-major) to PCM-16 stereo [PcmAudio]. */
    fun floatStereoToPcm16(stereo: FloatArray, frames: Int, name: String): PcmAudio {
        require(stereo.size == 2 * frames) { "Stereo buffer size mismatch" }
        val samples = ShortArray(2 * frames)
        for (i in 0 until frames) {
            samples[2 * i] = toPcm16(stereo[i])
            samples[2 * i + 1] = toPcm16(stereo[frames + i])
        }
        return PcmAudio(name = name, samples = samples, sampleRate = SeparatorSpec.SAMPLE_RATE, channelCount = 2)
    }
}

/**
 * Channel-major float stereo at [SeparatorSpec.SAMPLE_RATE], read from [audio] on demand.
 * Values equal [SeparatorDsp.pcmToFloatStereo44100] without a whole-song float copy.
 */
class SeparatorSourceReader(private val audio: PcmAudio) {
    init {
        require(audio.frameCount > 0) { "空の音源は分離できません" }
    }

    val frames: Int = SeparatorDsp.resampledLength(audio.frameCount, audio.sampleRate, SeparatorSpec.SAMPLE_RATE)

    /** Fills `[L x segment][R x segment]` in [destination] with output frames [start, start + length). */
    fun read(start: Int, length: Int, destination: FloatArray, segment: Int) {
        require(start >= 0 && length >= 0 && length <= segment && start + length <= frames) { "Invalid source window" }
        require(destination.size >= 2 * segment) { "Chunk buffer size mismatch" }
        val channels = audio.channelCount
        val samples = audio.samples
        for (ch in 0..1) {
            val sourceChannel = if (ch == 0 || channels == 1) 0 else 1
            val offset = ch * segment
            if (audio.sampleRate == SeparatorSpec.SAMPLE_RATE) {
                for (i in 0 until length) {
                    destination[offset + i] = samples[(start + i) * channels + sourceChannel] / 32768f
                }
            } else {
                val inputFrames = audio.frameCount
                for (i in 0 until length) {
                    destination[offset + i] = SeparatorDsp.resampledSampleAt(
                        start + i, inputFrames, audio.sampleRate, SeparatorSpec.SAMPLE_RATE,
                    ) { frame -> samples[frame * channels + sourceChannel] / 32768f }
                }
            }
        }
    }
}
