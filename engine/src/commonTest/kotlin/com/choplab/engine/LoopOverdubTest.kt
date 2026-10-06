package com.choplab.engine

import kotlin.math.sin
import kotlin.test.*

class LoopOverdubTest {
    private val sound = PcmAsset.fromInterleaved(FloatArray(8_000 * 2) { (sin(it / 2 * .05) * if (it % 2 == 0) .08 else -.025).toFloat() })
    private val pad = Pad(0, sound, mode = PlayMode.GATE, releaseFrames = 360, reverse = true, pitchSemitones = -3.0, tone = .4f)

    @Test fun quantizedForwardAndBackwardPressesFirstJoinTheNextPassAndKeepStereo() {
        for ((press, target) in listOf(600 to 1_000, 1_400 to 1_000)) {
            val period = 8_000
            val take = LoopOverdub(0, period, intArrayOf(0, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000, 8_000))
            val result = render(take, listOf(EngineCommand.Trigger(press.toLong(), 2, 0), EngineCommand.Release(press + 700L, 3, 0)), period * 3, 192)
            val expected = PadPerformanceRender.render(pad, 700, 2_000)
            for (i in expected.indices) {
                assertEquals(expected[i], take.samples()[target * 2 + i], 1e-7f)
                assertEquals(expected[i], result[(period + target + 72) * 2 + i], 1e-7f, "Next pass at $press -> $target, $i")
            }
            assertTrue(result.sliceArray((2_600 * 2) until (period * 2)).all { it == 0f }, "No forward-snap leak into this pass")
            assertTrue(take.elapsedFrames > period * 2)
            assertEquals(1, take.acceptedPresses)
        }
    }

    @Test fun gateAndTripletRepeatAcrossTheBoundaryFoldOnlyNewAudioAndPreserveThePhraseGridShift() {
        val tempo = Tempo(240_000, 710)
        val period = 48_000
        val press = 44_777
        val release = 57_377
        for (ticks in listOf(0, 160, 320)) {
            val take = LoopOverdub(0, period, intArrayOf(0, 12_000, 24_000, 36_000, 48_000))
            val commands = listOf(if (ticks == 0) EngineCommand.Trigger(press.toLong(), 2, 0)
                else EngineCommand.StartNoteRepeat(press.toLong(), 2, 0, ticks), EngineCommand.Release(release.toLong(), 3, 0))
            val output = render(take, commands, period * 3, 480, tempo)
            val phrase = if (ticks == 0) PadPerformanceRender.render(pad, release - press, 20_000)
                else NoteRepeatRender.render(pad, tempo, ticks, release - press, 20_000)
            for (i in 0 until 1_000) {
                val live = phrase.getOrElse((period - press) * 2 + i) { 0f }
                assertEquals(live + phrase[i], output[(period + 72) * 2 + i], 1e-7f, "End snap returns at the very next loop zero: $ticks/$i")
            }
            val folded = FloatArray(period * 2)
            // This press snaps to the end boundary (the following loop's zero); its tail wraps intact.
            for (i in phrase.indices) folded[i % folded.size] += phrase[i]
            for (i in folded.indices) assertEquals(folded[i], take.samples()[i], 1e-7f, "$ticks/$i")
        }
    }

