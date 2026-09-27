package com.choplab.core.pattern

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.EngineCore
import com.choplab.engine.PlayMode
import com.choplab.engine.SequenceClock
import com.choplab.engine.Tempo
import kotlin.test.*

class PatternEditsTest {
    @Test fun draftLengthAndSelectedPadChangesAreBoundedAndOnePutIsOneUndo() {
        val project = project()
        var pattern = PatternEdits.resize(project.patterns.first(), 8)
        pattern = PatternEdits.toggle(project, pattern, 1, 127, .5f)
        assertEquals(128, pattern.lengthTicks / PatternEdits.STEP_TICKS)
        assertEquals(Note(30_480, 1, .5f), pattern.notes.single())
        assertEquals(PatternProblem.TRIM_REQUIRED, assertFailsWith<PatternEditException> { PatternEdits.resize(pattern, 1) }.problem)
        assertEquals(pattern, PatternEdits.resize(pattern, 8))
        assertTrue(PatternEdits.resize(pattern, 1, trim = true).notes.isEmpty())
        assertEquals(PatternProblem.EMPTY_PAD, assertFailsWith<PatternEditException> { PatternEdits.toggle(project, pattern, 100, 0, 1f) }.problem)
        val session = EditSession(project)
        val plan = session.plan(PatternEdits.put(project, pattern))
        assertEquals(project, session.project)
        plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
        assertEquals(1, session.undoCount)
        val undo = assertNotNull(session.planUndo())
        undo.effects.indices.forEach { session.acknowledge(undo, it) }; session.commit(undo)
        assertEquals(project, session.project)
        val copied = PatternEdits.create(project, "Second", pattern)
        assertNotEquals(pattern.id, copied.id); assertEquals(pattern.notes, copied.notes)
        assertEquals(8, copied.bars)
    }

    @Test fun quantizationKeepsOtherPadsAndStrongestCollisionsAndCannotWrapAtTheEnd() {
        val pattern = Pattern("p", notes = frozenListOf(Note(119, 0, .2f), Note(120, 0, .5f), Note(239, 0, .8f),
            Note(3839, 0, .9f), Note(119, 1, .7f)))
        val result = PatternEdits.quantize(pattern, 0, 240)
        assertEquals(listOf(Note(0, 0, .2f), Note(119, 1, .7f), Note(240, 0, .8f), Note(3600, 0, .9f)), result.notes)
        assertEquals(pattern.notes.filter { it.padId == 1 }, result.notes.filter { it.padId == 1 })
        assertEquals(1, PatternEdits.clear(result, 0).notes.size)
    }

    @Test fun repeatsUseAbsoluteTicksWithNoCumulativeFrameRoundingOrLostSwing() {
        for (tempo in listOf(Tempo(97_125, 500), Tempo(97_125, 710), Tempo(240_000, 750), Tempo(40_000, 540))) {
            val base = project(tempo).copy(patterns = frozenListOf(Pattern("a", bars = 1, notes = frozenListOf(Note(0, 0), Note(240, 1))),
                Pattern("b", bars = 2, notes = frozenListOf(Note(720, 0), Note(7440, 1)))))
            val plan = PatternPlacement.plan(base, listOf(SongSection("a", 64), SongSection("b", 3)), 3L * PatternEdits.BAR_TICKS)
            assertEquals(73L * PatternEdits.BAR_TICKS, plan.endTick)
            assertEquals(134, plan.voices.size)
            for (cycle in 0 until 64) {
                val tick = (3L + cycle) * PatternEdits.BAR_TICKS + 240
                val voice = plan.voices[cycle * 2 + 1]
                assertEquals(tick, voice.startTick)
                val exact = SequenceClock.targetNumerator(tick, tempo.swingPermille)
                assertEquals(exact / tempo.milliBpm, ProgramCompiler.clipTickToFrame(voice.startTick, tempo))
                val ceil = (exact + tempo.milliBpm - 1) / tempo.milliBpm
                assertTrue(ceil - ProgramCompiler.clipTickToFrame(voice.startTick, tempo) in 0..1,
                    "Existing pattern scheduling uses ceil; arrangement preserves its floor mapping without accumulating the difference")
            }
            for (step in listOf(0L, 1L, 2L, 63L, 127L)) {
                val tick = step * 240
                val numerator = SequenceClock.targetNumerator(tick, tempo.swingPermille)
                val audible = (numerator + tempo.milliBpm - 1) / tempo.milliBpm
                assertEquals(tick, PatternEdits.nearestStepTick(audible, tempo))
            }
        }
    }

