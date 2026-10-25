package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.persistence.ProjectJson
import com.choplab.engine.Tempo
import kotlin.test.*

class CroppedStretchEditTest {
    @Test fun aNativeUnityCropWithNoCompleteNormalizedFrameIsAVisibleInvalidInput() {
        val source = Asset("a".repeat(64), "wav", 160_044, 96_000, 2, 20_000, "Original")
        val before = Project(assets = frozenListOf(source), pads = (0..127).map {
            if (it == 0) Pad(0, source.hash, FrameRange(0, source.frames)) else Pad(it)
        }.frozen())
        val target = StretchTarget(StretchKind.PAD, "0")
        val draft = BeatStretchEdits.draft(DocumentState(before, 0), target, before.tempo.milliBpm)
        val unity = Reducer.reduce(before, BeatStretchEdits.apply(DocumentState(before, 0), draft, source)).project
        val trimmed = unity.copy(pads = unity.pads.map { if (it.id == 0) it.copy(range = FrameRange(1, 2)) else it }.frozen())
        assertEquals(StretchProblem.INVALID_INPUT, assertFailsWith<StretchException> {
            BeatStretchEdits.draft(DocumentState(trimmed, 1), target, before.tempo.milliBpm)
        }.problem)
    }

    @Test fun repeatedLengthChangesAndUnityPreserveExactFractionsFullContextPlacementAndUndoAcrossRates() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000)) {
            val source = Asset("a".repeat(64), "wav", 160_044, rate, 2, 20_000, "Original")
            val initial = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("track", "Beat", TrackKind.BANK)),
                clips = frozenListOf(Clip("clip", "track", source.hash, FrameRange(13, 10_013), 960, null, .7f, -.2f),
                    Clip("other", "track", source.hash, FrameRange(10_013, 20_000), 12_000)), tempo = Tempo(137_000))
            val target = StretchTarget(StretchKind.CLIP, "clip")
            val first = BeatStretchEdits.draft(DocumentState(initial, 0), target, 103_000)
            val firstAsset = render(first, initial, "b")
            val full = Reducer.reduce(initial, BeatStretchEdits.apply(DocumentState(initial, 0), first, firstAsset)).project
            for (crop in listOf(FrameRange(0, 1), FrameRange(17, firstAsset.frames - 31), FrameRange(firstAsset.frames - 1, firstAsset.frames))) {
                val trimmed = full.copy(clips = full.clips.map { if (it.id == "clip") it.copy(range = crop) else it }.frozen())
                val session = EditSession(trimmed, 1)
                fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
                val reference = StretchCrop(crop, firstAsset.frames)
                var expected = trimmed
                for ((index, tempo) in listOf(239_000, 40_000, 103_000, 137_000, 239_000, 137_000).withIndex()) {
                    commit(session.plan(Intent.SetTempo(Tempo(tempo))))
                    val before = session.project
                    val document = DocumentState(before, session.revision)
                    val draft = BeatStretchEdits.draft(document, target, 103_000)
                    assertEquals(first.sourceRange, draft.sourceRange)
                    assertEquals(source.hash, draft.sourceAssetHash)
                    assertEquals(reference, draft.crop, "Reference fraction must not be reconstructed from rounded output")
                    val rendered = render(draft, before, (index + 1).toString())
                    val intent = BeatStretchEdits.apply(document, draft, rendered)
                    val count = session.undoCount
                    commit(session.plan(intent))
                    expected = session.project
                    val clip = expected.clips[0]
                    assertEquals(reference.at(rendered.frames), clip.range)
                    assertEquals(trimmed.clips[0].copy(assetHash = rendered.hash, range = clip.range), clip)
                    assertEquals(trimmed.clips[1], expected.clips[1])
                    assertEquals(source, expected.asset(source.hash))
                    assertEquals(count + 1, session.undoCount)
                    commit(session.planUndo()!!); assertEquals(before, session.project)
                    commit(session.planRedo()!!); assertEquals(expected, session.project)
                    assertEquals(expected, ProjectJson.decode(ProjectJson.encode(expected)))
                    if (tempo == 137_000) assertEquals(crop, clip.range, "Returning to a length must restore both endpoints exactly")
                }
                assertEquals(reference, expected.beatStretches.single().crop)
            }
        }
    }

    @Test fun freshTrimRecordsANewIntervalButCannotForgeDifferentSourceContextOrFractions() {
        val source = Asset("a".repeat(64), "wav", 160_044, 44_100, 2, 20_000, "Original")
        val before = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("track", "Beat", TrackKind.BANK)),
            clips = frozenListOf(Clip("clip", "track", source.hash, FrameRange(13, 10_013))), tempo = Tempo(137_000))
        val target = StretchTarget(StretchKind.CLIP, "clip")
        val first = BeatStretchEdits.draft(DocumentState(before, 0), target, 103_000)
        val full = Reducer.reduce(before, BeatStretchEdits.apply(DocumentState(before, 0), first, render(first, before, "b"))).project
        val trimmed = full.copy(clips = frozenListOf(full.clips[0].copy(range = FrameRange(1, 100))))
        val draft = BeatStretchEdits.draft(DocumentState(trimmed, 1), target, 103_000)
        assertFailsWith<IllegalArgumentException> { BeatStretchEdits.validate(trimmed, draft.copy(crop = StretchCrop(FrameRange(2, 101), draft.crop!!.referenceFrames))) }
        assertFailsWith<IllegalArgumentException> { BeatStretchEdits.validate(trimmed, draft.copy(sourceRange = FrameRange(14, 10_014))) }
        assertFailsWith<IllegalArgumentException> { BeatStretchEdits.validate(trimmed, draft.copy(sourceAssetHash = trimmed.clips[0].assetHash)) }
        assertEquals(StretchProblem.STALE, assertFailsWith<StretchException> {
            BeatStretchEdits.apply(DocumentState(trimmed, 2), draft, render(draft, trimmed, "c"))
        }.problem)
        assertFailsWith<IllegalArgumentException> { StretchCrop(FrameRange(0, 2), 1) }
        assertFailsWith<IllegalArgumentException> { StretchCrop(FrameRange(0, 1), Long.MAX_VALUE) }
    }

    private fun render(draft: StretchDraft, project: Project, hash: String): Asset {
        val frames = stretchFrames(project.asset(draft.sourceAssetHash), draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm)
        return Asset(hash.repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Rendered", AssetRole.RENDERED, derivedFrom = draft.sourceAssetHash)
    }
}
