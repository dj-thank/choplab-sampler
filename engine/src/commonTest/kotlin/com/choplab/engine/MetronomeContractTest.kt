package com.choplab.engine

import kotlin.math.abs
import kotlin.test.*

class MetronomeContractTest {
    private fun program(tempo: Tempo = Tempo()) = EngineProgram(emptyList(), tempo = tempo,
        arrangement = Arrangement(emptyList(), 14_400_000))
    private fun EngineCore.offer(command: EngineCommand) = assertEquals(OfferResult.ACCEPTED, controls.offer(command))
    private fun EngineCore.renderFrames(count: Int) = FloatArray(count * 2).also { render(it) }
    private fun EngineCore.eventFor(id: Long): EngineEventType? {
        val event = MutableEngineEvent()
        var result: EngineEventType? = null
        while (events.poll(event)) if (event.orderId == id) result = event.type
        return result
    }

    @Test fun countInHasExactQuarterBeatsAndResumesWithoutAdvancingTheSongOrSwingingTheClick() {
        for (bpm in listOf(40_000, 123_457, 240_000)) for (bars in 0..2) {
            val engine = EngineCore(program(Tempo(bpm, 667)))
            engine.offer(EngineCommand.Seek(0, 0, 12_345))
            engine.offer(EngineCommand.CountInAndResume(0, 1, bars))
            val end = (bars * 4 * 2_880_000_000L + bpm - 1) / bpm
            val samples = engine.renderFrames(end.toInt() + 1)
            assertEquals(end, engine.recordingStartFrame)
            assertEquals(12_345L, engine.recordingStartSequenceFrame)
            assertEquals(12_346L, engine.sequenceFrame, "Only the cue frame advanced the song: $bpm/$bars")
            assertEquals(0, engine.countInBeatsRemaining)
            assertTrue(engine.sequencePlaying)
            assertEquals(0, engine.activeVoiceCount, "Count-in-only voice returns its slot")
            val heard = ArrayList<Int>()
            var previous = -2
            for (frame in samples.indices step 2) if (abs(samples[frame]) > 1e-7f) {
                val at = frame / 2
                if (at - previous > 2) heard += at
                previous = at
            }
            assertEquals(bars * 4, heard.size)
            for (beat in heard.indices) {
                val expected = (beat * 2_880_000_000L + bpm - 1) / bpm + engine.latencyFrames + 1
                assertEquals(expected, heard[beat].toLong(), "Quarter-note click, independent of swing")
            }
        }
    }

    @Test fun clickResumesAtTheNextMusicalBeatAndNeverEntersExportOrProgram() {
        val program = program()
        val engine = EngineCore(program)
        engine.offer(EngineCommand.SetMetronome(0, 0, true))
        engine.offer(EngineCommand.Seek(0, 1, 12_000))
        engine.offer(EngineCommand.Resume(0, 2))
        assertTrue(engine.renderFrames(12_000).all { it == 0f })
        assertTrue(engine.renderFrames(1_100).any { it != 0f })
        engine.offer(EngineCommand.Pause(engine.frame, 3))
        assertTrue(engine.renderFrames(2_000).all { it == 0f })
        assertEquals(0, engine.activeVoiceCount)
        assertTrue(engine.metronomeEnabled, "Pause retains the preference, not a sounding/reserved voice")
        val exporter = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
        assertEquals(OfferResult.MONITOR_DISABLED, exporter.controls.offer(EngineCommand.SetMetronome(0, 0, true)))
        assertEquals(OfferResult.MONITOR_DISABLED, exporter.controls.offer(EngineCommand.CountInAndResume(0, 1, 2)))
        exporter.offer(EngineCommand.StartSequence(0, 2))
        assertTrue(exporter.renderFrames(48_000).all { it == 0f })
        assertEquals(0, program.arrangement!!.clipCount)
    }

    @Test fun everyTransportChangeCancelsTheCueAndReturnsItsReservation() {
        val changes: List<(Long, Long, EngineProgram) -> EngineCommand> = listOf(
            { f, id, _ -> EngineCommand.Stop(f, id) },
            { f, id, _ -> EngineCommand.StopAll(f, id) },
            { f, id, _ -> EngineCommand.Panic(f, id) },
            { f, id, _ -> EngineCommand.Pause(f, id) },
            { f, id, _ -> EngineCommand.Seek(f, id, 222) },
            { f, id, p -> EngineCommand.SwapProgram(f, id, p) },
            { f, id, _ -> EngineCommand.SetTempo(f, id, Tempo(60_000)) },
        )
        for (change in changes) {
            val program = program(Tempo(240_000))
            val engine = EngineCore(program)
            engine.offer(EngineCommand.CountInAndResume(0, 0, 1))
            engine.renderFrames(100)
            assertEquals(1, engine.activeVoiceCount)
            engine.offer(change(engine.frame, 1, program))
            val output = engine.renderFrames(60_000)
            assertEquals(-1, engine.recordingStartFrame)
            assertEquals(0, engine.countInBeatsRemaining)
            assertEquals(0, engine.activeVoiceCount)
            assertFalse(engine.sequencePlaying, "A cancelled cue cannot resume later")
            assertTrue(output.drop(EngineCore.STOP_TAIL_FRAMES * 2).all { it == 0f })
        }
    }

    @Test fun fullQueueStopInvalidatesFutureCueAndClickSharesTheHandPadAndFadeBudget() {
        val queued = EngineCore(program(), EngineConfig(controlCapacity = 4))
        for (id in 0L..3) queued.offer(EngineCommand.CountInAndResume(100_000 + id, id, 1))
        assertEquals(OfferResult.FULL, queued.controls.offer(EngineCommand.CountInAndResume(100_004, 4, 1)))
        queued.offer(EngineCommand.Stop(0, 4))
        queued.renderFrames(200_000)
        assertEquals(-1, queued.recordingStartFrame)
        assertEquals(EngineEventType.INVALIDATED, queued.eventFor(3))

        val asset = PcmAsset.fromMono(FloatArray(8_192) { .001f })
        val pads = (0..31).map { Pad(it, asset, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val engine = EngineCore(EngineProgram(pads, arrangement = Arrangement(emptyList(), 14_400_000)))
        var id = 0L
        engine.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(asset, loop = true)))
        engine.offer(EngineCommand.PlayOriginalSource(0, id++))
        engine.offer(EngineCommand.ScratchOriginalStart(0, id++, 100.0, 0, 8_192))
        for (pad in 0..30) engine.offer(EngineCommand.Trigger(0, id++, pad))
        repeat(16) { engine.offer(EngineCommand.Trigger(0, id++, 0)) }
        val refused = id++
        engine.offer(EngineCommand.CountInAndResume(0, refused, 1))
        engine.renderFrames(1)
        assertEquals(EngineEventType.VOICE_LIMIT, engine.eventFor(refused))
        assertEquals(-1, engine.recordingStartFrame, "A silent, refused count-in is not a successful recording start")
        assertEquals(32, engine.activeVoiceCount)
        engine.renderFrames(200) // Steal tails have ended; one slot can now move to click.
        engine.offer(EngineCommand.CountInAndResume(engine.frame, id++, 1))
        engine.renderFrames(1)
        assertEquals(32, engine.activeVoiceCount, "30 PAD + HAND + click")
        assertTrue(engine.originalPlaying)
        assertEquals(100.0, engine.handSourceFrame)
        engine.offer(EngineCommand.Pause(engine.frame, id))
        engine.renderFrames(200)
        assertEquals(31, engine.activeVoiceCount, "Cancellation returns the click slot and preserves live PAD/HAND")
        assertTrue(engine.originalPlaying)
    }
}
