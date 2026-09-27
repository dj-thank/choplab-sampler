package com.choplab.core.lyrics

import com.choplab.core.model.*
import kotlin.math.abs
import kotlin.test.*

class LrcCodecTest {
    private val timing = LyricTiming(120_000)
    private fun <T> value(result: LyricResult<T>): T = assertIs<LyricResult.Success<T>>(result).value
    private fun fails(code: LyricProblem, text: String, limits: LrcLimits = LrcLimits()) {
        assertEquals(code, assertIs<LyricResult.Failure>(LrcCodec.parse(text, timing, 20_000, limits)).issue.problem)
    }

    @Test fun japaneseMetadataOffsetAndRepeatedRowsUseAbsoluteTimeAtTheChosenTempo() {
        val imported = value(LrcCodec.parse("\uFEFF[ti:日本語の歌]\r\n[00:01.500][00:03.500]あいうえお\r\n[offset:+500]\r\n[00:04.500]次の行", timing, 10_000))
        assertEquals(listOf(1920L, 5760L, 7680L), imported.lines.map { it.startTick })
        assertEquals(listOf(5760L, 7680L, 10_000L), imported.lines.map { it.endTick })
        assertEquals(listOf("あいうえお", "あいうえお", "次の行"), imported.lines.map { it.text })
        assertEquals(3, imported.lines.map { it.id }.distinct().size)
        assertEquals(listOf(LrcMetadata("ti", "日本語の歌")), imported.metadata)
        assertEquals(500, imported.offsetMilliseconds)
        assertEquals(timing, imported.importedTiming)
        val slower = value(LrcCodec.parse("[00:01.000]歌", LyricTiming(60_000), 4000))
        assertEquals(960, slower.lines.single().startTick, "LRC seconds are not already ticks, and swing is not applied")
        assertEquals(1000, LyricTiming(60_000).tickToMilliseconds(960))
        assertEquals(500, timing.tickToMilliseconds(960), "Stored beat anchors move when tempo changes")
    }

    @Test fun simultaneousRowsAndClearMarkersPreserveSilenceInsteadOfFreezingThePreviousRow() {
        val imported = value(LrcCodec.parse("[00:01]歌\n[00:01]掛け声\n[00:02]\n[00:04]あと", timing, 10_000))
        assertEquals(listOf(1920L, 1920L, 7680L), imported.lines.map { it.startTick })
        assertEquals(listOf(3840L, 3840L, 10_000L), imported.lines.map { it.endTick })
        assertEquals(emptyList(), value(LyricSynchronization.at(imported.lines, 5000)).current)
        val duplicates = value(LrcCodec.parse("[00:01][00:01]同じ歌", timing, 4000)).lines
        assertEquals(2, duplicates.size)
        for (format in LrcFormat.entries) {
            val exported = value(LrcCodec.export(duplicates, timing, format, LrcLimits(maximumLines = 2)))
            assertEquals(2, value(LrcCodec.parse(exported.text, timing, 4000, LrcLimits(maximumLines = 2))).lines.size)
        }
    }

    @Test fun enhancedWordsKeepGapsAndRepeatedTimestampsShiftAllWordTimes() {
        val imported = value(LrcCodec.parse("[00:01.000][00:05.000]<00:01.000>あ<00:01.500><00:01.750>い<00:02.000>", timing, 15_000))
        assertEquals(listOf("あい", "あい"), imported.lines.map { it.text })
        assertEquals(listOf(3840L, 11_520L), imported.lines.map { it.endTick })
        assertEquals(listOf(LyricWord("あ", 1920, 2880), LyricWord("い", 3360, 3840)), imported.lines.first().words)
        assertEquals(listOf(LyricWord("あ", 9600, 10_560), LyricWord("い", 11_040, 11_520)), imported.lines.last().words)
        assertNull(value(LyricSynchronization.at(imported.lines, 3100)).current.single().wordIndex)
        assertEquals(1, value(LyricSynchronization.at(imported.lines, 3360)).current.single().wordIndex)
    }

    @Test fun extendedRoundtripPreservesOverlappingRowsIndependentEndsAndWordsWithinTwoTicks() {
        for (tempo in listOf(40_000, 97_125, 120_000, 240_000)) {
            val clock = LyricTiming(tempo)
            val lines = listOf(
                LyricLine("a", "朝です", 1, 5987, frozenListOf(LyricWord("朝", 203, 997), LyricWord("です", 1234, 5987))),
                LyricLine("b", "重なる歌", 1000, 8001),
                LyricLine("c", "同時", 1000, 9000),
            )
            val first = value(LrcCodec.export(lines, clock))
            assertFalse(first.omittedWordTiming)
            val reopened = value(LrcCodec.parse(first.text, clock, 12_000)).lines
            assertEquals(lines.map { it.text }, reopened.map { it.text })
            lines.zip(reopened).forEach { (old, new) ->
                assertTrue(abs(old.startTick - new.startTick) <= 2)
                assertTrue(abs(old.endTick - new.endTick) <= 2)
                old.words.zip(new.words).forEach { (a, b) ->
                    assertEquals(a.text, b.text)
                    assertTrue(abs(a.startTick - b.startTick) <= 2)
                    assertTrue(abs(a.endTick - b.endTick) <= 2)
                }
            }
            assertEquals(first.text, value(LrcCodec.export(reopened, clock)).text, "The normalized representation is stable")
            val atEnd = listOf(LyricLine("edge", "終わり", 1000, 8001))
            val ending = value(LrcCodec.export(atEnd, clock)).text
            assertTrue(abs(value(LrcCodec.parse(ending, clock, 8001)).lines.single().endTick - 8001) <= 2,
                "An explicit end remains valid when millisecond quantization moves it past the fallback end")
        }
    }

