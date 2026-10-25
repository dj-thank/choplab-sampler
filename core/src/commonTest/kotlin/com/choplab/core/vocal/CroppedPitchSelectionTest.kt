package com.choplab.core.vocal

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings
import kotlin.test.*

class CroppedPitchSelectionTest {
    @Test fun exact48kCropsRoundTripWithPlacementHistoryAndOneUndoPerSwitch() {
        for (crop in listOf(FrameRange(0, 1), FrameRange(7, 9_980), FrameRange(9_998, 9_999))) {
            val before = corrected(48_000, crop)
            val session = EditSession(before, 2)
            fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
            fun document() = DocumentState(session.project, session.revision)
            commit(session.plan(VocalPitchEdits.select(document(), "pitch", true)))
            val original = session.project
            assertEquals(before.clips[0].copy(assetHash = "a".repeat(64),
                range = FrameRange(crop.start + 13, crop.end + 13)), original.clips[0])
            assertEquals(before.clips[1], original.clips[1])
            assertEquals(before.assets, original.assets)
            assertEquals(before.pitchCorrections, original.pitchCorrections)
            assertEquals(1, session.undoCount)
            commit(session.planUndo()!!); assertEquals(before, session.project)
            commit(session.planRedo()!!); assertEquals(original, session.project)
            commit(session.plan(VocalPitchEdits.select(document(), "pitch", false)))
            assertEquals(before, session.project)
            assertEquals(2, session.undoCount)
        }
    }

    @Test fun reeditingAnOriginalCropRetainsTheWholeProcessingContextAndOutputCrop() {
        val crop = FrameRange(7, 9_980)
        val corrected = corrected(48_000, crop)
        val original = Reducer.reduce(corrected,
            VocalPitchEdits.select(DocumentState(corrected, 2), "pitch", true)).project
        val document = DocumentState(original, 3)
        val draft = VocalPitchEdits.draft(document, "voice", "unused", PitchCorrectionSettings(amount = .5f))
        assertEquals(FrameRange(13, 10_012), draft.sourceRange)
        assertEquals(crop, draft.auditionRange(original))
        assertEquals("a".repeat(64), draft.sourceAssetHash)
        val replacement = original.asset("b".repeat(64)).copy(hash = "c".repeat(64))
        val edited = Reducer.reduce(original, VocalPitchEdits.apply(document, draft, replacement)).project
        assertEquals(corrected.clips[0].copy(assetHash = replacement.hash), edited.clips[0])
        assertEquals(corrected.clips[1], edited.clips[1])
        assertEquals(corrected.pitchCorrections.single().sourceRange, edited.pitchCorrections.single().sourceRange)
    }

    @Test fun otherSampleRatesAndReplacedSourcesCannotBypassTheSelectionGuard() {
        for (rate in listOf(8_000, 44_100, 96_000)) {
            val before = corrected(rate, FrameRange(1, 100))
            for (original in listOf(true)) {
                assertEquals(VocalPitchProblem.TRIMMED_SELECTION, assertFailsWith<VocalPitchException> {
                    VocalPitchEdits.select(DocumentState(before, 2), "pitch", original)
                }.problem)
                assertFailsWith<IllegalArgumentException> {
                    Reducer.reduce(before, Intent.SelectVocalPitch("pitch", before.clips[0], original))
                }
            }
            assertEquals(before, Reducer.reduce(before, VocalPitchEdits.select(DocumentState(before, 2), "pitch", false)).project)
        }
        val before = corrected(48_000, FrameRange(7, 9_980))
        val changed = before.copy(clips = before.clips.map { if (it.id == "voice")
            it.copy(assetHash = "a".repeat(64), range = FrameRange(0, 10)) else it }.frozen())
        assertFailsWith<IllegalArgumentException> {
            Reducer.reduce(changed, Intent.SelectVocalPitch("pitch", changed.clips[0], false))
        }
        assertFailsWith<IllegalArgumentException> {
            Reducer.reduce(before, Intent.SelectVocalPitch("pitch", before.clips[0].copy(timelineStartFrame = 0), true))
        }
    }

