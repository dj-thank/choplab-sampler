package com.choplab.core.separation

import com.choplab.core.edit.*
import com.choplab.core.model.*
import kotlin.test.*

class FourStemPlacementTest {
    private val original = Asset("a".repeat(64), "wav", 444, 44_100, 2, 50, "Original")
    private val stems = StemPart.entries.mapIndexed { index, part ->
        SeparatedStem(part, Asset(('b' + index).toString().repeat(64), "wav", 444, 44_100, 2, 50, part.name, AssetRole.RENDERED, derivedFrom = original.hash))
    }.frozen()
    private val prepared = PreparedFourStems(original.hash, "f".repeat(64), stems)
    private val project = Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, 50), pitchSemitones = 2.0),
        lyrics = frozenListOf(LyricLine("line", "A line", 0, 960)))

    @Test fun allAcapellaAndInstrumentalRetainAllFourHeadsAndTheOriginalInOneEdit() {
        for (mix in StemMix.entries) {
            val edit = assertIs<SeparationResult.Success<Intent.SetArrangement>>(prepared.placement(project, 1920, mix, "split")).value
            assertEquals(4, edit.tracks.size); assertEquals(4, edit.clips.size); assertEquals(4, edit.assets.size)
            assertTrue(edit.tracks.all { it.kind == TrackKind.STEM })
            assertEquals(when (mix) { StemMix.ALL -> 4; StemMix.ACAPELLA -> 1; StemMix.INSTRUMENTAL -> 3 }, edit.tracks.count { !it.mute })
            assertEquals(StemPart.entries.map { it.name.lowercase() }, edit.tracks.map { it.name })
            assertTrue(edit.clips.all { it.startTick == 1920L && it.timelineStartFrame == null && it.range == FrameRange(0, 50) })
            val after = Reducer.reduce(project, edit).project
            assertEquals(project.source, after.source); assertEquals(project.lyrics, after.lyrics)
            assertEquals(5, after.assets.size); assertEquals(original, after.asset(original.hash))
        }
    }

    @Test fun aChangedSourceOrMalformedSetCannotBePlaced() {
        assertEquals(SeparationProblem.STALE_DOCUMENT,
            assertIs<SeparationResult.Failure>(prepared.placement(project.copy(source = null), 0, StemMix.ALL, "split")).failure.problem)
        assertIs<SeparationResult.Failure>(prepared.placement(project, -1, StemMix.ALL, "split"))
        assertFailsWith<IllegalArgumentException> { PreparedFourStems(original.hash, "f".repeat(64), stems.reversed().frozen()) }
        assertFailsWith<IllegalArgumentException> { PreparedFourStems(original.hash, "f".repeat(64), stems.mapIndexed { index, stem ->
            if (index == 2) stem.copy(asset = stem.asset.copy(frames = 49)) else stem }.frozen()) }
    }
}
