package com.choplab.core.lyrics

import com.choplab.core.model.*

data class LrcMetadata(val key: String, val value: String)
data class LrcImport(
    val lines: FrozenList<LyricLine>,
    val metadata: FrozenList<LrcMetadata>,
    val importedTiming: LyricTiming,
    /** Positive LRC offset advances lyrics: this many milliseconds were subtracted. */
    val offsetMilliseconds: Long,
)
enum class LrcFormat { STANDARD, EXTENDED }
data class LrcExport(val text: String, /** Standard LRC intentionally omits word timing. */ val omittedWordTiming: Boolean)

/**
 * Strict, bounded LRC codec. Untimed/malformed rows fail the whole import rather than disappearing.
 * Supports mm:ss, mm:ss.d/dd/ddd, multiple [timestamps], metadata and signed [offset:milliseconds].
 * Blank timestamp rows clear the display and provide end boundaries, without becoming lyric lines.
 * Enhanced <timestamps> start words; a final marker ends the last word and line. Without an explicit
 * end, the next distinct row/clear timestamp ends a line, or the caller's explicit finalEndTick does.
 * finalEndTick is a fallback for an open final row, not a cutoff for explicitly timed rows.
 * Repeated enhanced rows shift their word times relative to their first [timestamp].
 */
object LrcCodec {
    private class Invalid(val issue: LyricIssue) : RuntimeException()
    private data class RawWord(val text: String, val start: Long, val end: Long?)
    private data class RawRow(val start: Long, val text: String, val words: List<RawWord>, val end: Long?, val inputLine: Int)
    private val timestamp = Regex("([0-9]{1,6}):([0-9]{2})(?:\\.([0-9]{1,3}))?")
    private val metadataTag = Regex("([A-Za-z][A-Za-z0-9_-]{0,15}):(.*)")