    @Test fun normalizedOriginalSwitchesEveryNativeRateWithoutRoundingCropAndSurvivesReeditUndoAndOneFrameEdges() {
        for (rate in listOf(8_000, 44_100, 96_000)) {
            val full = corrected(rate, FrameRange(1, 100))
            val correction = full.pitchCorrections.single()
            val rendered = full.asset(correction.renderedAssetHash)
            val normalized = rendered.copy(hash = "d".repeat(64), name = "Normalized original")
            for (crop in listOf(FrameRange(0, 1), FrameRange(1, rendered.frames - 1), FrameRange(rendered.frames - 1, rendered.frames))) {
                val before = full.copy(clips = full.clips.map { if (it.id == "voice") it.copy(range = crop) else it }.frozen())
                val session = EditSession(before, 2)
                fun commit(plan: EditPlan) { plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan) }
                commit(session.plan(VocalPitchEdits.select(DocumentState(before, 2), "pitch", true, normalized)))
                val original = session.project
                assertEquals(before.clips[0].copy(assetHash = normalized.hash), original.clips[0])
                assertEquals(before.clips[1], original.clips[1])
                assertEquals(before.asset(correction.sourceAssetHash), original.asset(correction.sourceAssetHash))
                assertEquals(normalized.hash, original.pitchCorrections.single().normalizedOriginalHash)
                assertEquals(1, session.undoCount)
                commit(session.planUndo()!!); assertEquals(before, session.project)
                commit(session.planRedo()!!); assertEquals(original, session.project)
                val draft = VocalPitchEdits.draft(DocumentState(original, session.revision), "voice", "unused", correction.settings.copy(amount = .5f))
                assertEquals(correction.sourceAssetHash, draft.sourceAssetHash)
                assertEquals(correction.sourceRange, draft.sourceRange)
                assertEquals(crop, draft.auditionRange(original))
                val reapplied = Reducer.reduce(original, VocalPitchEdits.apply(DocumentState(original, session.revision), draft, rendered.copy(hash = "e".repeat(64)))).project
                assertEquals(normalized.hash, reapplied.pitchCorrections.single().normalizedOriginalHash)
                assertEquals(crop, reapplied.clips[0].range)
                commit(session.plan(VocalPitchEdits.select(DocumentState(original, session.revision), "pitch", false)))
                assertEquals(before.clips, session.project.clips)
                assertEquals(2, session.undoCount)
            }
        }
    }

    @Test fun wrongNormalizedContextAndCapacityAreRefusedBeforeDocumentMutation() {
        val before = corrected(44_100, FrameRange(1, 100))
        val normalized = before.asset("b".repeat(64)).copy(hash = "d".repeat(64))
        for (invalid in listOf(normalized.copy(frames = normalized.frames - 1), normalized.copy(sampleRate = 44_100),
            normalized.copy(derivedFrom = "c".repeat(64)), normalized.copy(role = AssetRole.PCM_CACHE))) {
            assertFailsWith<IllegalArgumentException> {
                Reducer.reduce(before, Intent.SelectVocalPitch("pitch", before.clips[0], true, invalid))
            }
        }
        val full = before.copy(assets = (before.assets + (2 until ProjectLimits.MAX_ASSETS).map {
            before.assets[0].copy(hash = it.toString(16).padStart(64, '0'), byteCount = 100)
        }).frozen())
        assertEquals(VocalPitchProblem.LIMIT, assertFailsWith<VocalPitchException> {
            VocalPitchEdits.select(DocumentState(full, 2), "pitch", true, normalized)
        }.problem)
    }

    private fun corrected(rate: Int, crop: FrameRange): Project {
        val source = Asset("a".repeat(64), "wav", 160_044, rate, 2, 20_000, "Original")
        val before = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("track", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("voice", "track", source.hash, FrameRange(13, 10_012),
                timelineStartFrame = 1234, gain = .7f, pan = -.3f),
                Clip("other", "track", source.hash, FrameRange(10_012, 20_000), timelineStartFrame = 20_000)))
        val document = DocumentState(before, 0)
        val draft = VocalPitchEdits.draft(document, "voice", "pitch", PitchCorrectionSettings())
        val frames = draft.frames48(source)
        val rendered = Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Corrected",
            AssetRole.RENDERED, derivedFrom = source.hash)
        val corrected = Reducer.reduce(before, VocalPitchEdits.apply(document, draft, rendered)).project
        return corrected.copy(clips = corrected.clips.map { if (it.id == "voice") it.copy(range = crop) else it }.frozen())
    }
}