    @Test fun standardExportReportsOmittedWordTimingAndRefusesUnrepresentableOverlap() {
        val lines = listOf(LyricLine("a", "歌", 1920, 3840, frozenListOf(LyricWord("歌", 1920, 3840))), LyricLine("b", "次", 7680, 9600))
        val output = value(LrcCodec.export(lines, timing, LrcFormat.STANDARD))
        assertTrue(output.omittedWordTiming)
        val reopened = value(LrcCodec.parse(output.text, timing, 9600)).lines
        assertEquals(lines.map { it.startTick to it.endTick }, reopened.map { it.startTick to it.endTick })
        assertTrue(reopened.all { it.words.isEmpty() })
        for (overlap in listOf(listOf(LyricLine("a", "A", 0, 4000), LyricLine("b", "B", 1000, 5000)),
            listOf(LyricLine("a", "A", 0, 4000), LyricLine("b", "B", 0, 5000)))) {
            assertEquals(LyricProblem.UNREPRESENTABLE_END, assertIs<LyricResult.Failure>(LrcCodec.export(overlap, timing, LrcFormat.STANDARD)).issue.problem)
        }
    }

    @Test fun malformedRowsOffsetsWordOrderingAndResourceLimitsRejectTheWholeInput() {
        fails(LyricProblem.MALFORMED_TIMESTAMP, "[00:60]bad")
        fails(LyricProblem.MALFORMED_TIMESTAMP, "[00:01.1234]bad")
        fails(LyricProblem.MALFORMED_TAG, "[00:01")
        fails(LyricProblem.UNTYPED_TEXT, "[00:01]valid\nuntimed row")
        fails(LyricProblem.DUPLICATE_OFFSET, "[offset:1]\n[offset:2]\n[00:01]歌")
        fails(LyricProblem.NEGATIVE_TIME, "[offset:+1001]\n[00:01]歌")
        fails(LyricProblem.TIME_OUT_OF_RANGE, "[offset:9223372036854775807]\n[00:01]歌")
        fails(LyricProblem.TIME_OUT_OF_RANGE, "[999999:59]歌")
        fails(LyricProblem.INVALID_ORDER, "[00:01]<00:01.500>歌<00:01.100>声")
        fails(LyricProblem.INVALID_ORDER, "[00:01]<00:00.500>歌<00:02>")
        fails(LyricProblem.INVALID_TEXT, "[00:01]歌\u0000")
        fails(LyricProblem.TOO_LARGE, "[00:01]歌", LrcLimits(maximumCharacters = 4))
        fails(LyricProblem.TOO_MANY_LINES, "[00:01][00:02]歌", LrcLimits(maximumLines = 1))
        fails(LyricProblem.TOO_MANY_METADATA, "[ti:歌]\n[ar:人]", LrcLimits(maximumMetadata = 1))
        fails(LyricProblem.TOO_MANY_WORDS, "[00:00]" + (0..256).joinToString("") { "<00:00.001>あ" })
        fails(LyricProblem.TIMING_COLLAPSE, "[00:01]歌<00:01>")
        val negative = value(LrcCodec.parse("[offset:-250]\n[00:01]歌", timing, 4000))
        assertEquals(2400, negative.lines.single().startTick)
        assertEquals(LyricProblem.WORD_TEXT_MISMATCH, assertIs<LyricResult.Failure>(LrcCodec.export(listOf(LyricLine("a", "別の歌", 0, 960, frozenListOf(LyricWord("歌", 0, 960)))), timing)).issue.problem)
    }

    @Test fun conversionHasBoundedErrorAtTempoAndDocumentEdges() {
        for (tempo in listOf(40_000, 97_125, 240_000)) {
            val clock = LyricTiming(tempo)
            for (tick in listOf(0L, 1L, 959L, 960L, 1_234_567L, ProjectLimits.MAX_TIMELINE_TICKS)) {
                assertTrue(abs(clock.millisecondsToTick(clock.tickToMilliseconds(tick)) - tick) <= 2, "quantization at $tempo / $tick")
            }
        }
        assertFailsWith<IllegalArgumentException> { LyricTiming(0) }
        assertFailsWith<IllegalArgumentException> { timing.millisecondsToTick(Long.MAX_VALUE) }
    }
}
