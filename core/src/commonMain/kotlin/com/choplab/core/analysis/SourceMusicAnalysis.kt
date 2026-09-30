package com.choplab.core.analysis

import com.choplab.core.model.*
import kotlin.math.*

/** Scores rank candidates within this measured excerpt; they are not calibrated probabilities. */
data class TempoCandidate(val milliBpm: Int, val strength: Double) {
    init { require(milliBpm in 40_000..240_000 && strength.isFinite() && strength in 0.0..1.0) }
}
enum class KeyMode { MAJOR, MINOR }
data class KeyCandidate(val tonic: Int, val mode: KeyMode, val strength: Double) {
    init { require(tonic in 0..11 && strength.isFinite() && strength in 0.0..1.0) }
}
data class SourceMusicResult(val frames: Int, val tempos: FrozenList<TempoCandidate>, val keys: FrozenList<KeyCandidate>) {
    init { require(frames in 0..SourceMusicAnalysis.MAX_FRAMES && tempos.size <= 3 && keys.size <= 3) }
}

/**
 * Worker-only, bounded analysis of unmodified 48 kHz stereo. No source PCM is retained beyond the FFT ring.
 * Tempo uses autocorrelation of positive RMS changes; key uses tonal spectral peaks and profile correlation.
 * Stereo energies are combined after measurement, so opposite-phase channels cannot cancel each other.
 */
