package com.choplab.core.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings
import kotlin.test.*

class VocalPitchEditTest {
    @Test fun draftCancelApplyAndABUseOneUndoEachWithoutChangingTheOriginalOrOtherClips() {
        val before = project()
        val session = EditSession(before, 17)
        fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
        fun document() = DocumentState(session.project, session.revision)
        val draft = VocalPitchEdits.draft(document(), "voice-clip", "pitch", PitchCorrectionSettings())
        val rendered = rendered(draft, before)
        val intent = VocalPitchEdits.apply(document(), draft, rendered)
        val canceled = session.plan(intent)
        assertEquals(before, session.project); assertEquals(0, session.undoCount)
        session.cancel(canceled)
        assertEquals(before, session.project); assertEquals(17L, session.revision)
        val plan = session.plan(intent)
        assertEquals(listOf<Effect>(Effect.PublishProject), plan.effects.toList(), "Voice correction must not stop unrelated live PADs")
        commit(plan)
        val corrected = session.project
        assertEquals(1, session.undoCount)
        assertEquals(before.clips[1], corrected.clips[1]); assertEquals(before.pads, corrected.pads)
        assertEquals(before.takes, corrected.takes)
        assertEquals(before.assets.single(), corrected.asset(before.assets.single().hash))
        assertEquals(before.clips[0].copy(assetHash = rendered.hash, range = FrameRange(0, rendered.frames)), corrected.clips[0])
        commit(session.planUndo()!!); assertEquals(before, session.project)
        commit(session.planRedo()!!); assertEquals(corrected, session.project)
        commit(session.plan(VocalPitchEdits.select(document(), "pitch", true)))
        assertEquals(before.clips, session.project.clips); assertEquals(2, session.undoCount)
        assertTrue(session.project.assets.contains(rendered), "Original selection must retain the corrected A/B asset")
        commit(session.plan(VocalPitchEdits.select(document(), "pitch", false)))
        assertEquals(corrected, session.project); assertEquals(3, session.undoCount)
        val next = VocalPitchEdits.draft(document(), "voice-clip", "ignored", PitchCorrectionSettings(amount = .5f))
        assertEquals(draft.sourceAssetHash, next.sourceAssetHash); assertEquals(draft.sourceRange, next.sourceRange)
        assertEquals("pitch", next.id, "Re-edit from the original, not the corrected PCM")
        val deleted = Reducer.reduce(corrected, Intent.SetArrangement(corrected.tracks, frozenListOf(), corrected.takes)).project
        assertEquals(corrected.pitchCorrections, deleted.pitchCorrections)
        assertTrue(deleted.assets.contains(rendered))
    }

    @Test fun staleRevisionPlacementInvalidRenderAndNonVoiceAreRejectedWithoutAnEdit() {
        val before = project()
        val doc = DocumentState(before, 9)
        val draft = VocalPitchEdits.draft(doc, "voice-clip", "pitch", PitchCorrectionSettings())
        val rendered = rendered(draft, before)
        assertEquals(VocalPitchProblem.STALE, assertFailsWith<VocalPitchException> {
            VocalPitchEdits.apply(doc.copy(revision = 10), draft, rendered)
        }.problem)
        val moved = before.copy(clips = before.clips.map { if (it.id == draft.expectedClip.id) it.copy(timelineStartFrame = 999) else it }.frozen())
        assertFailsWith<VocalPitchException> { VocalPitchEdits.apply(doc.copy(project = moved), draft, rendered) }
        assertFailsWith<IllegalArgumentException> { VocalPitchEdits.apply(doc, draft, rendered.copy(derivedFrom = null)) }
        assertFailsWith<IllegalArgumentException> { VocalPitchEdits.apply(doc, draft, rendered.copy(frames = rendered.frames - 1)) }
        val corrected = Reducer.reduce(before, VocalPitchEdits.apply(doc, draft, rendered)).project
        val trimmed = corrected.copy(clips = corrected.clips.map { if (it.id == draft.expectedClip.id) it.copy(range = FrameRange(1, rendered.frames)) else it }.frozen())
        assertEquals(VocalPitchProblem.TRIMMED_SELECTION, assertFailsWith<VocalPitchException> {
            VocalPitchEdits.select(doc.copy(project = trimmed), "pitch", true)
        }.problem)
        val music = before.copy(tracks = frozenListOf(before.tracks.single().copy(kind = TrackKind.BANK)))
        assertEquals(VocalPitchProblem.NO_VOICE_CLIP, assertFailsWith<VocalPitchException> {
            VocalPitchEdits.draft(doc.copy(project = music), "voice-clip", "pitch", draft.settings)
        }.problem)
        assertEquals(before, doc.project)
    }

