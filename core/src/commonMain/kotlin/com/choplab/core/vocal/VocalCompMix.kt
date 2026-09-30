package com.choplab.core.vocal

import com.choplab.core.model.*

/** Offline spans only; no files, buffers or work enter EngineCore's render callback. */
data class VocalMixSpan(val start: Long, val end: Long, val from: Take?, val to: Take? = from, val ramp: Boolean = false) {
    init { require(end > start) }
    /** Constant-sum interpolation preserves correlated DC and stereo identity without an equal-power level bump. */
    fun mix(first: Float, second: Float, frame: Long): Float {
        require(frame in start until end)
        if (!ramp) return first
        val blend = if (end - start == 1L) .5f else ((frame - start).toDouble() / (end - start - 1)).toFloat()
        return first + (second - first) * blend
    }
}

class VocalCompMix private constructor(val draft: VocalCompDraft, val spans: FrozenList<VocalMixSpan>) {
    companion object {
        const val CROSSFADE_FRAMES = 480 // 10 ms at the mix clock; timeline frames are never removed.
        private data class Join(val start: Long, val end: Long)

        fun plan(project: Project, draft: VocalCompDraft): VocalCompMix {
            VocalCompEdits.validate(project, draft)
            val lines = draft.segments
            val takes = lines.map { line -> project.takes.first { it.id == line.takeId } }
            val joins = lines.zipWithNext().mapIndexed { index, (left, right) ->
                if (left.endFrame != right.startFrame || left.takeId == right.takeId) null else {
                    val boundary = left.endFrame
                    val before = minOf(CROSSFADE_FRAMES / 2L, (left.endFrame - left.startFrame) / 2,
                        boundary - takes[index + 1].correctedStartFrame()).coerceAtLeast(0)
                    val after = minOf(CROSSFADE_FRAMES / 2L, (right.endFrame - right.startFrame) / 2,
                        takes[index].correctedEndFrame(project.asset(takes[index].assetHash)) - boundary).coerceAtLeast(0)
                    if (before + after == 0L) null else Join(boundary - before, boundary + after)
                }
            }
            val spans = buildList {
                fun addSpan(start: Long, end: Long, from: Take?, to: Take? = from, ramp: Boolean = false) {
                    if (end > start) add(VocalMixSpan(start, end, from, to, ramp))
                }
                for (index in lines.indices) {
                    val line = lines[index]
                    val take = takes[index]
                    val left = joins.getOrNull(index - 1)
                    val right = joins.getOrNull(index)
                    val start = left?.end ?: line.startFrame
                    val end = right?.start ?: line.endFrame
                    val sameBefore = lines.getOrNull(index - 1)?.let { it.endFrame == line.startFrame && it.takeId == line.takeId } == true
                    val sameAfter = lines.getOrNull(index + 1)?.let { it.startFrame == line.endFrame && it.takeId == line.takeId } == true
                    // Without overlap handles, ramp to/from silence inside the original interval. Never invent source frames.
                    val edge = minOf(CROSSFADE_FRAMES.toLong(), (end - start) / 2)
                    val fadeIn = if (left == null && !sameBefore) edge else 0
                    val fadeOut = if (right == null && !sameAfter) edge else 0
                    addSpan(start, start + fadeIn, null, take, true)
                    addSpan(start + fadeIn, end - fadeOut, take)
                    addSpan(end - fadeOut, end, take, null, true)
                    if (right != null) addSpan(right.start, right.end, take, takes[index + 1], true)
                    val next = lines.getOrNull(index + 1)
                    if (next != null && line.endFrame < next.startFrame) addSpan(line.endFrame, next.startFrame, null)
                }
            }
            check(spans.first().start == draft.startFrame && spans.last().end == draft.endFrame)
            check(spans.zipWithNext().all { (a, b) -> a.end == b.start })
            return VocalCompMix(draft, spans.frozen())
        }
    }
}