class SourceMusicAnalysis {
    private val left = DoubleArray(FFT_SIZE)
    private val right = DoubleArray(FFT_SIZE)
    private val real = DoubleArray(FFT_SIZE)
    private val imaginary = DoubleArray(FFT_SIZE)
    private val power = DoubleArray(FFT_SIZE / 2)
    private val hann = DoubleArray(FFT_SIZE) { .5 - .5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
    private val chroma = DoubleArray(12)
    private val frameChroma = DoubleArray(12)
    private val novelty = DoubleArray(MAX_FRAMES / ENERGY_HOP)
    private val energyWindow = DoubleArray(ENERGY_HOP * 5)
    private var count = 0
    private var energyCount = 0
    private var energy = 0.0
    private var rmsSum = 0.0
    private var previousRms = 0.0
    private var nextSpectrum = FFT_SIZE
    private var tonalWindows = 0
    private var finished = false

    fun accept(stereo: FloatArray, checkCancelled: () -> Unit = {}) {
        check(!finished)
        require(stereo.size % 2 == 0 && stereo.size / 2 <= MAX_FRAMES - count)
        for (frame in 0 until stereo.size / 2) {
            if (frame % 1024 == 0) checkCancelled()
            val l = stereo[frame * 2].toDouble(); val r = stereo[frame * 2 + 1].toDouble()
            require(l.isFinite() && r.isFinite())
            left[count % FFT_SIZE] = l; right[count % FFT_SIZE] = r
            val square = l * l + r * r
            val slot = count % energyWindow.size
            energy += square - energyWindow[slot]
            energyWindow[slot] = square
            count++
            if (count % ENERGY_HOP == 0) {
                val rms = sqrt(max(0.0, energy) / (min(count, energyWindow.size) * 2))
                novelty[energyCount] = if (count <= energyWindow.size) 0.0 else max(0.0, rms - previousRms)
                energyCount++; rmsSum += rms; previousRms = rms
            }
            if (count == nextSpectrum) {
                spectrum(); nextSpectrum += SPECTRUM_HOP
            }
        }
    }

    fun finish(checkCancelled: () -> Unit = {}): SourceMusicResult {
        check(!finished); finished = true; checkCancelled()
        if (count < MIN_FRAMES) return SourceMusicResult(count, frozenListOf(), frozenListOf())
        return SourceMusicResult(count, tempos(checkCancelled).frozen(), keys().frozen())
    }

    private fun tempos(checkCancelled: () -> Unit): List<TempoCandidate> {
        val maximum = novelty.take(energyCount).maxOrNull() ?: return emptyList()
        if (maximum < 1e-5 || maximum < rmsSum / energyCount * .15) return emptyList()
        val attacks = (1 until energyCount - 1).count {
            novelty[it] > maximum * .15 && novelty[it] > novelty[it - 1] && novelty[it] >= novelty[it + 1]
        }
        if (attacks < 4) return emptyList()
        val mean = (0 until energyCount).sumOf { novelty[it] } / energyCount
        for (i in 0 until energyCount) novelty[i] -= mean
        val scores = DoubleArray(2001)
        for (index in scores.indices) {
            if (index % 32 == 0) checkCancelled()
            val lag = 6_000_000.0 / (40_000 + index * 100)
            var cross = 0.0; var a2 = 0.0; var b2 = 0.0
            for (i in ceil(lag).toInt() until energyCount) {
                val at = i - lag; val first = at.toInt(); val fraction = at - first
                val a = novelty[i]; val b = novelty[first] * (1 - fraction) + novelty[first + 1] * fraction
                cross += a * b; a2 += a * a; b2 += b * b
            }
            scores[index] = if (a2 * b2 > 1e-20) (cross / sqrt(a2 * b2)).coerceIn(-1.0, 1.0) else 0.0
        }
        val peaks = scores.indices.filter { i -> scores[i] >= .45 &&
            (i == 0 || scores[i] >= scores[i - 1]) && (i == scores.lastIndex || scores[i] > scores[i + 1])
        }.map { TempoCandidate(40_000 + it * 100, scores[it]) }
            .sortedByDescending { it.strength - .03 * abs(log2(it.milliBpm / 120_000.0)) }
        val best = peaks.firstOrNull() ?: return emptyList()
        val result = mutableListOf(best)
        // Preserve the musically ambiguous half/double alternatives before unrelated weak peaks.
        for (factor in listOf(.5, 2.0)) {
            val target = best.milliBpm * factor
            peaks.firstOrNull { abs(it.milliBpm - target) < target * .025 && it.strength >= best.strength * .8 }
                ?.let { if (it !in result) result += it }
        }
        for (candidate in peaks) if (result.size < 3 && result.all { abs(it.milliBpm - candidate.milliBpm) > candidate.milliBpm * .04 }) result += candidate
        return result.take(3)
    }

    private fun spectrum() {
        power.fill(0.0)
        for (channel in listOf(left, right)) {
            for (i in 0 until FFT_SIZE) real[i] = channel[(count + i) % FFT_SIZE] * hann[i]
            imaginary.fill(0.0)
            fft()
            for (i in power.indices) power[i] += real[i] * real[i] + imaginary[i] * imaginary[i]
        }
        val first = ceil(55.0 * FFT_SIZE / RATE).toInt()
        val last = floor(4_000.0 * FFT_SIZE / RATE).toInt()
        var sum = 0.0; var logs = 0.0; var maximum = 0.0
        for (i in first..last) { sum += power[i]; logs += ln(max(power[i], 1e-20)); maximum = max(maximum, power[i]) }
        if (maximum < 1e-7 || exp(logs / (last - first + 1)) / (sum / (last - first + 1)) > .2) return
        frameChroma.fill(0.0)
        for (i in first..last) {
            if (power[i] < maximum * .005 || power[i] <= power[i - 1] || power[i] <= power[i + 1]) continue
            val previous = ln(max(power[i - 1], 1e-20)); val center = ln(max(power[i], 1e-20)); val next = ln(max(power[i + 1], 1e-20))
            val curvature = previous - 2 * center + next
            // A broad transient peak is not a sufficiently resolved pitch for a key claim.
            if (curvature > -.7) continue
            val offset = (.5 * (previous - next) / curvature).coerceIn(-.5, .5)
            val midi = 69 + 12 * log2((i + offset) * RATE / FFT_SIZE / 440.0)
            val nearest = midi.roundToInt(); val distance = midi - nearest
            if (abs(distance) > .4) continue
            frameChroma[(nearest % 12 + 12) % 12] += sqrt(power[i]) * exp(-.5 * (distance / .3).pow(2))
        }
        val total = frameChroma.sum()
        if (total > 0) {
            tonalWindows++
            for (i in chroma.indices) chroma[i] += frameChroma[i] / total
        }
    }

    private fun keys(): List<KeyCandidate> {
        val total = chroma.sum()
        if (tonalWindows < 5 || total <= 0 || chroma.count { it > total * .035 } < 3 ||
            chroma.maxOrNull()!! > total * .75) return emptyList()
        val average = total / 12
        val variance = chroma.sumOf { (it - average).pow(2) }
        if (variance < 1e-9) return emptyList()
        val candidates = buildList {
            for ((mode, profile) in listOf(KeyMode.MAJOR to MAJOR_PROFILE, KeyMode.MINOR to MINOR_PROFILE)) {
                val mean = profile.average(); val norm = sqrt(profile.sumOf { (it - mean).pow(2) } * variance)
                for (tonic in 0..11) {
                    val correlation = (0..11).sumOf { (chroma[(it + tonic) % 12] - average) * (profile[it] - mean) } / norm
                    if (correlation >= .55) add(KeyCandidate(tonic, mode, correlation.coerceAtMost(1.0)))
                }
            }
        }.sortedByDescending { it.strength }
        val best = candidates.firstOrNull() ?: return emptyList()
        return candidates.filter { it.strength >= best.strength - .12 }.take(3)
    }

    private fun fft() {
        var reversed = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE / 2
            while (reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if (i < reversed) { val value = real[i]; real[i] = real[reversed]; real[reversed] = value }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val cosine = cos(-2 * PI / length); val sine = sin(-2 * PI / length)
            for (first in 0 until FFT_SIZE step length) {
                var wr = 1.0; var wi = 0.0
                for (part in 0 until length / 2) {
                    val a = first + part; val b = a + length / 2
                    val br = real[b] * wr - imaginary[b] * wi; val bi = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi
                    real[a] += br; imaginary[a] += bi
                    val next = wr * cosine - wi * sine
                    wi = wr * sine + wi * cosine; wr = next
                }
            }
            length *= 2
        }
    }

    companion object {
        const val RATE = 48_000
        const val MAX_SECONDS = 30
        const val MAX_FRAMES = RATE * MAX_SECONDS
        const val MIN_FRAMES = RATE * 4
        /** Includes all fixed arrays, candidate work, and the caller's <=4096-frame stereo read window. */
        const val WORK_BYTES = 1024L * 1024
        private const val ENERGY_HOP = 480
        private const val SPECTRUM_HOP = 4_800
        private const val FFT_SIZE = 8_192
        // Krumhansl-Kessler empirical pitch-class ratings, ordered relative to the tonic.
        // Profile method and provenance: https://essentia.upf.edu/reference/std_Key.html
        private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}