    @Test fun croppedCorrectionRerendersTheFullOriginalContextAndKeepsExactOutputCropAndPlacementWithOneUndo() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000)) {
            val before = project().let { it.copy(assets = frozenListOf(it.assets.single().copy(sampleRate = rate))) }
            val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", PitchCorrectionSettings())
            val first = rendered(draft, before)
            val corrected = Reducer.reduce(before, VocalPitchEdits.apply(DocumentState(before, 0), draft, first)).project
            for (crop in listOf(FrameRange(1, first.frames - 3), FrameRange(first.frames - 1, first.frames))) {
                val trimmed = corrected.copy(clips = corrected.clips.map { if (it.id == "voice-clip") it.copy(range = crop) else it }.frozen())
                val doc = DocumentState(trimmed, 2)
                val again = VocalPitchEdits.draft(doc, "voice-clip", "ignored", draft.settings.copy(amount = .5f))
                assertEquals(draft.sourceAssetHash, again.sourceAssetHash)
                assertEquals(draft.sourceRange, again.sourceRange, "Retain all original DSP context at $rate Hz")
                assertEquals(crop, again.auditionRange(trimmed))
                val next = first.copy(hash = "c".repeat(64))
                val session = EditSession(trimmed, 2)
                fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
                commit(session.plan(VocalPitchEdits.apply(doc, again, next)))
                assertEquals(1, session.undoCount)
                assertEquals(trimmed.clips[0].copy(assetHash = next.hash), session.project.clips[0])
                assertEquals(trimmed.clips[1], session.project.clips[1])
                assertEquals(draft.sourceRange, session.project.pitchCorrections.single().sourceRange)
                val edited = session.project
                commit(session.planUndo()!!); assertEquals(trimmed, session.project)
                commit(session.planRedo()!!); assertEquals(edited, session.project)
                assertFailsWith<IllegalArgumentException> {
                    VocalPitchEdits.apply(doc, again, next.copy(frames = first.frames - 1))
                }
            }
        }
    }

    @Test fun normalizedRangeUsesTheSameCeilOnBothEdgesAsPlaybackAndNeverChangesDuration() {
        val before = project()
        val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", PitchCorrectionSettings())
        val source = before.assets.single()
        assertEquals(ProgramCompiler.sourceFrameTo48k(draft.sourceRange.start, source.sampleRate), draft.firstFrame48(source))
        assertEquals(ProgramCompiler.sourceFrameTo48k(draft.sourceRange.end, source.sampleRate) - draft.firstFrame48(source), draft.frames48(source))
        assertEquals(2L, draft.firstFrame48(source))
        assertEquals(10_883L, draft.frames48(source))
    }

    private fun rendered(draft: VocalPitchDraft, project: Project): Asset {
        val frames = draft.frames48(project.asset(draft.sourceAssetHash))
        return Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Corrected", AssetRole.RENDERED, derivedFrom = draft.sourceAssetHash)
    }
    private fun project(): Project {
        val asset = Asset("a".repeat(64), "wav", 40_044, 44_100, 1, 20_000, "Original")
        return Project(assets = frozenListOf(asset), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("voice-clip", "voice", asset.hash, FrameRange(1, 10_000), timelineStartFrame = 1234, gain = .7f, pan = -.3f),
                Clip("other", "voice", asset.hash, FrameRange(10_000, 20_000), timelineStartFrame = 20_000)),
            takes = frozenListOf(Take("take", "voice", asset.hash, FrameRange(0, asset.frames), 0)))
    }
}