    @Test fun blockPartitionsAndQueueFullStopKeepIdenticalAudioAndOneCompletedTake() {
        fun run(block: Int): Pair<FloatArray, FloatArray> {
            val take = LoopOverdub(0, 8_000)
            val out = render(take, listOf(EngineCommand.StartNoteRepeat(17, 2, 0, 160),
                EngineCommand.Release(10_000, 3, 0)), 25_000, block)
            return out to take.samples().copyOf()
        }
        val expected = run(1)
        for (block in listOf(17, 96, 192, 480, 4096)) {
            val actual = run(block)
            assertContentEquals(expected.first, actual.first); assertContentEquals(expected.second, actual.second)
        }
        val take = LoopOverdub(0, 8_000)
        val engine = EngineCore(EngineProgram(listOf(pad), arrangement = Arrangement(emptyList(), 8_000)), EngineConfig(controlCapacity = 2))
        val block = FloatArray(384)
        engine.controls.offer(EngineCommand.StartLoopOverdub(0, 0, take)); engine.controls.offer(EngineCommand.Resume(0, 1)); engine.render(block)
        engine.controls.offer(EngineCommand.Trigger(engine.frame, 2, 0)); engine.render(block)
        engine.controls.offer(EngineCommand.Trigger(50_000, 3, 0)); engine.controls.offer(EngineCommand.Trigger(50_000, 4, 0))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Stop(engine.frame, 5)))
        engine.render(block); assertTrue(take.completed)
        repeat(300) { engine.render(block); assertTrue(block.all { it == 0f }) }
        assertEquals(0, engine.activeVoiceCount); assertEquals(1, take.acceptedPresses)
        assertTrue(take.samples().any { it != 0f }); engine.close()
    }

    @Test fun backingSourceClickAndOldVoicesNeverEnterTheTakeAndDetachPreservesAlreadyCapturedPcm() {
        val take = LoopOverdub(200, 8_000)
        val background = Arrangement(listOf(ArrangementClip("backing", sound, 200)), 8_200)
        val engine = EngineCore(EngineProgram(listOf(pad), arrangement = background))
        var id = 0L
        fun command(value: (Long, Long) -> EngineCommand) { engine.controls.offer(value(engine.frame, id++)); engine.render(FloatArray(2)) }
        command { f, i -> EngineCommand.Trigger(f, i, 0) } // already sounding before this take
        command { f, i -> EngineCommand.Seek(f, i, 200) }
        command { f, i -> EngineCommand.StartLoopOverdub(f, i, take) }
        command { f, i -> EngineCommand.SetOriginalSource(f, i, OriginalSource(sound)) }
        command { f, i -> EngineCommand.PlayOriginalSource(f, i) }
        command { f, i -> EngineCommand.SetMetronome(f, i, true) }
        command { f, i -> EngineCommand.Resume(f, i) }
        engine.render(FloatArray(18_000))
        assertTrue(engine.sequenceFrame in 200 until 8_200)
        engine.close(); assertTrue(take.completed); assertTrue(take.interrupted)
        assertEquals(0, take.acceptedPresses); assertTrue(take.samples().all { it == 0f })
    }

    @Test fun emptyCancelledCountInAndExportRefusalDoNotCreateAPerformance() {
        val take = LoopOverdub(0, 48_000)
        val program = EngineProgram(listOf(pad), tempo = Tempo(240_000), arrangement = Arrangement(emptyList(), 48_000))
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.StartLoopOverdub(0, 0, take))
        engine.controls.offer(EngineCommand.CountInAndResume(0, 1, 1))
        engine.controls.offer(EngineCommand.Trigger(100, 2, 0))
        engine.controls.offer(EngineCommand.Stop(1_000, 3))
        engine.render(FloatArray(4_000))
        assertTrue(take.completed); assertEquals(0, take.acceptedPresses); assertTrue(take.samples().all { it == 0f })
        engine.close()
        val export = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
        assertEquals(OfferResult.MONITOR_DISABLED, export.controls.offer(EngineCommand.StartLoopOverdub(0, 0, LoopOverdub(0, 48_000))))
        export.close()
    }

    @Test fun twoDryBankRoutesReturnThroughExactlyOneFaderInsertSendAndMasterWhileOtherSoundStaysOut() {
        val period = 48_000
        val a = Pad(0, sound, mode = PlayMode.GATE, gain = .7f, pan = -.2f, releaseFrames = 300,
            mixBus = 0, mixGain = .35f, mixPan = .7f)
        val b = Pad(16, sound, mode = PlayMode.GATE, gain = 1.1f, pan = .25f, reverse = true, tone = .4f,
            releaseFrames = 600, mixBus = 1, mixGain = 1.7f, mixPan = -.5f)
        val routes = listOf(LoopOverdubRoute("a", .35f, .7f), LoopOverdubRoute("b", 1.7f, -.5f))
        val take = LoopOverdub(0, period, IntArray(49) { it * 1_000 }, routes)
        val mixer = MixerProgram(listOf(
            TrackFx(MixInsert(eq = MixEq(3f, -2f, 1f), compressor = MixCompressor(true, -30f, 3f)), .3f, .2f),
            TrackFx(MixInsert(filter = MixFilter(MixFilterMode.LOW_PASS, 1800f)), .55f, .4f), TrackFx()),
            MixSettings(MixDelay(true, 113, .3f, .4f), MixReverb(true, .1f, .3f, .2f),
                MixInsert(eq = MixEq(-1f, 2f, 0f)), .8f), listOf("a", "b", "backing"))
        val background = PcmAsset.fromInterleaved(FloatArray(2_000 * 2) { if (it % 2 == 0) -.018f else .006f })
        val phrases = listOf(PadPerformanceRender.render(a, 700, 4_000), PadPerformanceRender.render(b, 1100, 4_000))
        val loops = phrases.map { phrase -> FloatArray(period * 2).also { loop -> for (i in phrase.indices) loop[1_000 * 2 + i] += phrase[i] } }
        val pcm = loops.map { PcmAsset.fromInterleaved(it) }
        fun program(reference: Boolean) = EngineProgram(listOf(a, b), tempo = Tempo(240_000), mixer = mixer,
            arrangement = Arrangement(buildList {
                repeat(if (reference) 4 else 1) { cycle -> add(ArrangementClip("backing-$cycle", background, cycle.toLong() * period, trackIndex = 2)) }
                if (reference) for (cycle in 1..3) for (route in routes.indices) add(ArrangementClip("loop-$cycle-$route", pcm[route], cycle.toLong() * period,
                    gain = routes[route].gain, pan = routes[route].pan, trackIndex = route))
            }, period.toLong() * if (reference) 4 else 1))
        fun play(reference: Boolean): FloatArray {
            val engine = EngineCore(program(reference))
            var id = 0L
            if (!reference) engine.controls.offer(EngineCommand.StartLoopOverdub(0, id++, take))
            engine.controls.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(background)))
            engine.controls.offer(EngineCommand.PlayOriginalSource(0, id++))
            engine.controls.offer(EngineCommand.ScratchOriginalStart(0, id++, 100.0, 0, background.frameCount))
            engine.controls.offer(EngineCommand.SetMetronome(0, id++, true))
            engine.controls.offer(EngineCommand.Resume(0, id++))
            engine.controls.offer(EngineCommand.Trigger(600, id++, 0))
            engine.controls.offer(EngineCommand.Release(1300, id++, 0))
            engine.controls.offer(EngineCommand.Trigger(1400, id++, 16))
            engine.controls.offer(EngineCommand.Release(2500, id++, 16))
            val out = FloatArray(period * 3 * 2)
            for (first in 0 until period * 3 step 192) engine.render(out, first, minOf(192, period * 3 - first))
            assertTrue(out.any { it != 0f })
            engine.close()
            return out
        }
        val expected = play(true)
        val actual = play(false)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 2e-7f, "Shared graph frame/channel $i")
        assertEquals(2, take.acceptedPresses)
        for (route in routes.indices) {
            assertEquals(1, take.acceptedPresses(route))
            assertContentEquals(loops[route], take.samples(route), "The stored route excludes its fader, FX, backing and monitors")
        }
    }

    @Test fun staleBankRoutingRejectsTheEntireTakeBeforeItCanCapture() {
        val routed = Pad(0, sound, mixBus = 0, mixGain = .5f)
        val engine = EngineCore(EngineProgram(listOf(routed), arrangement = Arrangement(emptyList(), 8_000),
            mixer = MixerProgram(listOf(TrackFx()), busIds = listOf("bank"))))
        val wrong = LoopOverdub(0, 8_000, routes = listOf(LoopOverdubRoute("other", .5f)))
        engine.controls.offer(EngineCommand.StartLoopOverdub(0, 0, wrong)); engine.render(FloatArray(384))
        assertEquals(0, engine.activeVoiceCount); assertEquals(0, wrong.acceptedPresses)
        val right = LoopOverdub(0, 8_000, routes = listOf(LoopOverdubRoute("bank", .5f)))
        engine.controls.offer(EngineCommand.StartLoopOverdub(engine.frame, 1, right)); engine.render(FloatArray(384))
        assertEquals(1, engine.activeVoiceCount)
        engine.close(); assertTrue(right.completed); assertFalse(wrong.completed)
        wrong.dispose()
    }

    private fun render(take: LoopOverdub, gestures: List<EngineCommand>, frames: Int, block: Int, tempo: Tempo = Tempo()): FloatArray {
        val engine = EngineCore(EngineProgram(listOf(pad), tempo = tempo, arrangement = Arrangement(emptyList(), take.endFrame)))
        val result = FloatArray((frames + 192) * 2)
        engine.controls.offer(EngineCommand.StartLoopOverdub(0, 0, take))
        engine.controls.offer(EngineCommand.Resume(0, 1))
        for (command in gestures) assertEquals(OfferResult.ACCEPTED, engine.controls.offer(command))
        engine.controls.offer(EngineCommand.Stop(frames.toLong(), gestures.last().orderId + 1))
        var first = 0
        while (first < result.size / 2) { val count = minOf(block, result.size / 2 - first); engine.render(result, first, count); first += count }
        assertTrue(take.completed)
        engine.close()
        return result
    }
}