    @Test fun loopAndChokeVoicesHaveExplicitExclusiveBoundsAndClipGainIsNotBakedTwice() {
        val initial = project()
        val base = initial.copy(pads = initial.pads.map { pad -> when (pad.id) {
            0 -> pad.copy(mode = PlayMode.LOOP, gain = .5f, pan = 1f, chokeGroup = 1, releaseFrames = 192)
            1 -> pad.copy(chokeGroup = 1)
            else -> pad
        } }.frozen(), patterns = frozenListOf(Pattern("a", notes = frozenListOf(Note(0, 0, .25f), Note(240, 1), Note(480, 0, .75f)))))
        val plan = PatternPlacement.plan(base, listOf(SongSection("a", 2)), 0)
        assertEquals(6_000, plan.voices.first().render.releaseAt)
        assertEquals(6_192, plan.voices.first().render.limitFrames)
        val last = plan.voices.last().render
        assertNotNull(last.stopAt)
        assertEquals(last.stopAt!! + EngineCore.STEAL_FADE_FRAMES, last.limitFrames)
        val renders = plan.renders.associateWith { rendered(it, base) }
        var id = 0
        val intent = PatternPlacement.intent(base, plan, renders, { "fresh-${++id}" }, "Patterns")
        assertEquals(6, intent.clips.size)
        assertEquals(.25f, intent.clips.first().gain)
        assertEquals(0f, intent.clips.first().pan)
        assertNull(intent.clips.first().timelineStartFrame)
        assertEquals(renders.getValue(plan.voices.first().render).frames, intent.clips.first().range.end)
        assertEquals(0, intent.clips.first().range.start)
        assertEquals(base.takes, intent.takes)
    }

    @Test fun failuresRefuseTheWholePlacementBeforeAnyDocumentMutation() {
        val base = project()
        assertEquals(PatternProblem.EMPTY_SEQUENCE, assertFailsWith<PatternEditException> { PatternPlacement.plan(base, emptyList(), 0) }.problem)
        assertEquals(PatternProblem.NO_NOTES, assertFailsWith<PatternEditException> { PatternPlacement.plan(base, listOf(SongSection("pattern-1")), 0) }.problem)
        val dense = base.copy(patterns = frozenListOf(Pattern("p", notes = (0..15).map { Note(it * 240, 0) }.frozen())))
        assertEquals(PatternProblem.SONG_FULL, assertFailsWith<PatternEditException> { PatternPlacement.plan(dense, listOf(SongSection("p", 65)), 0) }.problem)
        val overlap = base.copy(pads = base.pads.map { if (it.id < 33) it.copy(assetHash = base.assets[0].hash, range = FrameRange(0, 4800)) else it }.frozen(),
            patterns = frozenListOf(Pattern("p", notes = (0..32).map { Note(0, it) }.frozen())))
        val plan = PatternPlacement.plan(overlap, listOf(SongSection("p")), 0)
        var id = 0
        assertEquals(PatternProblem.RENDER_FAILED, assertFailsWith<PatternEditException> { PatternPlacement.intent(overlap, plan, emptyMap(), { "c-${++id}" }, "P") }.problem)
        assertEquals(PatternProblem.SONG_FULL, assertFailsWith<PatternEditException> {
            PatternPlacement.intent(overlap, plan, plan.renders.associateWith { rendered(it, overlap) }, { "c-${++id}" }, "P")
        }.problem)
        assertTrue(base.clips.isEmpty()); assertEquals(2, base.assets.size)
    }

    private fun project(tempo: Tempo = Tempo()): Project {
        val assets = (0..1).map { Asset((it + 1).toString().padStart(64, '0'), "wav", 38444, 48_000, 2, 4800, "sound$it") }.frozen()
        return Project(assets = assets, pads = (0..127).map { id -> if (id < 2) Pad(id, assets[id].hash, FrameRange(0, 4800), releaseFrames = 96) else Pad(id) }.frozen(), tempo = tempo)
    }
    private fun rendered(request: PatternVoiceRender, project: Project): Asset = Asset("f".repeat(56) + request.hashCode().toUInt().toString(16).padStart(8, '0'), "wav",
        request.limitFrames * 8L + 44, 48_000, 2, request.limitFrames.toLong(), "render${request.padId}", AssetRole.RENDERED,
        derivedFrom = project.pads[request.padId].assetHash)
}
