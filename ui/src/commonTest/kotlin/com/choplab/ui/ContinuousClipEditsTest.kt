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
        val quarter = ceGridLines(Tempo(120_000), ContinuousGrid.QUARTER, 24f, 6f, 0f, 50f)
        assertEquals(listOf(0f to 2, 12f to 1, 24f to 1, 36f to 1, 48f to 2), quarter.take(5), "Quarter lines 3 px apart are left out")
        val half = ceGridLines(Tempo(120_000), ContinuousGrid.HALF, 24f, 6f, 0f, 13f)
        assertEquals(listOf(0f to 2, 6f to 0, 12f to 1), half.take(3))
        assertEquals(ceGridLines(Tempo(120_000), ContinuousGrid.BEAT, 24f, 6f, 0f, 50f), ceGridLines(Tempo(120_000), ContinuousGrid.FREE, 24f, 6f, 0f, 50f))
        // Zoomed far out (240 BPM at 4 px a second, a bar 4 px): every other bar.
        val far = ceGridLines(Tempo(240_000), ContinuousGrid.QUARTER, 4f, 6f, 0f, 17f)
        assertEquals(listOf(0f to 2, 8f to 2, 16f to 2), far.take(3))
        // Only what is in view: a window far along starts near it.
        val window = ceGridLines(Tempo(120_000), ContinuousGrid.BEAT, 24f, 6f, 1_200f, 1_250f)
        assertTrue(window.first().first in 1_188f..1_200f && window.size <= 6)
    }

    @Test fun swingMovesTheOffSixteenthsLinesAndWhatIsPlacedOnThem() {
        // 120 BPM at 60%: an eighth is 12 000 frames, and its second sixteenth comes 7 200 frames in, not 6 000.
        val swung = Tempo(120_000, 600)
        val straight = Tempo(120_000)
        val p = fixture().copy(tempo = swung)
        val off = apply(p, ContinuousEditorAction.PlacePad(0, null, 9_300), ContinuousGrid.QUARTER)
        assertEquals(240L, off.clips.single().startTick, "Nearest the swung line, where straight the eighth would be")
        assertEquals(7_200L, off.start())
        assertEquals(7_200L, ContinuousClipEdits.landingFrame(9_300, swung, ContinuousGrid.QUARTER), "Shown where it lands")
        assertEquals(9_300L, ContinuousClipEdits.landingFrame(9_300, swung, ContinuousGrid.FREE))
        assertEquals(480L, ContinuousClipEdits.snapTick(9_300, straight, ContinuousGrid.QUARTER))
        assertEquals(0L, ContinuousClipEdits.snapTick(3_500, swung, ContinuousGrid.QUARTER))
        assertEquals(240L, ContinuousClipEdits.snapTick(3_500, straight, ContinuousGrid.QUARTER))
        // From the swung line a nudge goes back to the beat before it, or on to the eighth after it.
        val id = off.clips.single().id
        assertEquals(0L, apply(off, ContinuousEditorAction.NudgeClip(id, forward = false), ContinuousGrid.QUARTER).clips.single().startTick)
        assertEquals(480L, apply(off, ContinuousEditorAction.NudgeClip(id, forward = true), ContinuousGrid.QUARTER).clips.single().startTick)
        // Between where the sixteenth would be straight (6 000) and where it swings to (7 200), it is still ahead.
        assertEquals(240L, ContinuousClipEdits.adjacentTick(6_500, swung, ContinuousGrid.QUARTER, forward = true))
        assertEquals(0L, ContinuousClipEdits.adjacentTick(6_500, swung, ContinuousGrid.QUARTER, forward = false))
        // Bars and beats stay where they were.
        assertEquals(1L, ContinuousClipEdits.barAt(96_000, swung))
        assertEquals(0L, ContinuousClipEdits.barAt(95_999, swung))
        assertEquals(ContinuousClipEdits.snapTick(29_000, straight, ContinuousGrid.BEAT), ContinuousClipEdits.snapTick(29_000, swung, ContinuousGrid.BEAT))
        // The grid draws a swung line where its sounds go: at 96 px a second a sixteenth is 12 px, swung 2.4 px later.
        val lines = ceGridLines(swung, ContinuousGrid.QUARTER, 96f, 6f, 0f, 48f)
        assertEquals(listOf(2, 0, 0, 0, 1), lines.map { it.second })
        listOf(0f, 14.4f, 24f, 38.4f, 48f).zip(lines.map { it.first }).forEach { (expected, x) -> assertEquals(expected, x, .001f) }
        // At 75% the gap after an off sixteenth halves, to 6 px: with 7 px between lines, beats only.
        assertEquals(listOf(0f to 2, 48f to 1), ceGridLines(Tempo(120_000, 750), ContinuousGrid.QUARTER, 96f, 7f, 0f, 48f))
        assertEquals(5, ceGridLines(straight, ContinuousGrid.QUARTER, 96f, 7f, 0f, 48f).size)
    }

    @Test fun playedHitsGoOntoTheGridOnceEachAsPlacingPutsThem() {
        // 120 BPM: a beat is 24 000 frames. PAD 1 plays another part of PAD 0's sound.
        val p = fixture().let { p -> p.copy(pads = p.pads.map { when (it.id) {
            0 -> it.copy(range = FrameRange(0, 2_400))
            1 -> Pad(1, hash, FrameRange(2_400, 4_800))
            else -> it
        } }.frozen()) }
        val hits = listOf(ContinuousHit(0, 23_000), ContinuousHit(1, 25_000), ContinuousHit(0, 25_500), ContinuousHit(0, 47_000))
        val played = apply(p, ContinuousEditorAction.PlaceHits(hits), ContinuousGrid.BEAT)
        // PAD 0 at 23 000 and 25 500 both go to the second beat: once. PAD 1 there too, as another sound.
        assertEquals(listOf(FrameRange(0, 2_400) to 960L, FrameRange(2_400, 4_800) to 960L, FrameRange(0, 2_400) to 1_920L),
            played.clips.map { it.range to it.startTick })
        assertTrue(played.clips.all { it.timelineStartFrame == null && it.trackId == played.tracks.single().id }, "On the beat, on one track")
        assertEquals(listOf(p.pads[0].gain, p.pads[1].gain, p.pads[0].gain), played.clips.map { it.gain }, "As loud as each PAD")
        // Where the same sound already starts, a hit adds nothing.
        assertEquals(played.clips, apply(played, ContinuousEditorAction.PlaceHits(listOf(ContinuousHit(0, 24_100))), ContinuousGrid.BEAT).clips)
        // Free, each goes where it was heard.
        val free = apply(p, ContinuousEditorAction.PlaceHits(listOf(ContinuousHit(0, 23_000), ContinuousHit(0, 23_000), ContinuousHit(0, 25_500))))
        assertEquals(listOf(23_000L, 25_500L), free.clips.map { it.timelineStartFrame })
        // Nothing played, or an empty PAD, is refused; a transformed PAD goes on as its rendered sound.
        assertFailsWith<IllegalArgumentException> { apply(p, ContinuousEditorAction.PlaceHits(emptyList())) }
        assertFailsWith<IllegalArgumentException> { apply(p, ContinuousEditorAction.PlaceHits(listOf(ContinuousHit(5, 0)))) }
        val pitched = p.copy(pads = p.pads.map { if (it.id == 0) it.copy(pitchSemitones = 12.0) else it }.frozen())
        assertFailsWith<IllegalArgumentException> { apply(pitched, ContinuousEditorAction.PlaceHits(listOf(ContinuousHit(0, 0)))) }
        val made = Asset("c".repeat(64), "wav", 9_644, 48_000, 2, 1_200, "Octave", AssetRole.RENDERED, derivedFrom = hash)
        val both = listOf(ContinuousHit(0, 0), ContinuousHit(1, 24_000), ContinuousHit(0, 48_000))
        val rendered = Reducer.reduce(pitched, ContinuousClipEdits.intent(pitched, ContinuousEditorAction.PlaceHits(both), ::fresh,
            mapOf(0 to made), ContinuousGrid.BEAT)).project
        assertEquals(listOf(made.hash, hash, made.hash), rendered.clips.map { it.assetHash })
        assertEquals(FrameRange(0, 1_200), rendered.clips.first().range)
        assertTrue(rendered.assets.any { it.hash == made.hash })
    }

    @Test fun aFillPlacesThePadOnEveryLineThroughItsBarsInPlaceOfItsOwnSoundOnThatTrack() {
        // 120 BPM: a bar is 96 000 frames, 3 840 ticks. Song position 100 000 is in the second bar.
        val p = fixture().let { p -> p.copy(pads = p.pads.map { when (it.id) {
            0 -> it.copy(range = FrameRange(0, 2_400))
            1 -> Pad(1, hash, FrameRange(2_400, 4_800))
            else -> it
        } }.frozen()) }
        val filled = apply(p, ContinuousEditorAction.FillPad(0, null, 100_000, ContinuousGrid.HALF, 2))
        assertEquals((0 until 16).map { 3_840L + it * 480 }, filled.clips.map { it.startTick })
        assertTrue(filled.clips.all { it.timelineStartFrame == null && it.range == FrameRange(0, 2_400) && it.gain == .7f }, "On the beat, as the PAD")
        // Another fill of those bars replaces the PAD's own sound there. Its sound before them, another PAD's sound and the
        // same sound on another track all stay.
        val before = apply(filled, ContinuousEditorAction.PlacePad(0, null, 0), ContinuousGrid.BEAT)
        val other = apply(before, ContinuousEditorAction.PlacePad(1, null, 96_000), ContinuousGrid.BEAT)
        val elsewhere = Track("track-other", "Other", TrackKind.BANK)
        val twoTracks = other.copy(tracks = (other.tracks + elsewhere).frozen())
        val layered = apply(twoTracks, ContinuousEditorAction.PlacePad(0, elsewhere.id, 96_000), ContinuousGrid.BEAT)
        val refilled = apply(layered, ContinuousEditorAction.FillPad(0, null, 96_000, ContinuousGrid.BEAT, 2))
        val mine = refilled.clips.filter { it.range == FrameRange(0, 2_400) && it.trackId != elsewhere.id }
        assertEquals(listOf(0L) + (0 until 8).map { 3_840L + it * 960 }, mine.map { it.startTick }.sorted())
        assertEquals(1, refilled.clips.count { it.range == FrameRange(2_400, 4_800) }, "Another PAD's sound stays")
        assertEquals(1, refilled.clips.count { it.trackId == elsewhere.id }, "So does the sound on another track")
    }

    @Test fun aFillStartsAtTheBarPlaybackReachesAndTakesOnlyItsChoices() {
        // At 97 BPM the second bar falls between frames: from the frame playback starts it, it is the second bar.
        val p = fixture().copy(tempo = Tempo(97_000))
        val second = ProgramCompiler.tickToFrame(3_840, 97_000)
        assertEquals(1L, ContinuousClipEdits.barAt(second, Tempo(97_000)))
        assertEquals(0L, ContinuousClipEdits.barAt(second - 1, Tempo(97_000)))
        assertEquals(3_840L, apply(p, ContinuousEditorAction.FillPad(0, null, second, ContinuousGrid.BEAT, 1)).clips.first().startTick)
        assertEquals(0L, apply(p, ContinuousEditorAction.FillPad(0, null, second - 1, ContinuousGrid.BEAT, 1)).clips.first().startTick)
        assertEquals(32, apply(p, ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 8)).clips.size)
        for (refused in listOf(ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.FREE, 1),
                ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 0), ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 9),
                ContinuousEditorAction.FillPad(5, null, 0, ContinuousGrid.BEAT, 1))) {
            assertFailsWith<IllegalArgumentException>("$refused") { apply(p, refused) }
        }
        // A transformed PAD is rendered first, as for a single placement.
        val pitched = p.copy(pads = p.pads.map { if (it.id == 0) it.copy(pitchSemitones = 12.0) else it }.frozen())
        assertFailsWith<IllegalArgumentException> { apply(pitched, ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.BEAT, 1)) }
    }

    @Test fun anEditTheSongCouldNotPlayIsRefusedWhileASongAlreadyPastItCanBeThinnedOut() {
        // A five-second sound every sixteenth at 120 BPM (6 000 frames apart) would sound 40 at once through four bars.
        val long = Asset(hash, "wav", 100, 48_000, 2, 240_000, "Long")
        val p = Project(assets = frozenListOf(long), pads = (0..127).map { if (it == 0) Pad(it, hash, FrameRange(0, 240_000)) else Pad(it) }.frozen())
        assertFailsWith<ContinuousClipEdits.SongFull> { apply(p, ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.QUARTER, 4)) }
        // Through one bar, 16 at once.
        val one = apply(p, ContinuousEditorAction.FillPad(0, null, 0, ContinuousGrid.QUARTER, 1))
        assertEquals(16, one.clips.size)
        assertTrue(ProgramCompiler.songFits(one))
        // Nor past 30 minutes, nor past 1024 clips.
        assertFailsWith<ContinuousClipEdits.SongFull> { apply(p, ContinuousEditorAction.PlacePad(0, null, ContinuousClipEdits.MAX_TIMELINE_FRAMES - 1)) }
        val short = one.clips[0].copy(range = FrameRange(0, 100))
        val full = one.copy(clips = (0 until 1024).map { short.copy(id = "full-$it", timelineStartFrame = it * 1_000L) }.frozen())
        assertFailsWith<ContinuousClipEdits.SongFull> { apply(full, ContinuousEditorAction.DuplicateClip("full-0")) }
        // A song an earlier build let past the limit stays editable, so it can be thinned out.
        val over = one.copy(clips = (0 until 40).map { one.clips[0].copy(id = "over-$it", timelineStartFrame = it * 6_000L) }.frozen())
        assertFalse(ProgramCompiler.songFits(over))
        assertEquals(39, apply(over, ContinuousEditorAction.DeleteClip("over-0")).clips.size)
    }

    @Test fun aRepeatCopiesItsBarsIntoTheEmptyBarsAfterThemOnTheBeatOrByTheBarsFrames() {
        // 120 BPM: a bar is 96 000 frames. Bar 1 holds a hit on its second beat and one placed freely; bar 3 one more.
        val hit = fixture().let { p -> p.copy(pads = p.pads.map { if (it.id == 0) it.copy(range = FrameRange(0, 2_400)) else it }.frozen()) }
        val onBeat = apply(hit, ContinuousEditorAction.PlacePad(0, null, 24_000), ContinuousGrid.BEAT)
        val free = apply(onBeat, ContinuousEditorAction.PlacePad(0, null, 50_000))
        val p = apply(free, ContinuousEditorAction.PlacePad(0, null, 200_000), ContinuousGrid.BEAT)
        assertEquals(listOf(24_000L, 50_000L, 192_000L), p.clips.map { p.start(it) })
        // From the song position's bar: bar 3's hit repeats into bar 4, on the beat.
        val third = apply(p, ContinuousEditorAction.RepeatBars(200_000, 1, 1)).clips.drop(3).single()
        assertEquals(11_520L to null, third.startTick to third.timelineStartFrame)
        // Twice would need bars 2 and 3 empty; bar 3 is not.
        assertFailsWith<IllegalArgumentException> { apply(p, ContinuousEditorAction.RepeatBars(10_000, 1, 2)) }
        val once = apply(p, ContinuousEditorAction.RepeatBars(10_000, 1, 1))
        val copies = once.clips.drop(3)
        assertEquals(listOf(120_000L, 146_000L), copies.map { once.start(it) })
        assertEquals(listOf(4_800L, null), copies.map { if (it.timelineStartFrame == null) it.startTick else null }, "The beat's copy keeps to the beat")
        fun sound(clip: Clip) = listOf(clip.trackId, clip.assetHash, clip.range, clip.gain, clip.pan)
        assertEquals(p.clips.take(2).map(::sound), copies.map(::sound), "The same sounds, tracks and levels")
        // Bars with nothing to repeat, and choices the panel does not offer, are refused.
        for (refused in listOf(ContinuousEditorAction.RepeatBars(400_000, 1, 1), ContinuousEditorAction.RepeatBars(0, 3, 1),
                ContinuousEditorAction.RepeatBars(0, 1, 0))) {
            assertFailsWith<IllegalArgumentException>("$refused") { apply(once, refused) }
        }
        // A freely placed clip moves by the bars' frames as playback maps them: at 97 BPM a bar is 118 762 frames.
        val odd = apply(fixture().copy(tempo = Tempo(97_000)), ContinuousEditorAction.PlacePad(0, null, 1_000))
        // Three times is not among the panel's choices, even into empty bars.
        assertFailsWith<IllegalArgumentException> { apply(odd, ContinuousEditorAction.RepeatBars(0, 1, 3)) }
        val repeated = apply(odd, ContinuousEditorAction.RepeatBars(0, 1, 2))
        assertEquals(listOf(1_000L, 1_000L + ProgramCompiler.tickToFrame(3_840, 97_000), 1_000L + ProgramCompiler.tickToFrame(7_680, 97_000)),
            repeated.clips.map { repeated.start(it) })
    }

    @Test fun aRepeatKeepsItsCopiesInTheirBarsAndNeverPlaysASoundOverItsOwnRepeat() {
        // At 97 BPM bars 2-5 run 475 052 frames from 118 762, and bars 6-9 one frame fewer, to 1 068 864.
        val odd = fixture().copy(tempo = Tempo(97_000))
        val last = apply(odd, ContinuousEditorAction.PlacePad(0, null, 593_813))
        val copy = apply(last, ContinuousEditorAction.RepeatBars(118_762, 4, 1)).clips.last()
        assertEquals(1_068_864L, copy.timelineStartFrame, "The last frame of bars 6-9, not bar 10's first")
        // 240 BPM: a bar is 48 000 frames and the two-second sound lasts two. Repeated every bar it would play over itself.
        val long = apply(fixture().copy(tempo = Tempo(240_000)), ContinuousEditorAction.PlacePad(0, null, 0))
        assertFailsWith<IllegalArgumentException> { apply(long, ContinuousEditorAction.RepeatBars(0, 1, 1)) }
        assertEquals(listOf(0L, 96_000L), apply(long, ContinuousEditorAction.RepeatBars(0, 2, 1)).let { p -> p.clips.map { p.start(it) } })
        // A silent clip an earlier build saved is not repeated, and alone it is nothing to repeat.
        val placed = apply(fixture(96_000), ContinuousEditorAction.PlacePad(0, null, 0))
        val silent = placed.copy(clips = placed.clips.map { it.copy(range = FrameRange(1, 2)) }.frozen())
        assertFailsWith<IllegalArgumentException> { apply(silent, ContinuousEditorAction.RepeatBars(0, 2, 1)) }
        val both = apply(silent, ContinuousEditorAction.PlacePad(0, null, 1_000))
        assertEquals(3, apply(both, ContinuousEditorAction.RepeatBars(0, 2, 1)).clips.size)
    }
}

