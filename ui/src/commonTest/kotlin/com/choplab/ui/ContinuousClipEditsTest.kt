package com.choplab.ui

import com.choplab.core.edit.EditSession
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import kotlin.test.*

class ContinuousClipEditsTest {
    private val hash = "a".repeat(64)
    private fun fixture(rate: Int = 48_000): Project {
        val asset = Asset(hash, "wav", 100, rate, 2, rate.toLong() * 2, "Original")
        return Project(assets = frozenListOf(asset), source = Source(hash, FrameRange(0, asset.frames)),
            pads = (0..127).map { if (it == 0) Pad(it, hash, FrameRange(0, asset.frames), gain = .7f) else Pad(it) }.frozen())
    }
    private var id = 0
    private fun fresh(prefix: String) = "$prefix-${++id}"
    private fun apply(p: Project, action: ContinuousEditorAction) = Reducer.reduce(p, ContinuousClipEdits.intent(p, action, ::fresh)).project

    @Test fun placementSnapshotsPadAndKeepsOriginalWithOneUndo() {
        val original = fixture()
        val session = EditSession(original)
        val plan = session.plan(ContinuousClipEdits.intent(original, ContinuousEditorAction.PlacePad(0, null, 137), ::fresh))
        plan.effects.indices.forEach { session.acknowledge(plan, it) }
        session.commit(plan)
        assertEquals(original.source, session.project.source)
        assertEquals(original.pads, session.project.pads)
        assertEquals(137L, session.project.clips.single().timelineStartFrame)
        assertEquals(.7f, session.project.clips.single().gain)
        val undo = requireNotNull(session.planUndo())
        undo.effects.indices.forEach { session.acknowledge(undo, it) }
        session.commit(undo)
        assertEquals(original, session.project)
    }

    @Test fun mixedRateSplitHasNoDuplicatedBoundaryAndDuplicateFollowsEnd() {
        var p = apply(fixture(44_100), ContinuousEditorAction.PlacePad(0, null, 137))
        val old = p.clips.single()
        p = apply(p, ContinuousEditorAction.SplitClip(old.id, 24_140))
        val left = p.clips[0]; val right = p.clips[1]
        assertEquals(left.range.end, right.range.start)
        assertEquals(ContinuousClipEdits.startFrame(p, left) + ContinuousClipEdits.durationFrames(p, left), right.timelineStartFrame)
        assertEquals(96_000L, p.clips.sumOf { ContinuousClipEdits.durationFrames(p, it) })
        val next = apply(p, ContinuousEditorAction.DuplicateClip(right.id))
        assertEquals(ContinuousClipEdits.startFrame(p, right) + ContinuousClipEdits.durationFrames(p, right), next.clips.last().timelineStartFrame)
    }

    @Test fun clipGainAndTrackMuteDoNotChangePadOrSource() {
        val p = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 0))
        val changed = apply(apply(p, ContinuousEditorAction.SetClipGain(p.clips.single().id, .25f)),
            ContinuousEditorAction.SetTrackMuted(p.tracks.single().id, true))
        assertEquals(p.pads, changed.pads)
        assertEquals(p.source, changed.source)
        assertEquals(.25f, changed.clips.single().gain)
        assertTrue(changed.tracks.single().mute)
    }

    @Test fun invalidTrimAndTransformedPadCannotSilentlyChangeAudio() {
        val p = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 0))
        assertFailsWith<IllegalArgumentException> {
            apply(p, ContinuousEditorAction.TrimClip(p.clips.single().id, 0, 200_000, 0))
        }
        val pitched = p.copy(pads = p.pads.map { if (it.id == 0) it.copy(pitchSemitones = 12.0) else it }.frozen())
        assertFailsWith<IllegalArgumentException> { apply(pitched, ContinuousEditorAction.PlacePad(0, null, 0)) }
        assertEquals(1, pitched.clips.size)
    }
}
