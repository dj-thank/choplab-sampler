package com.choplab.ui

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.VocalPitchEdits
import com.choplab.engine.PitchCorrectionSettings
import com.choplab.engine.Tempo
import kotlin.test.*

class CroppedPitchEditsTest {
    @Test fun splittingAStretchedClipKeepsBothRecipesAndCannotProcessTheRenderedRightHalfAgain() {
        val source = Asset("a".repeat(64), "wav", 352_844, 44_100, 2, 44_100, "Original")
        val original = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("track", "Beat", TrackKind.BANK)),
            clips = frozenListOf(Clip("clip", "track", source.hash, FrameRange(13, 40_007))), tempo = Tempo(100_000))
        val draft = BeatStretchEdits.draft(DocumentState(original, 0), StretchTarget(StretchKind.CLIP, "clip"), 120_000)
        val frames = stretchFrames(source, draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm)
        val rendered = Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Stretched",
            AssetRole.RENDERED, derivedFrom = source.hash)
        val stretched = Reducer.reduce(original, BeatStretchEdits.apply(DocumentState(original, 0), draft, rendered)).project
        val split = Reducer.reduce(stretched, ContinuousClipEdits.intent(stretched,
            ContinuousEditorAction.SplitClip("clip", 13_123), { "right" }, grid = ContinuousGrid.FREE)).project
        assertEquals(2, split.beatStretches.size)
        for (clip in split.clips) {
            val target = StretchTarget(StretchKind.CLIP, clip.id)
            assertEquals(source.hash, split.beatStretches.single { it.target == target }.sourceAssetHash)
            assertEquals(StretchProblem.TRIMMED, assertFailsWith<StretchException> {
                BeatStretchEdits.draft(DocumentState(split, 2), target, 120_000)
            }.problem)
        }
        assertEquals(split.clips[0].range.end, split.clips[1].range.start)
    }

    @Test fun duplicateRepeatAndSplitKeepOriginalRecipeContextAndReeditOnlyTheChosenCrop() {
        val source = Asset("a".repeat(64), "wav", 352_844, 44_100, 2, 44_100, "Original")
        val original = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", source.hash, FrameRange(13, 40_007), timelineStartFrame = 1234)))
        val draft = VocalPitchEdits.draft(DocumentState(original, 0), "clip", "pitch", PitchCorrectionSettings())
        val frames = draft.frames48(source)
        val rendered = Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Corrected",
            AssetRole.RENDERED, derivedFrom = source.hash)
        val corrected = Reducer.reduce(original, VocalPitchEdits.apply(DocumentState(original, 0), draft, rendered)).project
        var serial = 0
        fun edit(project: Project, action: ContinuousEditorAction): Project =
            Reducer.reduce(project, ContinuousClipEdits.intent(project, action, { "new-${++serial}" }, grid = ContinuousGrid.FREE)).project
        val trimmed = edit(corrected, ContinuousEditorAction.TrimClip("clip", 7, frames - 11, 1234))
        for (action in listOf(ContinuousEditorAction.DuplicateClip("clip"), ContinuousEditorAction.RepeatBars(0, 1, 2),
            ContinuousEditorAction.SplitClip("clip", 13_123))) {
            val copied = edit(trimmed, action)
            assertTrue(copied.clips.size > 1)
            for (clip in copied.clips) {
                val next = VocalPitchEdits.draft(DocumentState(copied, 3), clip.id, "ignored", draft.settings.copy(amount = .55f))
                assertEquals(source.hash, next.sourceAssetHash)
                assertEquals(draft.sourceRange, next.sourceRange)
                assertEquals(clip.range, next.auditionRange(copied))
                val replacement = rendered.copy(hash = "c".repeat(64))
                val after = Reducer.reduce(copied, VocalPitchEdits.apply(DocumentState(copied, 3), next, replacement)).project
                assertEquals(clip.copy(assetHash = replacement.hash), after.clips.single { it.id == clip.id })
                assertEquals(copied.clips.filterNot { it.id == clip.id }, after.clips.filterNot { it.id == clip.id })
                assertEquals(copied.pitchCorrections.size, after.pitchCorrections.size)
            }
        }
    }
}
