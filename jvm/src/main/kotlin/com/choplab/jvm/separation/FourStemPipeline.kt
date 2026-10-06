package com.choplab.jvm.separation

import com.choplab.core.separation.SeparationProblem
import kotlin.math.min

/** Worker-only, bounded overlap/add. No array grows with source duration. All ranges are [start, end). */
internal class FourStemPipeline(private val segment: Int = FourStemSpec.FRAMES,
                                private val overlap: Int = segment / 4) {
    init { require(segment > 1 && overlap in 1..segment / 2) }
    private val stride = segment - overlap

    /** [read] fills two channel-major planes with stride [segment]; [emit] borrows an interleaved stereo block. */
    suspend fun render(total: Int, read: suspend (Int, Int, FloatArray) -> Unit, inference: FourStemInference,
                       emit: (Int, FloatArray, Int) -> Unit, check: () -> Unit, progress: (Int) -> Unit) {
        require(total > 0)
        val input = FloatArray(segment * 2)
        val accumulated = FloatArray(segment * 8)
        val weights = FloatArray(segment)
        val window = FloatArray(segment) { 1f }
        val output = FloatArray(segment * 2)
        for (frame in 0 until overlap) {
            val fade = frame.toFloat() / (overlap - 1).coerceAtLeast(1)
            window[frame] = fade; window[segment - 1 - frame] = fade
        }
        var start = 0
        while (start < total) {
            check()
            val count = min(segment, total - start)
            input.fill(0f)
            read(start, count, input)
            check()
            inference.infer(input, check) { stems ->
                if (stems.remaining() != segment * 8) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                val first = stems.position()
                for (frame in 0 until count) {
                    if (frame and 4095 == 0) check()
                    // There is no preceding chunk at frame zero. Preserve that edge instead of forcing it to silence.
                    val weight = if (start == 0 && frame < overlap) 1f else window[frame]
                    weights[frame] += weight
                    for (plane in 0 until 8) {
                        val value = stems[first + plane * segment + frame]
                        if (!value.isFinite()) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                        accumulated[plane * segment + frame] += value * weight
                    }
                }
            }
            check()
            val finished = min(stride, total - start)
            for (stem in 0 until 4) {
                for (frame in 0 until finished) {
                    if (frame and 4095 == 0) check()
                    val weight = weights[frame]
                    if (weight <= 0f) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                    output[frame * 2] = accumulated[stem * 2 * segment + frame] / weight
                    output[frame * 2 + 1] = accumulated[(stem * 2 + 1) * segment + frame] / weight
                }
                emit(stem, output, finished)
            }
            start += finished
            progress(start)
            if (start < total) {
                for (plane in 0 until 8) {
                    val offset = plane * segment
                    accumulated.copyInto(accumulated, offset, offset + stride, offset + segment)
                    accumulated.fill(0f, offset + overlap, offset + segment)
                }
                weights.copyInto(weights, 0, stride, segment)
                weights.fill(0f, overlap, segment)
            }
        }
    }
}
