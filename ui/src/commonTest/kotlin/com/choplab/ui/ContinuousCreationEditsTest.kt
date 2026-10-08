package com.choplab.ui

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.persistence.ProjectJson
import com.choplab.core.vocal.VocalPitchEdits
import com.choplab.engine.PitchCorrectionSettings
import kotlin.test.*

class ContinuousCreationEditsTest {
    private var serial = 0
    private fun fresh(kind: String) = "$kind-${++serial}"
    private val source = Asset("a".repeat(64), "wav", 96_044, 48_000, 2, 96_000, "Source")
    private fun project(tracks: Int) = Project(assets = frozenListOf(source),
        tracks = (0 until tracks).map { Track("track-$it", "Track $it", TrackKind.VOCAL) }.frozen(),
        clips = frozenListOf(Clip("clip", "track-0", source.hash, FrameRange(0, 48_000))))
    private fun commit(session: EditSession, plan: EditPlan) {
        plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
    }
    @Test fun legalLargeTrackDocumentsAllowOrganizingWithOneUndoAndRoundTrip() {
        for (count in listOf(17, 32, 64)) for (action in listOf(ContinuousEditorAction.DeleteClip("clip"),
            ContinuousEditorAction.MoveClip("clip", "track-${count - 1}", 123), ContinuousEditorAction.SetClipGain("clip", .4f))) {
            val before = project(count)
            val session = EditSession(before)
            commit(session, session.plan(ContinuousClipEdits.intent(before, action, ::fresh)))
            assertEquals(1, session.undoCount)
            assertEquals(count, session.project.tracks.size)
            assertEquals(session.project, ProjectJson.decode(ProjectJson.encode(session.project)))
            assertEquals(before.assets, session.project.assets)
            commit(session, session.planUndo()!!); assertEquals(before, session.project)
        }
        val full = project(64).let { it.copy(pads = it.pads.map { pad -> if (pad.id == 0) pad.copy(assetHash = source.hash, range = FrameRange(0, 1000)) else pad }.frozen()) }
        assertFailsWith<IllegalArgumentException> { ContinuousClipEdits.intent(full, ContinuousEditorAction.PlacePad(0, null, 0), ::fresh) }
        val overcrowded = project(1).copy(clips = (0..1025).map { Clip("c-$it", "track-0", source.hash, FrameRange(0, 10), timelineStartFrame = it * 10L) }.frozen())
        val reduced = Reducer.reduce(overcrowded, ContinuousClipEdits.intent(overcrowded, ContinuousEditorAction.DeleteClip("c-0"), ::fresh)).project
        assertEquals(1025, reduced.clips.size, "An old oversized arrangement can be reduced one clip at a time")
    }
    @Test fun duplicateAndRepeatRetainIndependentPitchAndStretchRecipesAndOriginalAssets() {
        for (pitch in listOf(false, true)) for (repeat in listOf(false, true)) {
            val target = StretchTarget(StretchKind.CLIP, "clip")
            val rendered = source.copy(hash = "b".repeat(64), role = AssetRole.RENDERED, derivedFrom = source.hash, frames = if (pitch) 48_000 else 24_000)
            val recipe = VocalPitchCorrection("pitch-original", "clip", source.hash, FrameRange(0, 48_000), rendered.hash, PitchCorrectionSettings())
            val stretched = BeatStretch(target, source.hash, FrameRange(0, 48_000), rendered.hash, FrameRange(0, 24_000), 60_000, 120_000)
            val before = project(1).copy(assets = frozenListOf(source, rendered),
                clips = frozenListOf(Clip("clip", "track-0", rendered.hash, FrameRange(0, rendered.frames))),
                pitchCorrections = if (pitch) frozenListOf(recipe) else frozenListOf(),
                beatStretches = if (pitch) frozenListOf() else frozenListOf(stretched))
            val action = if (repeat) ContinuousEditorAction.RepeatBars(0, 1, 2) else ContinuousEditorAction.DuplicateClip("clip")
            val session = EditSession(before)
            commit(session, session.plan(ContinuousClipEdits.intent(before, action, ::fresh)))
            assertEquals(1, session.undoCount)
            val copied = ProjectJson.decode(ProjectJson.encode(session.project))
            assertEquals(session.project, copied)
            for (clip in copied.clips.filter { it.id != "clip" }) {
                if (pitch) {
                    val inherited = copied.pitchCorrections.first { it.clipId == clip.id }
                    assertNotEquals(recipe.id, inherited.id)
                    assertEquals(recipe.copy(id = inherited.id, clipId = clip.id), inherited)
                    val draft = VocalPitchEdits.draft(DocumentState(copied, 1), clip.id, "unused", recipe.settings.copy(amount = .3f))
                    assertEquals(source.hash, draft.sourceAssetHash); assertEquals(recipe.sourceRange, draft.sourceRange)
                    val changed = Reducer.reduce(copied, VocalPitchEdits.apply(DocumentState(copied, 1), draft, rendered)).project
                    assertEquals(recipe, changed.pitchCorrections.first { it.clipId == "clip" })
                    assertEquals(.3f, changed.pitchCorrections.first { it.clipId == clip.id }.settings.amount)
                } else {
                    val inherited = copied.beatStretches.first { it.target.id == clip.id }
                    assertEquals(stretched.copy(target = inherited.target), inherited)
                    val draft = BeatStretchEdits.draft(DocumentState(copied, 1), inherited.target, 90_000)
                    assertEquals(source.hash, draft.sourceAssetHash); assertEquals(stretched.sourceRange, draft.sourceRange)
                    val nextRender = rendered.copy(hash = "c".repeat(64), frames = 36_000)
                    val changed = Reducer.reduce(copied, BeatStretchEdits.apply(DocumentState(copied, 1), draft, nextRender)).project
                    assertEquals(stretched, changed.beatStretches.first { it.target == target })
                    assertEquals(90_000, changed.beatStretches.first { it.target.id == clip.id }.sourceMilliBpm)
                }
            }
            commit(session, session.planUndo()!!); assertEquals(before, session.project)
        }
    }
    @Test fun repeatedBarsRejectAnEarlierClipThatSoundsIntoDestinationButAllowItsExclusiveEnd() {
        val base = project(1)
        val sourceBarStart = 96_000L
        val destination = 192_000L
        val selected = Clip("selected", "track-0", source.hash, FrameRange(0, 1000), timelineStartFrame = sourceBarStart)
        // Start earlier than the chosen source bar to avoid selecting the sustaining clip itself.
        val longSource = source.copy(frames = 300_000)
        fun p(end: Long) = base.copy(assets = frozenListOf(longSource), clips = frozenListOf(
            Clip("earlier", "track-0", source.hash, FrameRange(0, end), timelineStartFrame = 0), selected))
        assertFailsWith<IllegalArgumentException> { ContinuousClipEdits.intent(p(destination + 1), ContinuousEditorAction.RepeatBars(sourceBarStart, 1, 1), ::fresh) }
        val before = p(destination)
        val after = Reducer.reduce(before, ContinuousClipEdits.intent(before, ContinuousEditorAction.RepeatBars(sourceBarStart, 1, 1), ::fresh)).project
        assertEquals(3, after.clips.size)
        assertEquals(destination, after.clips.last().timelineStartFrame)
        assertFalse(ContinuousClipEdits.overlapsFrames(0, destination, destination until destination + 96_000))
    }
}
