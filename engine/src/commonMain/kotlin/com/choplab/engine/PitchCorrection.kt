package com.choplab.engine

import kotlin.math.*

enum class PitchScale { CHROMATIC, MAJOR, NATURAL_MINOR }

/** Offline monophonic voice correction. Retune is a time constant; vibrato preserves pitch variation. */
data class PitchCorrectionSettings(
    val key: Int = 0,
    val scale: PitchScale = PitchScale.CHROMATIC,
    val amount: Float = 1f,
    val retuneMs: Float = 50f,
    val vibrato: Float = 1f,
) {
    init {
        require(key in 0..11)
        require(amount.isFinite() && amount in 0f..1f)
        require(retuneMs.isFinite() && retuneMs in 0f..500f)
        require(vibrato.isFinite() && vibrato in 0f..1f)
    }
    fun permits(midiNote: Int): Boolean {
        val pitchClass = ((midiNote - key) % 12 + 12) % 12
        return when (scale) {
            PitchScale.CHROMATIC -> true
            PitchScale.MAJOR -> (0xAB5 and (1 shl pitchClass)) != 0
            PitchScale.NATURAL_MINOR -> (0x5AD and (1 shl pitchClass)) != 0
        }
    }
    internal fun nearest(midi: Double, previous: Int?): Int {
        var best = floor(midi).toInt() - 3
        var distance = Double.POSITIVE_INFINITY
        for (note in floor(midi).toInt() - 3..ceil(midi).toInt() + 3) if (permits(note)) {
            val next = abs(note - midi)
            if (next < distance) { best = note; distance = next }
        }
        return previous?.takeIf { permits(it) && abs(it - midi) <= distance + .12 } ?: best
    }
}

/** Reader fills exactly [frames] stereo frames at 48 kHz into [destination], starting at element zero. */
fun interface PitchPcmReader { fun read(firstFrame: Int, frames: Int, destination: FloatArray): Int }
enum class PitchCorrectionPhase { ANALYZE, RENDER }
enum class PitchBypass { NONE, EDGE, UNVOICED, LOW_CONFIDENCE, OUT_OF_RANGE, OCTAVE_AMBIGUOUS, STEREO_AMBIGUOUS, UNSTABLE }
data class PitchCorrectionReport(
    val frames: Int,
    val correctedFrames: Int,
    val reliableHops: Int,
    val bypassHops: List<Int>,
    val workspaceBytes: Long,
    /** Lookahead used only by the offline analysis; output alignment has zero added frames. */
    val analysisWindowFrames: Int = 4096,
    val outputDelayFrames: Int = 0,
)
