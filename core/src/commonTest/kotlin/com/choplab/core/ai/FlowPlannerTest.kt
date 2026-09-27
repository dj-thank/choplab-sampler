package com.choplab.core.ai

import com.choplab.core.edit.*
import com.choplab.core.lyrics.LyricEdits
import com.choplab.core.lyrics.LyricResult
import com.choplab.core.model.*
import kotlin.test.*

class FlowPlannerTest {
    private fun placement(reading: String = "あいうえおかきくけこさしすせそた") = LyricProposal("川", LyricLanguage.JAPANESE,
        frozenListOf(ProposalSection("一番", LyricSectionKind.VERSE, 4,
            frozenListOf(ProposalLine.create("歌う", reading, LyricLanguage.JAPANESE), ProposalLine.create("次", "つぎ", LyricLanguage.JAPANESE)))))
        .placeStructured(0, 4, "lyric")
    private fun project() = placement().let { Project(lyrics = it.lines, lyricStructure = it.structure) }

    @Test fun explicitModesUseExactSixteenthsAndRetainTitleSectionAndReading() {
        val project = project()
        val plan = assertIs<FlowResult.Ready>(FlowPlanner.plan(project, 240, FlowMode.ONE_BAR, mapOf("lyric-1" to FlowMode.DOUBLE_TIME))).plan
        assertEquals(listOf(240L, 4080L), plan.rows.map { it.line.startTick })
        assertEquals(6000L, plan.rows.last().line.endTick)
        assertEquals(listOf(16, 8), plan.rows.map { it.sixteenths })
        assertNull(plan.rows.first().advice)
        assertEquals(16, plan.rows.first().units)
        assertEquals(project.lyricStructure, plan.structure)
        assertEquals(4, plan.structure.sections.single().bars)
        val two = assertIs<FlowResult.Ready>(FlowPlanner.plan(project, 0, FlowMode.TWO_BARS)).plan
        assertEquals(32, two.rows.first().sixteenths)
        assertEquals(15_360L, two.lines.last().endTick)
        assertEquals(FlowAdvice.TRY_DOUBLE_TIME, two.rows.last().advice)
        val dense = placement("あ".repeat(33)).let { Project(lyrics = it.lines, lyricStructure = it.structure) }
        assertEquals(FlowAdvice.SPLIT_OR_TWO_BARS, assertIs<FlowResult.Ready>(FlowPlanner.plan(dense, 0, FlowMode.TWO_BARS)).plan.rows.first().advice)
        assertIs<FlowResult.Invalid>(FlowPlanner.plan(project, 1, FlowMode.ONE_BAR))
        assertIs<FlowResult.Invalid>(FlowPlanner.plan(project, ProjectLimits.MAX_TIMELINE_TICKS, FlowMode.ONE_BAR))
    }

    @Test fun textEditsAreStaleAndDeletedThenReusedIdsDoNotInheritReadings() {
        val original = project()
        val edited = Reducer.reduce(original, Intent.SetLyrics(original.lyrics.map { it.copy(text = "違う本文") }.frozen()))
        assertTrue(edited.effects.isEmpty(), "Metadata edits preserve the playing audio graph")
        assertEquals(FlowProblem.STALE_READING, assertIs<FlowResult.Invalid>(FlowPlanner.plan(edited.project, 0, FlowMode.ONE_BAR)).issue.problem)
        val removed = Reducer.reduce(original, Intent.SetLyrics(frozenListOf())).project
        val reused = Reducer.reduce(removed, Intent.SetLyrics(original.lyrics.map { it.copy(text = "別人の本文") }.frozen())).project
        assertTrue(reused.lyricStructure!!.sections.isEmpty())
        assertEquals(FlowProblem.NO_LINES, assertIs<FlowResult.Invalid>(FlowPlanner.plan(reused, 0, FlowMode.ONE_BAR)).issue.problem)
    }

    @Test fun metricsAreVerifiedAndWordEditsDeclareManualTiming() {
        val p = placement()
        val section = p.structure.sections.single()
        assertFailsWith<IllegalArgumentException> { p.structure.copy(sections = frozenListOf(section.copy(lines = frozenListOf(section.lines.first().copy(mora = 1))))) }
        val word = LyricWord("歌", 0, 960, WordTimingOrigin.ESTIMATED)
        val line = LyricLine("a", "歌", 0, 1920, frozenListOf(word))
        val moved = assertIs<LyricResult.Success<FrozenList<LyricLine>>>(LyricEdits.retimeWord(listOf(line), "a", 0, 100, 900)).value
        assertEquals(WordTimingOrigin.MANUAL, moved.single().words.single().timingOrigin)
        val changed = Reducer.reduce(Project(lyrics = frozenListOf(line)), Intent.SetLyrics(moved))
        assertTrue(changed.effects.isEmpty())
    }
}
