package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlin.test.*

class BeatStretchEditTest {
    @Test fun padAndClipApplyOneUndoAndReprocessingAlwaysUsesTheOriginalAfterTempoChanges() {
        for (target in listOf(StretchTarget(StretchKind.PAD, "0"), StretchTarget(StretchKind.CLIP, "clip"))) {
            val before = project(); val session = EditSession(before, 3)
            fun doc() = DocumentState(session.project, session.revision)
            fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
            val draft = BeatStretchEdits.draft(doc(), target, 120_000)
            val asset = rendered(draft, before)
            val intent = BeatStretchEdits.apply(doc(), draft, asset)
            val cancelled = session.plan(intent); session.cancel(cancelled)
            assertEquals(0, session.undoCount); assertEquals(before, session.project)
            commit(session.plan(intent)); assertEquals(1, session.undoCount)
            val after = session.project
            assertEquals(before.source, after.source); assertEquals(before.assets[0], after.assets[0]); assertEquals(before.takes, after.takes)
            if (target.kind == StretchKind.PAD) {
                assertEquals(before.pads[0].copy(assetHash = asset.hash, range = FrameRange(0, asset.frames)), after.pads[0])
                assertEquals(before.clips, after.clips)
            } else {
                assertEquals(before.clips[0].copy(assetHash = asset.hash, range = FrameRange(0, asset.frames)), after.clips[0])
                assertEquals(before.pads, after.pads)
            }
            commit(session.planUndo()!!); assertEquals(before, session.project)
            commit(session.planRedo()!!); assertEquals(after, session.project)
            commit(session.plan(Intent.SetTempo(Tempo(90_000))))
            assertEquals(after.beatStretches, session.project.beatStretches)
            val again = BeatStretchEdits.draft(doc(), target, 120_000)
            assertEquals(draft.sourceAssetHash, again.sourceAssetHash); assertEquals(draft.sourceRange, again.sourceRange)
            assertEquals(90_000, again.targetMilliBpm)
        }
    }
    @Test fun bypassRetainsOriginalBytesMetadataAndNativeRangeWhileRecordingTheExplicitBpm() {
        val before = project().copy(tempo = Tempo(120_000))
        val draft = BeatStretchEdits.draft(DocumentState(before, 0), StretchTarget(StretchKind.PAD, "0"), 120_000)
        val after = Reducer.reduce(before, BeatStretchEdits.apply(DocumentState(before, 0), draft, before.assets[0])).project
        assertEquals(before.assets, after.assets); assertEquals(before.pads, after.pads)
        assertEquals(before.pads[0].range, after.beatStretches.single().renderedRange)
    }
    @Test fun staleChangedTempoChangedTargetBadProvenanceAndOversizeCannotBecomeAnEdit() {
        val before = project(); val doc = DocumentState(before, 4)
        val draft = BeatStretchEdits.draft(doc, StretchTarget(StretchKind.CLIP, "clip"), 120_000)
        val rendered = rendered(draft, before)
        assertEquals(StretchProblem.STALE, assertFailsWith<StretchException> { BeatStretchEdits.apply(doc.copy(revision = 5), draft, rendered) }.problem)
        assertFailsWith<StretchException> { BeatStretchEdits.apply(doc.copy(project = before.copy(tempo = Tempo(121_000))), draft, rendered) }
        val moved = before.copy(clips = frozenListOf(before.clips[0].copy(timelineStartFrame = 88)))
        assertFailsWith<StretchException> { BeatStretchEdits.apply(doc.copy(project = moved), draft, rendered) }
        assertFailsWith<IllegalArgumentException> { BeatStretchEdits.apply(doc, draft, rendered.copy(derivedFrom = null)) }
        assertFailsWith<IllegalArgumentException> { BeatStretchEdits.apply(doc, draft, rendered.copy(frames = rendered.frames - 1)) }
        val long = before.assets[0].copy(frames = ProjectLimits.MAX_FRAMES, sampleRate = 48_000)
        val oversized = before.copy(assets = frozenListOf(long), pads = before.pads.map { if (it.id == 0) it.copy(range = FrameRange(0, long.frames)) else it }.frozen(), tempo = Tempo(40_000))
        assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { BeatStretchEdits.draft(DocumentState(oversized, 0), StretchTarget(StretchKind.PAD, "0"), 240_000) }.problem)
        assertEquals(before, doc.project)
        assertEquals(2, stretchFirstFrame(before.assets[0], draft.sourceRange))
        assertEquals(10_882, stretchInputFrames(before.assets[0], draft.sourceRange))
    }
    @Test fun fullProjectRejectsTheEntireProposalWithTypedLimit() {
        val before = project()
        val full = before.copy(assets = (before.assets + (1 until ProjectLimits.MAX_ASSETS).map {
            before.assets[0].copy(hash = it.toString(16).padStart(64, '0'), byteCount = 100)
        }).frozen())
        val document = DocumentState(full, 0)
        val draft = BeatStretchEdits.draft(document, StretchTarget(StretchKind.PAD, "0"), 120_000)
        assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { BeatStretchEdits.apply(document, draft, rendered(draft, full)) }.problem)
        assertEquals(full, document.project)
    }
    private fun project(): Project {
        val source = Asset("a".repeat(64), "wav", 160_044, 44_100, 2, 20_000, "Original")
        val range = FrameRange(1, 10_000)
        return Project(assets = frozenListOf(source), source = Source(source.hash, FrameRange(0, source.frames)),
            pads = (0..127).map { if (it == 0) Pad(0, source.hash, range, gain = .7f, pan = -.3f) else Pad(it) }.frozen(),
            tracks = frozenListOf(Track("track", "Music", TrackKind.BANK)),
            clips = frozenListOf(Clip("clip", "track", source.hash, range, timelineStartFrame = 1234, gain = .7f, pan = -.3f)), tempo = Tempo(150_000))
    }
    private fun rendered(draft: StretchDraft, project: Project): Asset {
        val frames = stretchFrames(project.asset(draft.sourceAssetHash), draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm)
        return Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Stretched", AssetRole.RENDERED, derivedFrom = draft.sourceAssetHash)
    }
}
