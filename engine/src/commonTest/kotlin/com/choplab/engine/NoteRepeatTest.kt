package com.choplab.engine

import kotlin.math.sin
import kotlin.test.*

class NoteRepeatTest {
    @Test fun oneHourOfStraightTripletAndSwingPulsesMatchesTheRationalFrameOracle() {
        for (bpm in listOf(40_000, 97_125, 240_000)) for (swing in listOf(500, 710, 750))
            for (ticks in listOf(960, 480, 320, 240, 160, 120)) {
                val clock = NoteRepeatClock().apply { reset(Tempo(bpm, swing), ticks) }
                var frame = 0L
                var tick = 0L
                while (frame < 48_000L * 3_600) {
                    val distance = clock.framesUntilPulse()
                    frame += distance
                    clock.advance(distance)
                    val target = SequenceClock.targetNumerator(tick, swing)
                    assertEquals((target + bpm - 1) / bpm, frame, "$bpm/$swing/$ticks at $tick")
                    assertTrue(clock.pulse())
                    assertFalse(clock.pulse(), "A pulse cannot fire twice without advancing audio time")
                    tick += ticks
                }
            }
    }

    @Test fun capturedPhraseMatchesTheLiveRepeatForEveryRateAndPadMode() {
        val asset = PcmAsset.fromInterleaved(FloatArray(4_800 * 2) { i ->
            (sin((i / 2) * .031) * if (i % 2 == 0) .12 else -.07).toFloat()
        })
        val tempo = Tempo(97_125, 710)
        for (mode in PlayMode.entries) for (ticks in listOf(960, 480, 320, 240, 160, 120)) {
            val pad = Pad(0, asset, 100, 4_700, mode = mode, gain = .7f, pan = .25f,
                pitchSemitones = -3.0, reverse = true, tone = .4f,
                attackFrames = 32, releaseFrames = 700, decayFrames = 120, sustainLevel = .6f)
            val release = 68_333
            val capture = NoteRepeatRender.render(pad, tempo, ticks, release, 80_000)
            assertEquals((release + 700) * 2, capture.size)
            val live = OfflineRender.render(EngineProgram(listOf(pad), tempo = tempo), listOf(
                EngineCommand.StartNoteRepeat(0, 0, 0, ticks), EngineCommand.Release(release.toLong(), 1, 0)), 80_000)
            for (i in capture.indices) assertEquals(live[i], capture[i], 1e-6f, "$mode/$ticks sample $i")
            for (i in capture.size until live.size) assertEquals(0f, live[i], 1e-6f)
        }
    }

    @Test fun finiteAccessibleBurstAndExplicitReleaseAreIdenticalAndNeverAddABoundaryPulse() {
        val pad = Pad(0, PcmAsset.fromMono(FloatArray(800) { .05f }), attackFrames = 0)
        val program = EngineProgram(listOf(pad), tempo = Tempo(120_000))
        val finite = OfflineRender.render(program, listOf(EngineCommand.StartNoteRepeat(0, 0, 0, 240, 24_000)), 48_000)
        val held = OfflineRender.render(program, listOf(EngineCommand.StartNoteRepeat(0, 0, 0, 240), EngineCommand.Release(24_000, 1, 0)), 48_000)
        assertContentEquals(held, finite)
        assertTrue(finite.take(24_000 * 2).any { it != 0f })
        assertTrue(finite.drop(24_000 * 2).all { it == 0f }, "No fifth note at the one-beat boundary")
    }

    @Test fun renderBlockSplitsAndStopDuringAReleaseKeepExactlyTheSameStereoSamples() {
        val pad = Pad(0, PcmAsset.fromInterleaved(FloatArray(48_000 * 2) { if (it % 2 == 0) .08f else -.03f }),
            mode = PlayMode.LOOP, attackFrames = 0, releaseFrames = 4_000)
        val tempo = Tempo(123_456, 710)
        val program = EngineProgram(listOf(pad), tempo = tempo)
        val commands = listOf(EngineCommand.StartNoteRepeat(17, 0, 0, 160), EngineCommand.Release(17_037, 1, 0), EngineCommand.Stop(17_200, 2))
        val reference = OfflineRender.render(program, commands, 20_000, blockFrames = 1)
        for (block in listOf(17, 96, 192, 480, 4096)) assertContentEquals(reference, OfflineRender.render(program, commands, 20_000, blockFrames = block))
        val capture = NoteRepeatRender.render(pad, tempo, 160, releaseAt = 17_020, limitFrames = 19_000, stopAt = 17_183)
        assertEquals((17_183 + 96) * 2, capture.size)
        for (i in capture.indices) assertEquals(reference[i + 34], capture[i], 1e-6f, "Captured release/stop sample $i")
    }

