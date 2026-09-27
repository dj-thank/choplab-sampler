package com.choplab.core.lyrics

import com.choplab.core.model.*
import kotlin.test.*

class LyricEditsTest {
    private val first = LyricLine("first", "朝です", 0, 1920, frozenListOf(LyricWord("朝", 0, 960), LyricWord("です", 960, 1920)))
    private val next = LyricLine("next", "次", 3840, 5760)
    private val lines = listOf(first, next)
    private fun <T> value(result: LyricResult<T>): T = assertIs<LyricResult.Success<T>>(result).value
    private fun code(result: LyricResult<*>) = assertIs<LyricResult.Failure>(result).issue.problem

    @Test fun replacingOneTextKeepsEveryOtherLineAndInvalidatesOnlyItsWordAlignment() {
        val edited = value(LyricEdits.replaceText(lines, "first", "おはよう"))
        assertEquals("おはよう", edited.first().text)
        assertEquals(first.startTick to first.endTick, edited.first().startTick to edited.first().endTick)
        assertTrue(edited.first().words.isEmpty())
        assertSame(next, edited.last())
        assertSame(first, value(LyricEdits.replaceText(lines, "first", "朝です")).first())
        assertEquals(LyricProblem.INVALID_TEXT, code(LyricEdits.replaceText(lines, "first", "bad\nrow")))
        assertEquals(LyricProblem.UNKNOWN_LINE, code(LyricEdits.replaceText(lines, "missing", "a")))
    }

    @Test fun tapMovesWordsWithTheirRowAndRefusesCrossingTheAdjacentStartOrClippingWords() {
        val tapped = value(LyricEdits.tapLineStart(lines, "first", 480))
        assertEquals(480L to 2400L, tapped.first().startTick to tapped.first().endTick)
        assertEquals(listOf(LyricWord("朝", 480, 1440), LyricWord("です", 1440, 2400)), tapped.first().words)
        assertSame(next, tapped.last())
        assertEquals(LyricProblem.INVALID_ORDER, code(LyricEdits.tapLineStart(lines, "first", 4000)))
        assertEquals(LyricProblem.INVALID_ORDER, code(LyricEdits.retimeLine(lines, "first", 0, 1800)))
        assertEquals(LyricProblem.TIME_OUT_OF_RANGE, code(LyricEdits.tapLineStart(lines, "first", ProjectLimits.MAX_TIMELINE_TICKS)))
        val sameStart = value(LyricEdits.tapLineStart(lines, "first", 3840))
        assertEquals(listOf(3840L, 3840L), sameStart.map { it.startTick })
        assertEquals(lines, listOf(first, next), "Failed edits and successful copies never mutate input")
    }

    @Test fun wordEditsRefuseOverlapAndLineOverflowWithoutChangingOtherWords() {
        val changed = value(LyricEdits.retimeWord(lines, "first", 0, 100, 900))
        assertEquals(LyricWord("朝", 100, 900), changed.first().words.first())
        assertSame(first.words.last(), changed.first().words.last())
        assertSame(next, changed.last())
        assertEquals(LyricProblem.INVALID_ORDER, code(LyricEdits.retimeWord(lines, "first", 0, 0, 1000)))
        assertEquals(LyricProblem.INVALID_ORDER, code(LyricEdits.retimeWord(lines, "first", 1, 960, 2000)))
        assertEquals(LyricProblem.UNKNOWN_WORD, code(LyricEdits.retimeWord(lines, "first", -1, 0, 10)))
    }

    @Test fun insertionRemovalAndLookupKeepSimultaneousRowsAndExclusiveBoundaries() {
        val call = LyricLine("call", "声", 0, 960)
        val inserted = value(LyricEdits.insertLine(lines, call))
        assertEquals(listOf("first", "call", "next"), inserted.map { it.id })
        assertEquals(LyricProblem.DUPLICATE_ID, code(LyricEdits.insertLine(lines, first)))
        assertEquals(lines, value(LyricEdits.removeLine(inserted, "call")))
        val opening = value(LyricSynchronization.at(inserted, 0))
        assertEquals(listOf("first", "call"), opening.current.map { it.line.id })
        assertEquals(0, opening.current.first().wordIndex)
        assertEquals(listOf(next), opening.next)
        val seam = value(LyricSynchronization.at(inserted, 960))
        assertEquals(listOf("first"), seam.current.map { it.line.id })
        assertEquals(1, seam.current.single().wordIndex)
        assertTrue(value(LyricSynchronization.at(inserted, 1920)).current.isEmpty())
        assertTrue(value(LyricSynchronization.at(inserted, 5760)).next.isEmpty())
        assertEquals(LyricProblem.INVALID_ORDER, code(LyricSynchronization.at(lines.reversed(), 0)))
    }
}
