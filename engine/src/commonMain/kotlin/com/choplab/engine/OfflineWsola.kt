package com.choplab.engine

import kotlin.math.*

/**
 * Worker-only waveform-similarity overlap/add, linked stereo at 48 kHz. Speed is input/output length.
 * Windows stay at their original sample rate: duration changes without varispeed pitch shifting.
 * The first and final windows preserve both ends. Speech quality still needs audible A/B acceptance.
 */
object OfflineWsola {
    const val VERSION = "wsola-1"
    const val MAX_FRAMES = 48_000 * 30
    const val MIN_SPEED = .6
    const val MAX_SPEED = 1.6

    fun stretch(input: FloatArray, outputFrames: Int, checkCancelled: () -> Unit = {}): FloatArray {
        require(input.isNotEmpty() && input.size % 2 == 0)
        val frames = input.size / 2
        require(frames in 1..MAX_FRAMES && outputFrames in 1..MAX_FRAMES)
        for (i in input.indices) { if (i % 8192 == 0) checkCancelled(); require(input[i].isFinite()) }
        val speed = frames.toDouble() / outputFrames
        require(speed in MIN_SPEED..MAX_SPEED) { "Speech cannot fit the selected duration" }
        if (frames == outputFrames) return input.copyOf()
        require(minOf(frames, outputFrames) >= 128) { "Too little audio for time stretching" }
        val window = minOf(2048, minOf(frames, outputFrames) / 2).let { it - it % 2 }
        val hop = window / 2
        val search = minOf(256, hop / 2)
        val output = FloatArray(outputFrames * 2)
        input.copyInto(output, 0, 0, window * 2)
        var filled = window
        var previousInput = 0
        var outputStart = minOf(hop, outputFrames - window)
        while (outputStart < outputFrames) {
            checkCancelled()
            val last = outputStart == outputFrames - window
            val expected = (outputStart.toDouble() * (frames - window) / (outputFrames - window)).roundToInt()
            val overlap = filled - outputStart
            val chosen = if (last) frames - window else bestMatch(input, output, outputStart, overlap,
                maxOf(previousInput + 1, expected - search).coerceAtMost(frames - window),
                minOf(frames - window, expected + search), expected)
            for (i in 0 until window) {
                val weight = if (i < overlap) (i + 1).toFloat() / (overlap + 1) else 1f
                val destination = (outputStart + i) * 2
                val source = (chosen + i) * 2
                output[destination] = output[destination] * (1f - weight) + input[source] * weight
                output[destination + 1] = output[destination + 1] * (1f - weight) + input[source + 1] * weight
            }
            previousInput = chosen
            filled = outputStart + window
            if (last) break
            outputStart = minOf(outputStart + hop, outputFrames - window)
        }
        checkCancelled()
        return output
    }

    private fun bestMatch(input: FloatArray, output: FloatArray, out: Int, overlap: Int, low: Int, high: Int, expected: Int): Int {
        var best = expected.coerceIn(low, high)
        var bestScore = score(input, output, best, out, overlap) - abs(best - expected) * 1e-9
        fun tryAt(candidate: Int) {
            val score = score(input, output, candidate, out, overlap) - abs(candidate - expected) * 1e-9
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        var candidate = low
        while (candidate <= high) { tryAt(candidate); candidate += 4 }
        val center = best
        for (fine in maxOf(low, center - 3)..minOf(high, center + 3)) tryAt(fine)
        return best
    }

    private fun score(input: FloatArray, output: FloatArray, source: Int, destination: Int, count: Int): Double {
        var dot = 0.0; var sourceEnergy = 0.0; var destinationEnergy = 0.0
        var i = 0
        while (i < count) {
            repeat(2) { channel ->
                val a = input[(source + i) * 2 + channel].toDouble()
                val b = output[(destination + i) * 2 + channel].toDouble()
                dot += a * b; sourceEnergy += a * a; destinationEnergy += b * b
            }
            i += 8
        }
        return if (sourceEnergy * destinationEnergy < 1e-20) 0.0 else dot / sqrt(sourceEnergy * destinationEnergy)
    }
}
