package com.choplab.core

import com.choplab.core.model.*
import com.choplab.engine.PcmAsset
import com.choplab.engine.Tempo
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ArrangementCompilerTest {
    private fun asset(letter: String = "a", rate: Int = 48_000, frames: Long = 100) = Asset(letter.repeat(64), "wav", frames * 4 + 44, rate, 2, frames, "$letter.wav")
    private class Loader : PcmPort {
        val loaded = mutableListOf<String>()
        override suspend fun load(asset: Asset): PcmAsset {
            loaded += asset.hash
            val frames = ((asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate).toInt()
            return PcmAsset.fromInterleaved(FloatArray(frames * 2) { 0.1f })
        }
    }
    private fun project(asset: Asset, clips: List<Clip> = emptyList(), tracks: List<Track> = listOf(Track("t", "Track", TrackKind.SOURCE)), takes: List<Take> = emptyList()) =
        Project(assets = frozenListOf(asset), tracks = tracks.frozen(), clips = clips.frozen(), takes = takes.frozen())

    @Test fun explicitArrangementRetainsAuditionPadsButNeverSequencesInactivePattern() = runTest {
        val a = asset(); val loader = Loader(); val compiler = ProgramCompiler(loader)
        val p = project(a, listOf(Clip("c", "t", a.hash, FrameRange(0, 100), timelineStartFrame = 7))).copy(
            pads = (0..127).map { if (it == 0) Pad(0, a.hash, FrameRange(0, 100)) else Pad(it) }.frozen(),
            patterns = frozenListOf(Pattern("pattern-1", notes = frozenListOf(Note(0, 0)))),
        )
        val timeline = compiler.compile(p, PlaybackTarget.Arrangement(), 1)
        assertNull(timeline.pattern)
        assertNotNull(timeline.pad(0))
        assertSame(timeline.pad(0)!!.asset, timeline.arrangement!!.clip(0).asset)
        assertEquals(1, loader.loaded.size)
        val oldDefault = compiler.compile(p, "pattern-1", 2)
        assertNotNull(oldDefault.pattern); assertNull(oldDefault.arrangement)
        val empty = compiler.compile(p.copy(clips = frozenListOf(), tracks = frozenListOf()), PlaybackTarget.Arrangement(), 3)
        assertNotNull(empty.arrangement); assertNull(empty.pattern)
        assertEquals(0, empty.arrangement!!.clipCount)
    }

    @Test fun frameAnchorsRemainExactAndTickAnchorsFollowTempoAcrossMixedRates() = runTest {
        val a = asset(rate = 44_100, frames = 441)
        val p = project(a, listOf(
            Clip("frame", "t", a.hash, FrameRange(44, 88), startTick = 960, timelineStartFrame = 7),
            Clip("tick", "t", a.hash, FrameRange(0, 441), startTick = 960),
        ))
        val compiler = ProgramCompiler(Loader())
        val first = compiler.compile(p, PlaybackTarget.Arrangement(), 1).arrangement!!
        val fast = compiler.compile(p.copy(tempo = Tempo(240_000)), PlaybackTarget.Arrangement(), 2).arrangement!!
        assertEquals(7L, first.clip(0).timelineStartFrame); assertEquals(7L, fast.clip(0).timelineStartFrame)
        assertEquals(48, first.clip(0).sourceStartFrame); assertEquals(96, first.clip(0).sourceEndFrame)
        assertEquals(24_000L, first.clip(1).timelineStartFrame); assertEquals(12_000L, fast.clip(1).timelineStartFrame)
        assertEquals(480, first.clip(1).sourceEndFrame)
        assertEquals(19_575L, ProgramCompiler.tickToFrame(960, 147_125))
    }

    @Test fun mixStateKeepsSilentTailAndCombinesClipAndTrackGainPan() = runTest {
        val a = asset(); val loader = Loader(); val compiler = ProgramCompiler(loader)
        val tracks = listOf(Track("a", "Audible", TrackKind.SOURCE, gain = 0.5f, pan = 0.3f, solo = true), Track("b", "Inactive", TrackKind.SOURCE))
        val p = project(a, listOf(
            Clip("active", "a", a.hash, FrameRange(0, 100), timelineStartFrame = 5, gain = 0.5f, pan = 0.8f),
            Clip("late", "b", a.hash, FrameRange(0, 100), timelineStartFrame = 1000),
        ), tracks)
        val graph = compiler.compile(p, PlaybackTarget.Arrangement(), 1).arrangement!!
        assertEquals(1, graph.clipCount); assertEquals(1100L, graph.durationFrames)
        assertEquals(0.25f, graph.clip(0).gain); assertEquals(1f, graph.clip(0).pan)
        val silentLoader = Loader()
        val silent = ProgramCompiler(silentLoader).compile(p.copy(tracks = tracks.map { it.copy(mute = true) }.frozen()), PlaybackTarget.Arrangement(), 2).arrangement!!
        assertEquals(0, silent.clipCount); assertEquals(1100L, silent.durationFrames); assertTrue(silentLoader.loaded.isEmpty())
        assertFailsWith<IllegalArgumentException> { compiler.compile(p.copy(clips = p.clips.map { it.copy(gain = 8f) }.frozen(), tracks = tracks.map { it.copy(gain = 8f) }.frozen()), PlaybackTarget.Arrangement(), 3) }
    }

    @Test fun takesRequireExplicitIdsAndCompensationTrimsOrDelaysWithoutMutation() = runTest {
        val a = asset(rate = 44_100, frames = 441)
        val takes = listOf(
            Take("early", "t", a.hash, FrameRange(0, 441), 5, 10),
            Take("late", "t", a.hash, FrameRange(0, 441), 8, -7),
            Take("before", "t", a.hash, FrameRange(0, 441), 0, 480),
        )
        val p = project(a, takes = takes); val compiler = ProgramCompiler(Loader())
        assertEquals(0, compiler.compile(p, PlaybackTarget.Arrangement(), 0).arrangement!!.clipCount)
        val graph = compiler.compile(p, PlaybackTarget.Arrangement(frozenListOf("early", "late", "before")), 1).arrangement!!
        assertEquals(2, graph.clipCount)
        assertEquals(0L, graph.clip(0).timelineStartFrame); assertEquals(5, graph.clip(0).sourceStartFrame)
        assertEquals(15L, graph.clip(1).timelineStartFrame); assertEquals(0, graph.clip(1).sourceStartFrame)
        assertEquals(495L, graph.durationFrames)
        assertEquals(takes, p.takes)
        assertFailsWith<IllegalArgumentException> { compiler.compile(p, PlaybackTarget.Arrangement(frozenListOf("missing")), 2) }
    }

    @Test fun overloadAndPadPlusTimelineResidentBudgetRejectBeforeDecode() = runTest {
        val a = asset(); val loader = Loader(); val compiler = ProgramCompiler(loader)
        val overlapping = project(a, (0..32).map { Clip("c$it", "t", a.hash, FrameRange(0, 100)) })
        assertFailsWith<IllegalArgumentException> { compiler.compile(overlapping, PlaybackTarget.Arrangement(), 1) }
        assertTrue(loader.loaded.isEmpty())
        val manyTracks = (0..16).map { Track("t$it", "Track $it", TrackKind.SOURCE) }
        assertFailsWith<IllegalArgumentException> { compiler.compile(project(a, manyTracks.mapIndexed { i, t -> Clip("c$i", t.id, a.hash, FrameRange(0, 100), timelineStartFrame = i * 100L) }, manyTracks), PlaybackTarget.Arrangement(), 2) }
        val largeA = asset("a", frames = 9_000_000); val largeB = asset("b", frames = 9_000_000)
        val large = Project(assets = frozenListOf(largeA, largeB), tracks = frozenListOf(Track("t", "Track", TrackKind.SOURCE)),
            clips = frozenListOf(Clip("c", "t", largeB.hash, FrameRange(0, 1))),
            pads = (0..127).map { if (it == 0) Pad(0, largeA.hash, FrameRange(0, 1)) else Pad(it) }.frozen())
        assertFailsWith<IllegalArgumentException> { compiler.compile(large, PlaybackTarget.Arrangement(), 3) }
        assertFailsWith<IllegalArgumentException> { compiler.compile(project(a, (0..1024).map { Clip("c$it", "t", a.hash, FrameRange(0, 1), timelineStartFrame = it * 2L) }), PlaybackTarget.Arrangement(), 4) }
        assertFailsWith<IllegalArgumentException> { compiler.compile(project(a, listOf(Clip("end", "t", a.hash, FrameRange(0, 1), timelineStartFrame = ProjectLimits.MAX_TIMELINE_FRAMES))), PlaybackTarget.Arrangement(), 5) }
        assertTrue(loader.loaded.isEmpty())
    }

    @Test fun adjacentMixedRateRangesShareExactlyOneBoundary() = runTest {
        val a = asset(rate = 44_100, frames = 441)
        val boundary = ProgramCompiler.sourceFrameTo48k(220, a.sampleRate)
        val p = project(a, listOf(Clip("left", "t", a.hash, FrameRange(0, 220), timelineStartFrame = 0),
            Clip("right", "t", a.hash, FrameRange(220, 441), timelineStartFrame = boundary)))
        val graph = ProgramCompiler(Loader()).compile(p, PlaybackTarget.Arrangement(), 0).arrangement!!
        assertEquals(graph.clip(0).sourceEndFrame, graph.clip(1).sourceStartFrame)
        assertEquals(480L, graph.durationFrames)
        assertSame(graph.clip(0).asset, graph.clip(1).asset)
    }

    @Test fun subFrameNativeCutsRemainZeroLengthWithoutInventingAnOutputSample() = runTest {
        val a = asset(rate = 192_000, frames = 8); val loader = Loader()
        val graph = ProgramCompiler(loader).compile(project(a, listOf(Clip("tiny", "t", a.hash, FrameRange(1, 2), timelineStartFrame = 10))), PlaybackTarget.Arrangement(), 0).arrangement!!
        assertEquals(0, graph.clipCount); assertEquals(10L, graph.durationFrames)
        assertTrue(loader.loaded.isEmpty())
    }
}