    @Test fun twoReservedSlotsPerHoldStayInsideTheVoiceBudgetAndSafetyStopSurvivesAFullQueue() {
        val pads = (0..16).map { Pad(it, PcmAsset.fromMono(FloatArray(32) { .005f }), attackFrames = 0) }
        val engine = EngineCore(EngineProgram(pads), EngineConfig(controlCapacity = 2, eventCapacity = 128))
        try {
            val block = FloatArray(192 * 2)
            var id = 0L
            repeat(16) { index ->
                assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StartNoteRepeat(engine.frame, id++, index, 120)))
                engine.render(block)
            }
            assertEquals(32, engine.activeVoiceCount, "Silent gaps retain their two bounded primary slots")
            engine.controls.offer(EngineCommand.Trigger(engine.frame, id++, 16)); engine.render(block)
            assertEquals(1, engine.rejectedVoices)
            assertEquals(32, engine.activeVoiceCount)
            repeat(2) { assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Trigger(engine.frame + 48_000, id++, 16))) }
            assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Stop(engine.frame, id++)))
            engine.render(block)
            assertEquals(0, engine.activeVoiceCount)
            repeat(300) { engine.render(block); assertTrue(block.all { it == 0f }) }
            assertEquals(0, engine.fadeVoiceCount)
        } finally { engine.close() }
    }

    @Test fun repeatedPadRetriggerAtTheFullVoiceBudgetKeepsTheRecordedTransition() {
        val sound = PcmAsset.fromMono(FloatArray(8_000) { (.04 * sin(it * .05)).toFloat() })
        val silent = PcmAsset.fromMono(FloatArray(8_000))
        val pads = (0 until 16).map { Pad(it, if (it == 0) sound else silent, mode = PlayMode.LOOP,
            attackFrames = 0, releaseFrames = 4_000) }
        val tempo = Tempo(120_000)
        val commands = (0 until 16).map { EngineCommand.StartNoteRepeat(0, it.toLong(), it, 160) } + listOf(
            EngineCommand.StartNoteRepeat(5_000, 16, 0, 160), EngineCommand.Release(12_000, 17, 0))
        val live = OfflineRender.render(EngineProgram(pads, tempo = tempo), commands, 16_000)
        val first = NoteRepeatRender.render(pads[0], tempo, 160, 5_000, 16_000, stopAt = 5_000)
        val second = NoteRepeatRender.render(pads[0], tempo, 160, 7_000, 11_000)
        assertEquals(5_096 * 2, first.size)
        for (sample in live.indices) {
            val a = first.getOrNull(sample) ?: 0f
            val b = second.getOrNull(sample - 10_000) ?: 0f
            assertEquals(a + b, live[sample], 1e-6f, "Full-budget retrigger sample $sample")
        }
    }

    @Test fun programTempoPauseAndChokeChangesCannotLeaveARepeatRunning() {
        val asset = PcmAsset.fromMono(FloatArray(48_000) { .03f })
        val pad = Pad(0, asset, mode = PlayMode.LOOP, chokeGroup = 1)
        val silence = Pad(1, PcmAsset.fromMono(FloatArray(512)), chokeGroup = 1)
        val program = EngineProgram(listOf(pad, silence))
        for (end in listOf<EngineCommand>(EngineCommand.Pause(3_000, 1), EngineCommand.SetTempo(3_000, 1, Tempo(97_125)),
            EngineCommand.SwapProgram(3_000, 1, program), EngineCommand.Trigger(3_000, 1, 1))) {
            val output = OfflineRender.render(program, listOf(EngineCommand.StartNoteRepeat(0, 0, 0, 120), end), 48_000)
            assertTrue(output.take(3_000 * 2).any { it != 0f })
            assertTrue(output.drop(4_000 * 2).all { it == 0f }, "${end::class} keeps the held repeat stopped")
        }
    }

    @Test fun refusingARetriggerWithFullFadeSlotsKeepsTheEarlierHoldAlive() {
        val sound = PcmAsset.fromMono(FloatArray(800) { .02f })
        val silent = PcmAsset.fromMono(FloatArray(800))
        val pads = (0 until 16).map { Pad(it, if (it == 0) sound else silent, attackFrames = 0) }
        val engine = EngineCore(EngineProgram(pads), EngineConfig(controlCapacity = 128, eventCapacity = 128))
        try {
            var order = 0L
            repeat(16) { engine.controls.offer(EngineCommand.StartNoteRepeat(0, order++, it, 240)) }
            repeat(17) { engine.controls.offer(EngineCommand.StartNoteRepeat(0, order++, 0, 240)) }
            val output = FloatArray(7_000 * 2)
            engine.render(output)
            assertEquals(1, engine.rejectedVoices)
            assertEquals(32, engine.activeVoiceCount)
            assertTrue(output.drop(6_000 * 2).any { it > 0f }, "The refused newest press keeps the previous held clock")
        } finally { engine.close() }
    }
}
