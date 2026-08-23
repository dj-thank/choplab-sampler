package com.choplab.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/** Allocation-free numeric policy shared by realtime, offline and desktop PAD rendering. */
object SamplerDspPrimitives {
    fun pitchSemitones(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-24f, 24f) else 0f

    fun tone(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 1f

    fun gain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1.5f) else 0f

    fun bpm(value: Float): Float =
        if (value.isFinite()) value.coerceIn(40f, 240f) else DEFAULT_BPM

    fun swing(value: Float): Float =
        if (value.isFinite()) value.coerceIn(50f, 75f) else STRAIGHT_SWING

    fun sourceStep(
        pitchSemitones: Float,
        sourceSampleRate: Int,
        outputSampleRate: Int,
    ): Double {
        val pitchRatio = 2.0.pow(this.pitchSemitones(pitchSemitones).toDouble() / 12.0)
        return pitchRatio * sourceSampleRate.coerceAtLeast(1) / outputSampleRate.coerceAtLeast(1).toDouble()
    }

    fun toneFilterAlpha(tone: Float, outputSampleRate: Int): Float {
        val safeTone = this.tone(tone)
        if (safeTone >= TONE_BYPASS_THRESHOLD) return 1f
        val safeSampleRate = outputSampleRate.coerceIn(8_000, 192_000)
        val cutoffHz = 80.0 * 225.0.pow(safeTone.toDouble())
        return (1.0 - exp(-2.0 * PI * cutoffHz / safeSampleRate))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun boundaryEnvelope(
        position: Double,
        startFrame: Int,
        endFrame: Int,
        reverse: Boolean,
    ): Float {
        if (!position.isFinite() || endFrame <= startFrame) return 0f
        val framesFromStart = if (reverse) {
            (endFrame - 1.0) - position
        } else {
            position - startFrame
        }
        val framesToEnd = if (reverse) {
            position - startFrame
        } else {
            (endFrame - 1.0) - position
        }
        return minOf(
            1.0,
            framesFromStart / BOUNDARY_FADE_SOURCE_FRAMES,
            framesToEnd / BOUNDARY_FADE_SOURCE_FRAMES,
        ).coerceAtLeast(0.0).toFloat()
    }

    fun softLimit(sample: Float): Float =
        if (sample.isFinite()) sample / (1f + abs(sample)) else 0f

    fun stepLengthFrames(
        sampleRate: Int,
        bpm: Float,
        swing: Float,
        step: Int,
    ): Double {
        val straightSixteenth = sampleRate.coerceAtLeast(1) * 60.0 / this.bpm(bpm) / 4.0
        val longRatio = this.swing(swing).toDouble() / 50.0
        return if (step % 2 == 0) {
            straightSixteenth * longRatio
        } else {
            straightSixteenth * (2.0 - longRatio)
        }
    }

    const val DEFAULT_BPM = 92f
    const val STRAIGHT_SWING = 50f
    const val TONE_BYPASS_THRESHOLD = 0.995f
    const val BOUNDARY_FADE_SOURCE_FRAMES = 48.0
}
