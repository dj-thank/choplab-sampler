package com.choplab.core.vocal

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlin.test.*

class VocalCompTest {
    @Test fun retainsAllCandidatesAndACompAsOneUndoWithoutReplacingUnrelatedPlacements() {
        val before = project()
        val draft = draft()
        val rendered = Asset("c".repeat(64), "wav", 44 + 2400 * 8, 48_000, 2, 2400, "Comp", AssetRole.RENDERED)
        val session = EditSession(before)
        fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
        commit(session.plan(VocalCompEdits.apply(before, draft, rendered, before.tracks.single(), "comp-clip", setOf("old-a"))))
        val saved = session.project
        assertEquals(before.takes, saved.takes)
        assertEquals(listOf("unrelated", "comp-clip"), saved.clips.map { it.id })
        assertEquals(draft.segments, saved.vocalComps.single().segments)
        assertEquals(1, session.undoCount)
        commit(session.planUndo()!!); assertEquals(before, session.project)
        commit(session.planRedo()!!); assertEquals(saved, session.project)
        val deleted = Reducer.reduce(saved, Intent.SetArrangement(saved.tracks, frozenListOf(), saved.takes)).project
        assertEquals(saved.vocalComps, deleted.vocalComps)
        assertTrue(deleted.assets.any { it.hash == rendered.hash }, "Deleting a placement retains the saved comp recipe and render")
        assertEquals(saved.vocalComps, Reducer.reduce(saved, Intent.SetLyrics(frozenListOf())).project.vocalComps)
        assertFailsWith<IllegalArgumentException> { VocalCompEdits.apply(before, draft, rendered, before.tracks.single(), "comp", setOf("unrelated")) }
    }

    @Test fun candidateOnlySaveDoesNotMakeEveryTakeAudibleAndKeepsTheWholeOriginal() {
        val project = project()
        val asset = project.assets.first()
        val candidate = Take("extra", "voice", asset.hash, FrameRange(31, asset.frames), 200)
        val intent = VocalCompEdits.retain(project, asset, candidate)
        val after = Reducer.reduce(project, intent).project
        assertEquals(project.clips, after.clips)
        assertEquals(project.takes + candidate, after.takes)
        assertEquals(project.assets, after.assets)
        val (preview, target) = VocalCompEdits.audition(after, candidate.id)
        assertEquals(listOf(candidate.id), target.takeIds)
        assertTrue(preview.clips.isEmpty())
        assertEquals(after.vocalComps, preview.vocalComps)
    }

    @Test fun tenMillisecondCrossfadeKeepsTheExactPeriodChannelsAndCorrelatedDc() {
        val plan = VocalCompMix.plan(project(), draft())
        val join = plan.spans.single { it.from?.id == "a" && it.to?.id == "b" }
        assertEquals(1560L, join.start); assertEquals(2040L, join.end)
        for (frame in join.start until join.end) {
            assertEquals(1.4f, join.mix(1.4f, 1.4f, frame))
            assertEquals(-.3f, join.mix(-.3f, -.3f, frame))
        }
        assertEquals(2400, plan.spans.sumOf { it.end - it.start })
        assertEquals(.2f, join.mix(.2f, .8f, join.start))
        assertEquals(.8f, join.mix(.2f, .8f, join.end - 1), .000001f)
        assertFailsWith<IllegalArgumentException> { join.mix(0f, 1f, join.end) }
    }

    @Test fun shortLinesMissingOverlapHandlesGapsAndOneFrameChoicesKeepTheirTimeline() {
        val project = project()
        val short = VocalCompDraft("short", frozenListOf(VocalCompSegment("s1", "a", 1000, 1003),
            VocalCompSegment("s2", "b", 1003, 1007), VocalCompSegment("s3", "a", 1010, 1011)))
        val plan = VocalCompMix.plan(project, short)
        assertEquals(11, plan.spans.sumOf { it.end - it.start })
        assertEquals(3, plan.spans.single { it.from == null && it.to == null }.end - 1007)
        plan.spans.forEach { span -> for (frame in span.start until span.end) assertTrue(span.mix(1f, -.25f, frame).isFinite()) }
        val edgeProject = project.copy(takes = frozenListOf(project.takes[0].copy(range = FrameRange(0, 1800)),
            project.takes[1].copy(range = FrameRange(0, 2400), timelineStartFrame = 1800)))
        val edgeDraft = VocalCompDraft("edge", frozenListOf(VocalCompSegment("e1", "a", 1000, 1800), VocalCompSegment("e2", "b", 1800, 2400)))
        val edge = VocalCompMix.plan(edgeProject, edgeDraft)
        assertTrue(edge.spans.none { it.from != null && it.to != null && it.from != it.to })
        assertEquals(1400, edge.spans.sumOf { it.end - it.start })
        assertFailsWith<IllegalArgumentException> { VocalCompEdits.choose(project, short, "s1", "unknown") }
    }

    @Test fun mixedRateBoundariesUseTheCompilerCeilAndLyricTicksIgnoreSwing() {
        val source = Asset("d".repeat(64), "wav", 48000, 44_100, 1, 10000, "44k")
        val take = Take("mixed", "voice", source.hash, FrameRange(1, 10000), 500, 100)
        val p = project().copy(assets = frozenListOf(source), clips = frozenListOf(), takes = frozenListOf(take))
        assertEquals(400, take.correctedStartFrame())
        assertEquals(400 + 10885 - 2, take.correctedEndFrame(source))
        assertEquals(400, VocalCompEdits.fullTake(p, take.id, "full").startFrame)
        val normal = project().copy(lyrics = frozenListOf(LyricLine("line", "Words", 20, 40)), tempo = Tempo(97_125, 500))
        assertEquals(VocalCompEdits.lines(normal, "a", "lyrics"), VocalCompEdits.lines(normal.copy(tempo = Tempo(97_125, 750)), "a", "lyrics"))
    }

    private fun draft() = VocalCompDraft("comp", frozenListOf(VocalCompSegment("one", "a", 600, 1800, "line-one"), VocalCompSegment("two", "b", 1800, 3000, "line-two")))
    private fun project(): Project {
        val a = Asset("a".repeat(64), "wav", 32044, 48_000, 2, 4000, "A")
        val b = a.copy(hash = "b".repeat(64), name = "B")
        val other = a.copy(hash = "d".repeat(64), name = "Other")
        return Project(assets = frozenListOf(a, b, other), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            takes = frozenListOf(Take("a", "voice", a.hash, FrameRange(0, 4000), 0), Take("b", "voice", b.hash, FrameRange(0, 4000), 0)),
            clips = frozenListOf(Clip("old-a", "voice", a.hash, FrameRange(0, 4000)), Clip("unrelated", "voice", other.hash, FrameRange(0, 4000))))
    }
}
