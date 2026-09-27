package com.choplab.core.lyrics

import com.choplab.core.model.*

/** Pure edits for Intent.SetLyrics: the caller commits the returned snapshot once, for one Undo. */
object LyricEdits {
    /** Text edits invalidate only this row's word timings; the other rows retain identity and timing. */
    fun replaceText(lines: List<LyricLine>, id: String, text: String): LyricResult<FrozenList<LyricLine>> = edit(lines, id) { line ->
        if (text.length > 4096 || text.any { it == '\u0000' || it == '\r' || it == '\n' }) return@edit problem(LyricProblem.INVALID_TEXT)
        LyricResult.Success(if (text == line.text) line else line.copy(text = text, words = frozenListOf()))
    }

    /** Moves the row and all its words by the start delta. Rejects rather than clipping word timing. */
    fun retimeLine(lines: List<LyricLine>, id: String, startTick: Long, endTick: Long): LyricResult<FrozenList<LyricLine>> = edit(lines, id) { line ->
        if (startTick < 0 || endTick > ProjectLimits.MAX_TIMELINE_TICKS) return@edit problem(LyricProblem.TIME_OUT_OF_RANGE)
        if (endTick <= startTick) return@edit problem(LyricProblem.INVALID_ORDER)
        val delta = startTick - line.startTick
        if (line.words.any { it.startTick + delta < startTick || it.endTick + delta > endTick }) return@edit problem(LyricProblem.INVALID_ORDER)
        val words = line.words.map { it.copy(startTick = it.startTick + delta, endTick = it.endTick + delta) }
        LyricResult.Success(line.copy(startTick = startTick, endTick = endTick, words = words.frozen()))
    }

    /** A tap is already a musical tick sampled from the song clock; preserves this row's duration. */
    fun tapLineStart(lines: List<LyricLine>, id: String, tick: Long): LyricResult<FrozenList<LyricLine>> {
        val line = lines.firstOrNull { it.id == id } ?: return problem(LyricProblem.UNKNOWN_LINE)
        if (tick !in 0..ProjectLimits.MAX_TIMELINE_TICKS || tick > ProjectLimits.MAX_TIMELINE_TICKS - (line.endTick - line.startTick)) return problem(LyricProblem.TIME_OUT_OF_RANGE)
        return retimeLine(lines, id, tick, tick + line.endTick - line.startTick)
    }

    fun retimeWord(lines: List<LyricLine>, id: String, index: Int, startTick: Long, endTick: Long): LyricResult<FrozenList<LyricLine>> = edit(lines, id) { line ->
        val word = line.words.getOrNull(index) ?: return@edit problem(LyricProblem.UNKNOWN_WORD)
        if (startTick < line.startTick || endTick > line.endTick || endTick <= startTick) return@edit problem(LyricProblem.INVALID_ORDER)
        if (index > 0 && line.words[index - 1].endTick > startTick || index < line.words.lastIndex && endTick > line.words[index + 1].startTick) return@edit problem(LyricProblem.INVALID_ORDER)
        LyricResult.Success(line.copy(words = line.words.mapIndexed { i, original -> if (i == index) word.copy(startTick = startTick, endTick = endTick, timingOrigin = WordTimingOrigin.MANUAL) else original }.frozen()))
    }

    /** Inserts by start time, after existing simultaneous rows, retaining all existing IDs and objects. */
    fun insertLine(lines: List<LyricLine>, line: LyricLine): LyricResult<FrozenList<LyricLine>> {
        invalid(lines)?.let { return LyricResult.Failure(it) }
        if (lines.size >= 4096) return problem(LyricProblem.TOO_MANY_LINES)
        if (lines.any { it.id == line.id }) return problem(LyricProblem.DUPLICATE_ID)
        val at = lines.indexOfFirst { it.startTick > line.startTick }.let { if (it < 0) lines.size else it }
        return LyricResult.Success((lines.take(at) + line + lines.drop(at)).frozen())
    }

    fun removeLine(lines: List<LyricLine>, id: String): LyricResult<FrozenList<LyricLine>> {
        invalid(lines)?.let { return LyricResult.Failure(it) }
        if (lines.none { it.id == id }) return problem(LyricProblem.UNKNOWN_LINE)
        return LyricResult.Success(lines.filterNot { it.id == id }.frozen())
    }

    private inline fun edit(lines: List<LyricLine>, id: String, transform: (LyricLine) -> LyricResult<LyricLine>): LyricResult<FrozenList<LyricLine>> {
        invalid(lines)?.let { return LyricResult.Failure(it) }
        val index = lines.indexOfFirst { it.id == id }
        if (index < 0) return problem(LyricProblem.UNKNOWN_LINE)
        return when (val result = transform(lines[index])) {
            is LyricResult.Failure -> result
            is LyricResult.Success -> {
                val after = lines.mapIndexed { i, line -> if (i == index) result.value else line }
                invalid(after)?.let { LyricResult.Failure(it) } ?: LyricResult.Success(after.frozen())
            }
        }
    }
    internal fun invalid(lines: List<LyricLine>): LyricIssue? = when {
        lines.size > 4096 -> LyricIssue(LyricProblem.TOO_MANY_LINES)
        lines.map { it.id }.distinct().size != lines.size -> LyricIssue(LyricProblem.DUPLICATE_ID)
        lines.zipWithNext().any { (a, b) -> a.startTick > b.startTick } -> LyricIssue(LyricProblem.INVALID_ORDER)
        else -> null
    }
    private fun problem(code: LyricProblem) = LyricResult.Failure(LyricIssue(code))
}

data class ActiveLyric(val line: LyricLine, /** Null in a word gap, or when the line has no word timing. */ val wordIndex: Int?)
data class LyricPosition(val current: FrozenList<ActiveLyric>, val next: FrozenList<LyricLine>)

/** All simultaneous/overlapping rows are visible; boundaries are start-inclusive/end-exclusive. */
object LyricSynchronization {
    fun at(lines: List<LyricLine>, tick: Long): LyricResult<LyricPosition> {
        LyricEdits.invalid(lines)?.let { return LyricResult.Failure(it) }
        if (tick !in 0..ProjectLimits.MAX_TIMELINE_TICKS) return LyricResult.Failure(LyricIssue(LyricProblem.TIME_OUT_OF_RANGE))
        val current = lines.filter { tick >= it.startTick && tick < it.endTick }.map { line ->
            ActiveLyric(line, line.words.indexOfFirst { tick >= it.startTick && tick < it.endTick }.takeIf { it >= 0 })
        }
        val nextTick = lines.firstOrNull { it.startTick > tick }?.startTick
        return LyricResult.Success(LyricPosition(current.frozen(), lines.filter { it.startTick == nextTick }.frozen()))
    }
}
