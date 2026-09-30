package com.choplab.core.vocal

import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import com.choplab.engine.Tempo

/** Song-clock punch interval and the microphone's extra crossfade handles; all ranges are end-exclusive 48 kHz. */
data class VocalPunchPlan private constructor(
    val punchIn: Long,
    val punchOut: Long,
    val playbackStart: Long,
    val captureStart: Long,
    val captureEnd: Long,
    val armingSeconds: Int,
) {
    init {
        require(playbackStart >= 0 && playbackStart <= captureStart && captureStart <= punchIn)
        require(punchIn < punchOut && punchOut <= captureEnd && captureEnd <= ProjectLimits.MAX_TIMELINE_FRAMES)
        require(captureEnd - captureStart in 1..300L * 48_000 && armingSeconds in 20..32)
    }
    val captureFrames: Long get() = captureEnd - captureStart
    val framesBeforeCapture: Long get() = captureStart - playbackStart

    /** Replace only the chosen interval of an existing comp, retaining the old candidates and exact song length. */
    fun splice(project: Project, base: VocalCompDraft, newTakeId: String): VocalCompDraft {
        require(punchIn >= base.startFrame && punchOut <= base.endFrame)
        val selected = mutableListOf<VocalCompSegment>()
        for (line in base.segments) {
            if (line.startFrame < punchIn) selected += line.copy(endFrame = minOf(line.endFrame, punchIn))
        }
        // A punch can cover a lyric gap; its actual voice is retained instead of being silently removed by old text.
        val ids = base.segments.map { it.id }.toMutableSet()
        fun fresh(prefix: String): String {
            var id = prefix
            var count = 0
            while (!ids.add(id)) id = "$prefix-${++count}"
            return id
        }
        val id = fresh("punch")
        selected += VocalCompSegment(id, newTakeId, punchIn, punchOut)
        for (line in base.segments) {
            if (line.endFrame > punchOut) {
                val suffix = if (selected.any { it.id == line.id }) fresh("after-punch") else line.id
                selected += line.copy(id = suffix, startFrame = maxOf(line.startFrame, punchOut))
            }
        }
        return base.copy(segments = selected.frozen()).also { VocalCompEdits.validate(project, it) }
    }

    companion object {
        /** Pre-roll and count-in may each use 0-2 bars. The separate armed-input deadline includes both. */
        fun create(punchIn: Long, punchOut: Long, songEnd: Long, preRollBars: Int, countInBars: Int, tempo: Tempo): VocalPunchPlan {
            require(punchIn >= 0 && punchOut > punchIn && punchOut <= songEnd && songEnd <= ProjectLimits.MAX_TIMELINE_FRAMES)
            require(preRollBars in 0..2 && countInBars in 0..2)
            val preRoll = ProgramCompiler.tickToFrame(preRollBars * 4L * ProjectLimits.PPQ, tempo.milliBpm)
            val half = VocalCompMix.CROSSFADE_FRAMES / 2L
            val captureStart = (punchIn - half).coerceAtLeast(0)
            val captureEnd = (punchOut + half).coerceAtMost(songEnd)
            val playbackStart = minOf((punchIn - preRoll).coerceAtLeast(0), captureStart)
            val countIn = (countInBars * 4L * 60 * 48_000 * 1000 + tempo.milliBpm - 1) / tempo.milliBpm
            val arming = ((countIn + captureStart - playbackStart + 47_999) / 48_000 + 2).coerceAtLeast(20).toInt()
            require(arming <= 32 && captureEnd - captureStart <= 300L * 48_000)
            return VocalPunchPlan(punchIn, punchOut, playbackStart, captureStart, captureEnd, arming)
        }
    }
}
