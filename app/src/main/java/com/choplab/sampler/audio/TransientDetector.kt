package com.choplab.sampler.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight onset detector intended for drum and phrase chopping.
 * It ranks positive energy changes and enforces a minimum distance between slices.
 */
object TransientDetector {
    fun detect(
        samples: ShortArray,
        startFrame: Int,
        endFrame: Int,
        sampleRate: Int,
        maxSlices: Int = 16,
        sensitivity: Float = 1.15f,
    ): List<Int> {
        if (samples.isEmpty() || sampleRate <= 0 || maxSlices <= 1) return emptyList()

        val start = startFrame.coerceIn(0, samples.lastIndex)
        val end = endFrame.coerceIn(start + 1, samples.size)
        val windowSize = (sampleRate / 200).coerceIn(128, 512) // approximately 5 ms
        val windowCount = (end - start) / windowSize
        if (windowCount < 4) return emptyList()

        val novelty = FloatArray(windowCount)
        var smoothedEnergy = 0f
        var previousSmoothed = 0f

        for (window in 0 until windowCount) {
            val from = start + window * windowSize
            val to = min(end, from + windowSize)
            var sum = 0.0
            for (index in from until to) {
                val value = samples[index] / 32_768f
                sum += value * value
            }
            val rms = sqrt(sum / max(1, to - from)).toFloat()
            smoothedEnergy = smoothedEnergy * 0.72f + rms * 0.28f
            novelty[window] = max(0f, smoothedEnergy - previousSmoothed)
            previousSmoothed = smoothedEnergy
        }

        val mean = novelty.average().toFloat()
        var variance = 0f
        novelty.forEach { value ->
            val delta = value - mean
            variance += delta * delta
        }
        variance /= max(1, novelty.size)
        val threshold = mean + sqrt(variance) * sensitivity

        data class Candidate(val frame: Int, val score: Float)

        val candidates = buildList {
            for (index in 1 until novelty.lastIndex) {
                val score = novelty[index]
                if (score >= threshold && score >= novelty[index - 1] && score > novelty[index + 1]) {
                    add(Candidate(start + index * windowSize, score))
                }
            }
        }.sortedByDescending { it.score }

        val minimumDistance = (sampleRate * 0.065f).toInt().coerceAtLeast(windowSize)
        val accepted = mutableListOf<Candidate>()
        for (candidate in candidates) {
            if (accepted.size >= maxSlices - 1) break
            if (accepted.none { other -> kotlin.math.abs(other.frame - candidate.frame) < minimumDistance }) {
                accepted += candidate
            }
        }

        return accepted.map { it.frame }.sorted()
    }
}
