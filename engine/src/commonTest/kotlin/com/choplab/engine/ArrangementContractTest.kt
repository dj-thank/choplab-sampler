package com.choplab.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrangementContractTest {
    private fun source(frames: Int = 4096, level: Float? = null): PcmAsset = PcmAsset.fromInterleaved(FloatArray(frames * 2) { i ->
        val value = level ?: (((i / 2) % 1000 + 1) * .0001f)
        if (i % 2 == 0) value else -value / 2
    })

    @Test fun validatesImmutableClipsBoundsAndExplicitSilenceWithoutPadCoercion() {
        val pcm = source()
        val clips = mutableListOf(ArrangementClip("later", pcm, 13, 20, 40, trackIndex = 15),
            ArrangementClip("first", pcm, 3, 0, 5))
        val arrangement = Arrangement(clips, 100)
        clips.clear()
        assertEquals(2, arrangement.clipCount)
        assertEquals("first", arrangement.clip(0).id)
        assertEquals(100L, arrangement.durationFrames)
        val program = EngineProgram(listOf(Pad(0, pcm)), arrangement = arrangement)
        assertEquals(pcm.residentBytes, program.residentBytes)
        assertFailsWith<IllegalArgumentException> { Arrangement(listOf(arrangement.clip(0)), 7) }
        assertFailsWith<IllegalArgumentException> { Arrangement(listOf(arrangement.clip(0), arrangement.clip(0))) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, 0, 0, 4097) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, -1) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, 0, gain = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, 0, pan = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, 0, trackIndex = 16) }
        assertFailsWith<IllegalArgumentException> { ArrangementClip("x", pcm, Arrangement.MAX_DURATION_FRAMES) }
        assertFailsWith<IllegalArgumentException> { Arrangement(emptyList(), Arrangement.MAX_DURATION_FRAMES + 1) }
        assertEquals(0, Arrangement(emptyList(), 10).clipCount)
    }

    @Test fun supports1024IndependentPlacementsAndRejectsOnlyExcessCountOrOverlap() {
        val pcm = source(1, .1f)
        val clips = (0 until 1024).map { ArrangementClip("clip-$it", pcm, it.toLong(), trackIndex = it % 16) }
        val arrangement = Arrangement(clips)
        assertEquals(1024, arrangement.clipCount)
        assertEquals(1, arrangement.maximumOverlap)
        val result = OfflineRender.render(EngineProgram(arrangement = arrangement),
            listOf(EngineCommand.StartSequence(0, 0)), 1030)
        for (i in 0 until 1024) assertEquals(.1f, result[i * 2])
        assertTrue(result.drop(1024 * 2).all { it == 0f })
        assertFailsWith<IllegalArgumentException> { Arrangement(clips + ArrangementClip("too-many", pcm, 1024)) }
        val overlap = (0 until 32).map { ArrangementClip("overlap-$it", pcm, 0, trackIndex = it % 16) }
        assertEquals(32, Arrangement(overlap).maximumOverlap)
        assertFailsWith<IllegalArgumentException> { Arrangement(overlap + ArrangementClip("33rd", pcm, 0)) }
        // Touching [start,end) edges are not an overlap.
        assertEquals(32, Arrangement(overlap + overlap.map { ArrangementClip("next-${it.id}", pcm, 1) }).maximumOverlap)
    }

    @Test fun overlapUsesExactSourceRangesPanGainAndUnquantizedTimelineFrames() {
        val pcm = source()
        val arrangement = Arrangement(listOf(
            ArrangementClip("a", pcm, 3, 100, 120, gain = .5f),
            ArrangementClip("b", pcm, 11, 500, 530, pan = 1f, trackIndex = 1)), 50)
        val result = OfflineRender.render(EngineProgram(arrangement = arrangement),
            listOf(EngineCommand.StartSequence(0, 0)), 50)
        for (frame in 0 until 50) {
            val a = if (frame in 3 until 23) pcm.sample(100 + frame - 3, 0) * .5f else 0f
            val b = if (frame in 11 until 41) pcm.sample(500 + frame - 11, 1) else 0f
            assertTrue(abs(result[frame * 2] - a) < 1e-7, "left at $frame")
            assertTrue(abs(result[frame * 2 + 1] - (-a / 2 + b)) < 1e-7, "right at $frame")
        }
    }

    @Test fun seekRestoresEveryMidClipAtExactSourcePositionAndClearsLookahead() {
        val pcm = source()
        val program = EngineProgram(arrangement = Arrangement(listOf(
            ArrangementClip("one", pcm, 10, 200, 400, gain = .3f),
            ArrangementClip("two", pcm, 50, 500, 700, gain = .2f, trackIndex = 1)), 400))
        val continuous = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 0)), 400)
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.render(FloatArray(240))
        engine.controls.offer(EngineCommand.Seek(120, 1, 75))
        val output = FloatArray((150 + 72) * 2)
        engine.render(output)
        assertTrue(output.take(72 * 2).all { it == 0f })
        assertContentEquals(continuous.copyOfRange(75 * 2, 225 * 2), output.copyOfRange(72 * 2, output.size))
        assertEquals(297L, engine.sequenceFrame)
        assertEquals(342L, engine.frame)
    }

    @Test fun pauseFreezesCursorAndResumeStartsAtTheSameSample() {
        val pcm = source()
        val engine = EngineCore(EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("a", pcm, 0)))))
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.render(FloatArray(400))
        engine.controls.offer(EngineCommand.Pause(200, 1))
        val paused = FloatArray(200)
        engine.render(paused)
        assertTrue(paused.all { it == 0f })
        assertEquals(200L, engine.sequenceFrame)
        assertTrue(engine.sequencePaused)
        engine.controls.offer(EngineCommand.Resume(300, 2))
        val resumed = FloatArray(200)
        engine.render(resumed)
        assertTrue(resumed.take(72 * 2).all { it == 0f })
        for (i in 0 until 28) assertEquals(pcm.sample(200 + i, 0), resumed[(72 + i) * 2])
        assertEquals(300L, engine.sequenceFrame)
        assertFalse(engine.sequencePaused)
    }

    @Test fun declaredEndStopsAtItsFrameAndMasterFlushesExactly72Frames() {
        val pcm = source(200, .2f)
        val engine = EngineCore(EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("a", pcm, 0)), 200)))
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        val output = FloatArray(600)
        engine.render(output)
        assertEquals(200L, engine.sequenceFrame)
        assertFalse(engine.sequencePlaying)
        assertEquals(0, engine.activeClipCount)
        for (i in 0 until 300) assertEquals(if (i in 72 until 272) .2f else 0f, output[i * 2])
        engine.controls.offer(EngineCommand.Stop(300, 1))
        engine.render(FloatArray(2))
        assertEquals(0L, engine.sequenceFrame)
        assertEquals(0L, engine.tickNumerator)
        val snapshot = EngineSnapshot()
        assertTrue(engine.readout.copyInto(snapshot))
        assertEquals(0L, snapshot.sequenceFrame)
    }

    @Test fun emptyAndMutedArrangementsPreserveSilenceAndNeverFallbackToPattern() {
        val pcm = source(1, .2f)
        val pattern = Pattern(960, listOf(SequenceNote(0, 0)))
        val engine = EngineCore(EngineProgram(listOf(Pad(0, pcm)), pattern = pattern,
            arrangement = Arrangement(emptyList(), 200)))
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        val result = FloatArray(398)
        engine.render(result)
        assertTrue(result.all { it == 0f })
        assertTrue(engine.sequencePlaying)
        assertEquals(199L, engine.sequenceFrame)
        engine.render(FloatArray(2))
        assertFalse(engine.sequencePlaying)
        assertEquals(200L, engine.sequenceFrame)
        val empty = EngineCore(EngineProgram(listOf(Pad(0, pcm)), pattern = pattern, arrangement = Arrangement(emptyList())))
        empty.controls.offer(EngineCommand.StartSequence(0, 0))
        empty.render(result)
        assertTrue(result.all { it == 0f })
        assertFalse(empty.sequencePlaying)
        assertEquals(0L, empty.sequenceFrame)
    }

    @Test fun swapRebuildsActiveClipsPreservesPositionAndClampsShortenedEnd() {
        val a = source(1000, .1f)
        val b = source(1000, .2f)
        val first = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("old", a, 0)), 1000), revision = 1)
        val next = EngineProgram(arrangement = Arrangement(listOf(
            ArrangementClip("new1", b, 0, 0, 1000, gain = .5f),
            ArrangementClip("new2", b, 150, 0, 600, gain = .75f)), 1000), revision = 2)
        val engine = EngineCore(first)
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.render(FloatArray(400))
        engine.controls.offer(EngineCommand.SwapProgram(200, 1, next))
        val output = FloatArray(400)
        engine.render(output)
        assertEquals(400L, engine.sequenceFrame)
        assertEquals(2, engine.activeClipCount)
        assertEquals(b.residentBytes, engine.residentBytes)
        for (i in 0 until 200) assertTrue(abs(output[i * 2] - if (i < 72) .1f else .25f) < 1e-7)
        val shortened = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("short", b, 0, 0, 100)), 100), revision = 3)
        engine.controls.offer(EngineCommand.SwapProgram(400, 2, shortened))
        val tail = FloatArray(400)
        engine.render(tail)
        assertEquals(100L, engine.sequenceFrame)
        assertFalse(engine.sequencePlaying)
        assertTrue(tail.drop(72 * 2).all { it == 0f })
    }

    @Test fun pauseAndSeekFlushSharedLimiterWhileManualPadCursorContinues() {
        val pcm = source(512, .2f)
        val program = EngineProgram(listOf(Pad(0, pcm, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0)),
            arrangement = Arrangement(listOf(ArrangementClip("clip", pcm, 0, gain = .5f))))
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.Trigger(0, 0, 0))
        engine.controls.offer(EngineCommand.StartSequence(0, 1))
        engine.render(FloatArray(200))
        engine.controls.offer(EngineCommand.Seek(100, 2, 50))
        val sought = FloatArray(144)
        engine.render(sought)
        assertTrue(sought.all { it == 0f })
        assertEquals(1, engine.activeVoiceCount)
        assertEquals(122L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.Pause(172, 3))
        val paused = FloatArray(200)
        engine.render(paused)
        assertTrue(paused.take(144).all { it == 0f })
        for (i in 72 until 100) assertEquals(.2f, paused[i * 2])
        assertEquals(122L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.Resume(272, 4))
        val resumed = FloatArray(200)
        engine.render(resumed)
        for (i in 0 until 100) assertTrue(abs(resumed[i * 2] - if (i < 72) .2f else .3f) < 1e-7)
    }

    @Test fun partitionIdentityIncludesMidClipSeekPauseResumeSwapAndAutomaticEnd() {
        val pcm = source()
        val first = EngineProgram(arrangement = Arrangement(listOf(
            ArrangementClip("a", pcm, 5, 100, 2100, gain = .4f),
            ArrangementClip("b", pcm, 27, 400, 1400, gain = .3f, trackIndex = 1)), 2200))
        val next = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("c", pcm, 100, 0, 1400)), 1900))
        val commands = listOf(EngineCommand.StartSequence(0, 0), EngineCommand.Seek(173, 1, 301),
            EngineCommand.Pause(307, 2), EngineCommand.Seek(399, 3, 507), EngineCommand.Resume(483, 4),
            EngineCommand.SwapProgram(917, 5, next))
        val expected = OfflineRender.render(first, commands, 2600, blockFrames = 1)
        for (block in intArrayOf(17, 96, 192, 480, 4096)) {
            assertContentEquals(expected, OfflineRender.render(first, commands, 2600, blockFrames = block), "block=$block")
        }
    }

    @Test fun patternPauseResumesItsVoiceAndArrangementSeekIsRejectedWithoutChangingPattern() {
        val pcm = source()
        val program = EngineProgram(listOf(Pad(0, pcm, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0)),
            Pattern(960, listOf(SequenceNote(0, 0))))
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.render(FloatArray(200))
        engine.controls.offer(EngineCommand.Pause(100, 1))
        engine.render(FloatArray(200))
        assertEquals(100L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.Resume(200, 2))
        val resumed = FloatArray(200)
        engine.render(resumed)
        assertEquals(pcm.sample(100, 0), resumed[72 * 2])
        engine.controls.offer(EngineCommand.Seek(300, 3, 1000))
        engine.render(FloatArray(2))
        assertEquals(201L, engine.sequenceFrame)
        val event = MutableEngineEvent()
        var rejected = false
        while (engine.events.poll(event)) if (event.orderId == 3L) rejected = event.type == EngineEventType.INVALID_COMMAND
        assertTrue(rejected)
    }

    @Test fun outOfRangeSeekAndCombinedPcmBudgetFailWithoutReplacingTheGraph() {
        val pcm = source(16)
        val other = source(16)
        val program = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("a", pcm, 0))))
        val combined = EngineProgram(listOf(Pad(0, other)), arrangement = program.arrangement)
        assertEquals(pcm.residentBytes * 2, combined.residentBytes)
        assertFailsWith<IllegalArgumentException> { EngineCore(combined, EngineConfig(residentByteLimit = pcm.residentBytes)) }
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.Seek(0, 0, 17))
        engine.render(FloatArray(2))
        assertEquals(0L, engine.sequenceFrame)
        val event = MutableEngineEvent()
        assertTrue(engine.events.poll(event))
        assertEquals(EngineEventType.INVALID_COMMAND, event.type)
    }
}
