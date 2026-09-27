package com.choplab.ui

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.EditSession
import com.choplab.core.edit.Mutation
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
    private fun apply(p: Project, action: ContinuousEditorAction, grid: ContinuousGrid = ContinuousGrid.FREE) =
        Reducer.reduce(p, ContinuousClipEdits.intent(p, action, ::fresh, grid = grid)).project
    private fun Project.start(clip: Clip = clips.last()) = ContinuousClipEdits.startFrame(this, clip)

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

    @Test fun onTheGridAPlacedPadStartsOnTheNearestLineAndKeepsItsBeatWhenTheTempoChanges() {
        // 120 BPM: a beat is 24 000 frames. 29 000 frames is just past the second beat.
        val p = fixture()
        for ((grid, frame) in listOf(ContinuousGrid.BEAT to 24_000L, ContinuousGrid.HALF to 24_000L, ContinuousGrid.QUARTER to 30_000L)) {
            val placed = apply(p, ContinuousEditorAction.PlacePad(0, null, 29_000), grid)
            assertNull(placed.clips.single().timelineStartFrame, "$grid anchors to the beat")
            assertEquals(frame, placed.start(), "$grid")
        }
        val free = apply(p, ContinuousEditorAction.PlacePad(0, null, 29_000))
        assertEquals(29_000L, free.clips.single().timelineStartFrame, "Free is exactly where it was let go")
        // Halving the tempo: the clip on the grid stays on its beat, the free one where it was.
        val onBeat = apply(p, ContinuousEditorAction.PlacePad(0, null, 29_000), ContinuousGrid.BEAT)
        assertEquals(48_000L, onBeat.copy(tempo = Tempo(60_000)).start())
        assertEquals(29_000L, free.copy(tempo = Tempo(60_000)).start())
        // At 97 BPM a line falls between frames: it starts where playback floors it, the same frame the grid draws.
        val odd = apply(p.copy(tempo = Tempo(97_000)), ContinuousEditorAction.PlacePad(0, null, 60_000), ContinuousGrid.BEAT)
        assertEquals(ProgramCompiler.tickToFrame(2 * 960, 97_000), odd.start())
    }

    @Test fun onTheGridAMoveSnapsButOneOnlyToAnotherTrackStaysPut() {
        val free = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 29_000))
        val clip = free.clips.single()
        val moved = apply(free, ContinuousEditorAction.MoveClip(clip.id, clip.trackId, 77_000), ContinuousGrid.BEAT)
        assertEquals(72_000L, moved.start())
        assertNull(moved.clips.single().timelineStartFrame)
        val other = Track("track-other", "Other", TrackKind.BANK)
        val twoTracks = free.copy(tracks = (free.tracks + other).frozen())
        val across = apply(twoTracks, ContinuousEditorAction.MoveClip(clip.id, other.id, 29_000), ContinuousGrid.BEAT)
        assertEquals(clip.copy(trackId = other.id), across.clips.single(), "Off the grid it stays, exactly where it was")
    }

    @Test fun aNudgeGoesToTheNextOrPreviousLineAndOnAFreeGridBySeconds() {
        // Placed on a quarter beat, then nudged on the beat grid: to the beats either side of it.
        val p = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 6_000), ContinuousGrid.QUARTER)
        val clip = p.clips.single()
        assertEquals(6_000L, p.start())
        val later = apply(p, ContinuousEditorAction.NudgeClip(clip.id, forward = true), ContinuousGrid.BEAT)
        assertEquals(24_000L, later.start())
        assertEquals(48_000L, apply(later, ContinuousEditorAction.NudgeClip(clip.id, forward = true), ContinuousGrid.BEAT).start())
        val earlier = apply(later, ContinuousEditorAction.NudgeClip(clip.id, forward = false), ContinuousGrid.BEAT)
        assertEquals(0L, earlier.start())
        assertEquals(0L, apply(p, ContinuousEditorAction.NudgeClip(clip.id, forward = false), ContinuousGrid.BEAT).start())
        // At the first line there is none earlier: nothing changes, so nothing is added to Undo.
        val first = ContinuousClipEdits.intent(earlier, ContinuousEditorAction.NudgeClip(clip.id, forward = false), ::fresh, grid = ContinuousGrid.BEAT)
        assertEquals(Mutation.NONE, Reducer.reduce(earlier, first).mutation)
        // Free: a second each way, never before the start.
        val free = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 30_000))
        assertEquals(78_000L, apply(free, ContinuousEditorAction.NudgeClip(free.clips.single().id, forward = true)).start())
        assertEquals(0L, apply(free, ContinuousEditorAction.NudgeClip(free.clips.single().id, forward = false)).start())
        val start = apply(free, ContinuousEditorAction.MoveClip(free.clips.single().id, free.clips.single().trackId, 0))
        val none = ContinuousClipEdits.intent(start, ContinuousEditorAction.NudgeClip(start.clips.single().id, forward = false), ::fresh)
        assertEquals(Mutation.NONE, Reducer.reduce(start, none).mutation)
        // At 97 BPM the lines fall between frames; a nudge still lands on the next one, not back on its own.
        val odd = apply(fixture().copy(tempo = Tempo(97_000)), ContinuousEditorAction.PlacePad(0, null, 0), ContinuousGrid.BEAT)
        val step1 = apply(odd, ContinuousEditorAction.NudgeClip(odd.clips.single().id, forward = true), ContinuousGrid.BEAT)
        val step2 = apply(step1, ContinuousEditorAction.NudgeClip(odd.clips.single().id, forward = true), ContinuousGrid.BEAT)
        assertEquals(listOf(960L, 1_920L), listOf(step1.clips.single().startTick, step2.clips.single().startTick))
        assertEquals(960L, apply(step2, ContinuousEditorAction.NudgeClip(odd.clips.single().id, forward = false), ContinuousGrid.BEAT).clips.single().startTick)
    }

    @Test fun onTheGridADuplicateRepeatsAShortHitOnTheNextLineAndALoopEndToEnd() {
        // A short hit (a tenth of a beat) repeats on the next beat.
        val hit = fixture().let { p -> p.copy(pads = p.pads.map { if (it.id == 0) it.copy(range = FrameRange(0, 2_400)) else it }.frozen()) }
        val placed = apply(hit, ContinuousEditorAction.PlacePad(0, null, 24_000), ContinuousGrid.BEAT)
        val twice = apply(placed, ContinuousEditorAction.DuplicateClip(placed.clips.single().id), ContinuousGrid.BEAT)
        assertEquals(48_000L, twice.start())
        assertNull(twice.clips.last().timelineStartFrame)
        assertEquals(72_000L, apply(twice, ContinuousEditorAction.DuplicateClip(twice.clips.last().id), ContinuousGrid.BEAT).start())
        // A bar-long loop follows end to end.
        val bar = fixture().let { p -> p.copy(pads = p.pads.map { if (it.id == 0) it.copy(range = FrameRange(0, 96_000)) else it }.frozen()) }
        val loop = apply(bar, ContinuousEditorAction.PlacePad(0, null, 0), ContinuousGrid.BEAT)
        assertEquals(96_000L, apply(loop, ContinuousEditorAction.DuplicateClip(loop.clips.single().id), ContinuousGrid.BEAT).start())
    }

    @Test fun trimmingTheEndKeepsAClipOnItsBeatAndTrimmingTheStartIsExact() {
        val p = apply(fixture(), ContinuousEditorAction.PlacePad(0, null, 24_000), ContinuousGrid.BEAT)
        val clip = p.clips.single()
        val end = apply(p, ContinuousEditorAction.TrimClip(clip.id, 0, 50_000, 24_000), ContinuousGrid.BEAT).clips.single()
        assertEquals(clip.copy(range = FrameRange(0, 50_000)), end, "Still on its beat")
        val start = apply(p, ContinuousEditorAction.TrimClip(clip.id, 1_000, 96_000, 25_000), ContinuousGrid.BEAT).clips.single()
        assertEquals(25_000L, start.timelineStartFrame, "Trims are exact, even on the grid")
        assertEquals(FrameRange(1_000, 96_000), start.range)
    }

    @Test fun gridLinesAreBarsBeatsAndFinerLinesWhereThereIsRoom() {
        // 120 BPM at 24 px a second: a beat is 12 px, a bar 48 px.
        val quarter = ceGridLines(120_000, ContinuousGrid.QUARTER, 24f, 6f, 0f, 50f)
        assertEquals(listOf(0f to 2, 12f to 1, 24f to 1, 36f to 1, 48f to 2), quarter.take(5), "Quarter lines 3 px apart are left out")
        val half = ceGridLines(120_000, ContinuousGrid.HALF, 24f, 6f, 0f, 13f)
        assertEquals(listOf(0f to 2, 6f to 0, 12f to 1), half.take(3))
        assertEquals(ceGridLines(120_000, ContinuousGrid.BEAT, 24f, 6f, 0f, 50f), ceGridLines(120_000, ContinuousGrid.FREE, 24f, 6f, 0f, 50f))
        // Zoomed far out (240 BPM at 4 px a second, a bar 4 px): every other bar.
        val far = ceGridLines(240_000, ContinuousGrid.QUARTER, 4f, 6f, 0f, 17f)
        assertEquals(listOf(0f to 2, 8f to 2, 16f to 2), far.take(3))
        // Only what is in view: a window far along starts near it.
        val window = ceGridLines(120_000, ContinuousGrid.BEAT, 24f, 6f, 1_200f, 1_250f)
        assertTrue(window.first().first in 1_188f..1_200f && window.size <= 6)
    }
}