    fun parse(text: String, timing: LyricTiming, finalEndTick: Long, limits: LrcLimits = LrcLimits()): LyricResult<LrcImport> = attempt {
        if (text.length > limits.maximumCharacters) fail(LyricProblem.TOO_LARGE)
        if (finalEndTick !in 1..ProjectLimits.MAX_TIMELINE_TICKS) fail(LyricProblem.TIME_OUT_OF_RANGE)
        val rows = mutableListOf<RawRow>()
        val metadata = mutableListOf<LrcMetadata>()
        var offset: Long? = null
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, input ->
            val number = index + 1
            val line = input.removeSuffix("\r")
            if (line.length > limits.maximumLineCharacters) fail(LyricProblem.TOO_LARGE, number)
            if (line.isBlank()) return@forEachIndexed
            if (!line.startsWith('[')) fail(LyricProblem.UNTYPED_TEXT, number)
            val firstEnd = line.indexOf(']')
            if (firstEnd < 0) fail(LyricProblem.MALFORMED_TAG, number)
            val first = line.substring(1, firstEnd)
            val tag = metadataTag.matchEntire(first)
            if (tag != null && !first.first().isDigit()) {
                if (firstEnd != line.lastIndex) fail(LyricProblem.MALFORMED_TAG, number)
                val key = tag.groupValues[1].lowercase()
                val value = tag.groupValues[2]
                if (value.any { it.isISOControl() } || value.contains(']')) fail(LyricProblem.INVALID_TEXT, number)
                if (key == "offset") {
                    if (offset != null) fail(LyricProblem.DUPLICATE_OFFSET, number)
                    offset = value.toLongOrNull() ?: fail(LyricProblem.MALFORMED_TAG, number)
                    if (offset !in -timing.maximumMilliseconds..timing.maximumMilliseconds) fail(LyricProblem.TIME_OUT_OF_RANGE, number)
                } else {
                    if (metadata.size >= limits.maximumMetadata) fail(LyricProblem.TOO_MANY_METADATA, number)
                    metadata += LrcMetadata(key, value)
                }
                return@forEachIndexed
            }
            val starts = mutableListOf<Long>()
            var position = 0
            while (position < line.length && line[position] == '[') {
                val end = line.indexOf(']', position)
                if (end < 0) fail(LyricProblem.MALFORMED_TAG, number)
                starts += parseTime(line.substring(position + 1, end), number)
                // A standard export can carry one clear/end row per lyric; bound these separately.
                if (rows.size + starts.size > limits.maximumLines * 2 + 1) fail(LyricProblem.TOO_MANY_LINES, number)
                position = end + 1
            }
            val body = line.substring(position)
            if (body.contains('[') || body.contains(']') || body.contains('>') && !body.contains('<')) fail(LyricProblem.MALFORMED_TAG, number)
            val parsed = parseBody(body, number)
            starts.forEach { start ->
                val shift = start - starts.first()
                rows += RawRow(start, parsed.text, parsed.words.map { it.copy(start = it.start + shift, end = it.end?.plus(shift)) }, parsed.end?.plus(shift), number)
            }
        }
        val adjustment = offset ?: 0L
        fun tick(raw: Long, number: Int): Long {
            val adjusted = raw - adjustment
            if (adjusted < 0) fail(LyricProblem.NEGATIVE_TIME, number)
            if (adjusted > timing.maximumMilliseconds) fail(LyricProblem.TIME_OUT_OF_RANGE, number)
            return timing.millisecondsToTick(adjusted).also { if (it > ProjectLimits.MAX_TIMELINE_TICKS) fail(LyricProblem.TIME_OUT_OF_RANGE, number) }
        }
        val ordered = rows.sortedBy { it.start }
        ordered.forEach { tick(it.start, it.inputLine) }
        val boundaries = ordered.map { it.start }.distinct()
        if (ordered.count { it.text.isNotEmpty() } > limits.maximumLines) fail(LyricProblem.TOO_MANY_LINES)
        val lines = ordered.filter { it.text.isNotEmpty() }.mapIndexed { index, row ->
            val start = tick(row.start, row.inputLine)
            val end = row.end?.let { tick(it, row.inputLine) }
                ?: boundaries.firstOrNull { it > row.start }?.let { tick(it, row.inputLine) } ?: finalEndTick
            if (end <= start) fail(LyricProblem.TIMING_COLLAPSE, row.inputLine)
            val words = row.words.map { word ->
                val from = tick(word.start, row.inputLine)
                val to = word.end?.let { tick(it, row.inputLine) } ?: end
                if (from < start || to > end || to <= from) fail(LyricProblem.INVALID_ORDER, row.inputLine)
                LyricWord(word.text, from, to)
            }
            if (words.zipWithNext().any { (a, b) -> a.endTick > b.startTick }) fail(LyricProblem.INVALID_ORDER, row.inputLine)
            LyricLine("lrc-${index + 1}", row.text, start, end, words.frozen())
        }
        LrcImport(lines.frozen(), metadata.frozen(), timing, adjustment)
    }

    private data class Body(val text: String, val words: List<RawWord>, val end: Long?)
    private fun parseBody(body: String, number: Int): Body {
        if (body.any { it.isISOControl() }) fail(LyricProblem.INVALID_TEXT, number)
        if (!body.contains('<')) {
            if (body.length > 4096) fail(LyricProblem.INVALID_TEXT, number)
            return Body(body, emptyList(), null)
        }
        val first = body.indexOf('<')
        val prefix = body.substring(0, first)
        val markers = mutableListOf<Pair<Long, String>>()
        var position = first
        while (position < body.length) {
            if (body[position] != '<') fail(LyricProblem.MALFORMED_TAG, number)
            val close = body.indexOf('>', position)
            if (close < 0) fail(LyricProblem.MALFORMED_TAG, number)
            val time = parseTime(body.substring(position + 1, close), number)
            val next = body.indexOf('<', close + 1).let { if (it < 0) body.length else it }
            val token = body.substring(close + 1, next)
            if (token.contains('>')) fail(LyricProblem.MALFORMED_TAG, number)
            if (markers.isNotEmpty() && markers.last().first > time) fail(LyricProblem.INVALID_ORDER, number)
            markers += time to token
            if (markers.size > 513) fail(LyricProblem.TOO_MANY_WORDS, number)
            position = next
        }
        val plain = prefix + markers.joinToString("") { it.second }
        if (plain.length > 4096) fail(LyricProblem.INVALID_TEXT, number)
        // One empty final marker after untagged text only declares the line end; it creates no words.
        if (markers.size == 1 && markers.single().second.isEmpty()) return Body(plain, emptyList(), markers.single().first)
        if (prefix.isNotEmpty()) fail(LyricProblem.WORD_TEXT_MISMATCH, number)
        val words = markers.mapIndexedNotNull { index, (start, token) ->
            if (token.isEmpty()) null else {
                if (token.length > 256) fail(LyricProblem.INVALID_TEXT, number)
                RawWord(token, start, markers.getOrNull(index + 1)?.first)
            }
        }
        if (words.size > 256) fail(LyricProblem.TOO_MANY_WORDS, number)
        return Body(plain, words, markers.last().takeIf { it.second.isEmpty() }?.first)
    }

    /** Offset is already applied at import, so export writes resolved absolute times with no offset. */
    fun export(lines: List<LyricLine>, timing: LyricTiming, format: LrcFormat = LrcFormat.EXTENDED,
               limits: LrcLimits = LrcLimits()): LyricResult<LrcExport> = attempt {
        if (lines.size > limits.maximumLines) fail(LyricProblem.TOO_MANY_LINES)
        if (lines.map { it.id }.distinct().size != lines.size) fail(LyricProblem.DUPLICATE_ID)
        val ordered = lines.sortedBy { it.startTick }
        val events = mutableListOf<Pair<Long, String>>()
        val clearTimes = mutableSetOf<Long>()
        val starts = ordered.map { it.startTick }.distinct()
        val standardBoundaries = (starts + ordered.map { it.endTick }).distinct()
        for (line in ordered) {
            if (line.text.isEmpty() || line.text.any { it.isISOControl() || it in "[]<>" }) fail(LyricProblem.INVALID_TEXT)
            val start = timing.tickToMilliseconds(line.startTick)
            val end = timing.tickToMilliseconds(line.endTick)
            if (end <= start) fail(LyricProblem.TIMING_COLLAPSE)
            val content = if (format == LrcFormat.STANDARD) {
                // A clear marker cannot preserve a line overlapping a later line's start.
                if (standardBoundaries.any { it > line.startTick && it < line.endTick }) fail(LyricProblem.UNREPRESENTABLE_END)
                if (clearTimes.add(end)) events += end to "[${formatTime(end)}]"
                line.text
            } else if (line.words.isEmpty()) line.text + "<${formatTime(end)}>"
            else {
                if (line.words.joinToString("") { it.text } != line.text) fail(LyricProblem.WORD_TEXT_MISMATCH)
                buildString {
                    for (word in line.words) {
                        val from = timing.tickToMilliseconds(word.startTick)
                        val to = timing.tickToMilliseconds(word.endTick)
                        if (to <= from) fail(LyricProblem.TIMING_COLLAPSE)
                        append('<').append(formatTime(from)).append('>').append(word.text)
                        append('<').append(formatTime(to)).append('>')
                    }
                    if (line.words.last().endTick != line.endTick) append('<').append(formatTime(end)).append('>')
                }
            }
            val rendered = "[${formatTime(start)}]$content"
            if (rendered.length > limits.maximumLineCharacters) fail(LyricProblem.TOO_LARGE)
            events += start to rendered
        }
        // Empty clear markers before same-time sung rows also work with conventional LRC players.
        val output = events.sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { if (it.second.endsWith(']')) 0 else 1 })
            .joinToString("\n") { it.second }
        if (output.length > limits.maximumCharacters) fail(LyricProblem.TOO_LARGE)
        LrcExport(output, format == LrcFormat.STANDARD && lines.any { it.words.isNotEmpty() })
    }

    private fun parseTime(value: String, number: Int): Long {
        val match = timestamp.matchEntire(value) ?: fail(LyricProblem.MALFORMED_TIMESTAMP, number)
        val seconds = match.groupValues[2].toInt()
        if (seconds >= 60) fail(LyricProblem.MALFORMED_TIMESTAMP, number)
        val fraction = match.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toInt()
        return match.groupValues[1].toLong() * 60_000 + seconds * 1_000 + fraction
    }
    private fun formatTime(milliseconds: Long): String = "${(milliseconds / 60_000).toString().padStart(2, '0')}:${(milliseconds / 1_000 % 60).toString().padStart(2, '0')}.${(milliseconds % 1_000).toString().padStart(3, '0')}"
    private fun fail(problem: LyricProblem, number: Int? = null): Nothing = throw Invalid(LyricIssue(problem, number))
    private inline fun <T> attempt(block: () -> T): LyricResult<T> = try { LyricResult.Success(block()) } catch (invalid: Invalid) { LyricResult.Failure(invalid.issue) }
}
