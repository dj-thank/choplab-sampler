package com.choplab.ui

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.EditSession
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.engine.Tempo
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

    @Test fun trimCannotLeaveAClipShorterThanOneTimelineFrame() {
        // At 96 kHz one source frame is half a 48 kHz frame: playback drops it and the editor could not draw it.
        val p = apply(fixture(96_000), ContinuousEditorAction.PlacePad(0, null, 0))
        val clip = p.clips.single()
        assertFailsWith<IllegalArgumentException> { apply(p, ContinuousEditorAction.TrimClip(clip.id, 1, 2, 0)) }
        val shortest = apply(p, ContinuousEditorAction.TrimClip(clip.id, 1, 3, 0))
        assertEquals(1L, ContinuousClipEdits.durationFrames(shortest, shortest.clips.single()))
    }

    @Test fun trimRangesAlwaysLeaveOneAudibleTimelineFrame() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000)) {
            val p = apply(fixture(rate), ContinuousEditorAction.PlacePad(0, null, 0))
            val model = p.clips.single()
            val clip = ContinuousClip(model.id, model.trackId, "clip", 0, ContinuousClipEdits.durationFrames(p, model),
                model.range.start, model.range.end, rate * 2L, rate)
            val fromStart = apply(p, ContinuousEditorAction.TrimClip(clip.id, ContinuousClipEdits.trimStartRange(clip).last, clip.sourceEndFrame, 0))
            assertTrue(ContinuousClipEdits.durationFrames(fromStart, fromStart.clips.single()) >= 1, "start edge at $rate Hz")
            val fromEnd = apply(p, ContinuousEditorAction.TrimClip(clip.id, clip.sourceStartFrame, ContinuousClipEdits.trimEndRange(clip).first, 0))
            assertTrue(ContinuousClipEdits.durationFrames(fromEnd, fromEnd.clips.single()) >= 1, "end edge at $rate Hz")
        }
    }

    @Test fun savedZeroLengthClipStaysEditableAndDeletable() {
        val base = apply(fixture(96_000), ContinuousEditorAction.PlacePad(0, null, 0))
        val saved = base.copy(clips = base.clips.map { it.copy(range = FrameRange(1, 2)) }.frozen())
        val clip = saved.clips.single()
        assertEquals(0L, ContinuousClipEdits.durationFrames(saved, clip))
        assertEquals(.5f, apply(saved, ContinuousEditorAction.SetClipGain(clip.id, .5f)).clips.single().gain)
        assertTrue(apply(saved, ContinuousEditorAction.DeleteClip(clip.id)).clips.isEmpty())
    }

    @Test fun tickAnchoredClipUsesThePlaybackStartAndSplitHalvesStayJoined() {
        val base = apply(fixture(44_100), ContinuousEditorAction.PlacePad(0, null, 0))
        // One tick at 97 BPM is 30.9 frames. Playback floors it; the editor must draw the same frame.
        val p = base.copy(tempo = Tempo(97_000), clips = base.clips.map { it.copy(timelineStartFrame = null, startTick = 1) }.frozen())
        val clip = p.clips.single()
        val start = ProgramCompiler.tickToFrame(1, 97_000)
        assertEquals(start, ContinuousClipEdits.startFrame(p, clip))
        val split = apply(p, ContinuousEditorAction.SplitClip(clip.id, start + 24_000))
        val left = split.clips[0]; val right = split.clips[1]
        // Both halves are absolute, so a later tempo change cannot open a gap between them.
        assertEquals(start, left.timelineStartFrame)
        assertEquals(start + ContinuousClipEdits.durationFrames(split, left), right.timelineStartFrame)
    }
}
