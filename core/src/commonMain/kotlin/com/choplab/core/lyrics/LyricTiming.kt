package com.choplab.core.lyrics

import com.choplab.core.model.ProjectLimits

enum class LyricProblem {
    TOO_LARGE, TOO_MANY_LINES, TOO_MANY_WORDS, TOO_MANY_METADATA,
    MALFORMED_TAG, MALFORMED_TIMESTAMP, UNTYPED_TEXT, DUPLICATE_OFFSET,
    NEGATIVE_TIME, TIME_OUT_OF_RANGE, INVALID_ORDER, TIMING_COLLAPSE,
    INVALID_TEXT, DUPLICATE_ID, UNKNOWN_LINE, UNKNOWN_WORD,
    UNREPRESENTABLE_END, WORD_TEXT_MISMATCH,
}

data class LyricIssue(val problem: LyricProblem, /** One-based input row, when available. */ val inputLine: Int? = null)
sealed interface LyricResult<out T> {
    data class Success<T>(val value: T) : LyricResult<T>
    data class Failure(val issue: LyricIssue) : LyricResult<Nothing>
}

/**
 * Explicit constant-tempo conversion between an LRC's absolute milliseconds and the document's
 * musical ticks. Imported lyrics become beat anchors: changing the project tempo later moves them.
 * Swing is deliberately not applied to absolute lyric timestamps. Conversion rounds to nearest,
 * ties upward within the document bounds; millisecond export/import differs by at most two ticks
 * throughout the supported 40–240 BPM range (at most 0.521 ms at 240 BPM).
 */
data class LyricTiming(val milliBpm: Int) {
    init { require(milliBpm in 40_000..240_000) }
    fun millisecondsToTick(milliseconds: Long): Long {
        require(milliseconds in 0..maximumMilliseconds)
        return ((milliseconds * milliBpm * ProjectLimits.PPQ + 30_000_000L) / 60_000_000L).coerceAtMost(ProjectLimits.MAX_TIMELINE_TICKS)
    }
    fun tickToMilliseconds(tick: Long): Long {
        require(tick in 0..ProjectLimits.MAX_TIMELINE_TICKS)
        val denominator = milliBpm.toLong() * ProjectLimits.PPQ
        return (tick * 60_000_000L + denominator / 2) / denominator
    }
    val maximumMilliseconds: Long get() = tickToMilliseconds(ProjectLimits.MAX_TIMELINE_TICKS)
}

data class LrcLimits(
    val maximumCharacters: Int = 1_048_576,
    val maximumLines: Int = 4096,
    val maximumLineCharacters: Int = 8192,
    val maximumMetadata: Int = 32,
) {
    init {
        require(maximumCharacters in 1..1_048_576 && maximumLines in 1..4096)
        require(maximumLineCharacters in 1..8192 && maximumMetadata in 0..32)
    }
}
