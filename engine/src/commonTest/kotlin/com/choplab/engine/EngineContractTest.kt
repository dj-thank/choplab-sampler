package com.choplab.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineContractTest {
    private fun asset(frames: Int = 2048) = PcmAsset.fromInterleaved(FloatArray(frames * 2) {
        if (it % 2 == 0) (0.1 * sin(2 * PI * (it / 2) / 128)).toFloat()
        else (0.06 * sin(2 * PI * (it / 2) / 71)).toFloat()
    })
    private fun constantProgram(value: Float = 0.1f, revision: Long = 0): EngineProgram = EngineProgram(listOf(
        Pad(0, PcmAsset.fromMono(FloatArray(256) { value }), mode = PlayMode.LOOP,
            attackFrames = 0, loopCrossfadeFrames = 0)), revision = revision)

    @Test fun pcmAndProgramOwnPublishedValuesAndRejectInvalidInput() {
        val raw = floatArrayOf(2f, -2f, 0.2f, 0.3f)
        val source = PcmAsset.fromInterleaved(raw)
        raw.fill(0f)
        assertEquals(2f, source.sample(0, 0))
        assertEquals(-2f, source.sample(0, 1))
        assertFailsWith<IllegalArgumentException> { PcmAsset.fromInterleaved(floatArrayOf(Float.NaN, 0f)) }
        assertFailsWith<IllegalArgumentException> { PcmAsset.fromInterleaved(floatArrayOf(Float.POSITIVE_INFINITY, 0f)) }
        assertFailsWith<IllegalArgumentException> { PcmAsset.fromInterleaved(floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { PcmAsset.fromInterleaved(FloatArray(4), maxBytes = 8) }
        assertFailsWith<IllegalArgumentException> { Pad(0, source, 2, 2) }
        val pads = mutableListOf(Pad(0, source), Pad(1, source))
        val program = EngineProgram(pads)
        pads.clear()
        assertEquals(16L, program.residentBytes)
        assertTrue(program.pad(0) != null)
        assertFailsWith<IllegalArgumentException> { EngineProgram(listOf(Pad(0, source), Pad(0, source))) }
        assertFailsWith<IllegalArgumentException> { EngineProgram(pattern = Pattern(960, listOf(SequenceNote(0, 0)))) }
    }

    @Test fun arbitraryPartitionsAreBitIdenticalAcrossCommandsSwapsTempoAndScratch() {
        val source = asset()
        val pattern = Pattern(960, listOf(SequenceNote(0, 0), SequenceNote(240, 1), SequenceNote(480, 0)))
        val initial = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP),
            Pad(1, source, pitchSemitones = 12.0, reverse = true)), pattern, Tempo(239_999, 637), 1)
        val second = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP, reverse = true),
            Pad(1, source, pitchSemitones = -7.0)), pattern, Tempo(93_125, 711), 2)
        val commands = listOf(
            EngineCommand.StartSequence(0, 0), EngineCommand.Trigger(173, 1, 1, 0.7f),
            EngineCommand.Release(351, 2, 1), EngineCommand.SwapProgram(4007, 3, second),
            EngineCommand.Panic(4097, 4), EngineCommand.Trigger(4100, 5, 0),
            EngineCommand.StartSequence(5000, 6), EngineCommand.SetTempo(7001, 7, Tempo(127_357, 683)),
            EngineCommand.ScratchStart(8000, 8, 0, 300.0),
            EngineCommand.ScratchPosition(8030, 9, 900.0, 120), EngineCommand.ScratchCut(8090, 10, 0f),
            EngineCommand.ScratchPosition(8200, 11, 200.0, 160), EngineCommand.ScratchCut(8300, 12, 1f),
            EngineCommand.ScratchEnd(8400, 13), EngineCommand.Stop(10003, 14))
        val reference = OfflineRender.render(initial, commands, 12_000, blockFrames = 1)
        for (block in intArrayOf(17, 96, 192, 480, 4096)) {
            val actual = OfflineRender.render(initial, commands, 12_000, blockFrames = block)
            assertContentEquals(reference, actual, "block=$block")
        }
        assertTrue(reference.any { it != 0f })
        assertTrue(reference.all { it.isFinite() })
    }

    @Test fun fullQueueAndFutureCommandsCannotSuppressPanicOrRestartStaleVoices() {
        val engine = EngineCore(constantProgram(), EngineConfig(controlCapacity = 2, eventCapacity = 16))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Trigger(1000, 0, 0)))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Trigger(2000, 1, 0)))
        assertEquals(OfferResult.FULL, engine.controls.offer(EngineCommand.Trigger(3000, 2, 0)))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Panic(0, 3)))
        val output = FloatArray(6000)
        engine.render(output)
        assertTrue(output.all { it == 0f })
        assertEquals(0, engine.activeVoiceCount)
        val event = MutableEngineEvent()
        var invalidated = 0
        while (engine.events.poll(event)) if (event.type == EngineEventType.INVALIDATED) invalidated++
        assertEquals(2, invalidated)
        assertEquals(1L, engine.controls.overflowCount)
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Trigger(3000, 4, 0)))
        engine.render(output)
        assertTrue(output.any { it != 0f })
    }

    @Test fun urgentStopBypassesFutureQueueEvenWhenNotFull() {
        val engine = EngineCore(constantProgram(), EngineConfig(controlCapacity = 8))
        engine.controls.offer(EngineCommand.Trigger(1000, 0, 0))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Stop(0, 1)))
        engine.controls.offer(EngineCommand.Trigger(3, 2, 0))
        val output = FloatArray(400)
        engine.render(output)
        assertEquals(1, engine.activeVoiceCount)
        assertEquals(0f, output[(3 + 71) * 2])
        assertTrue(output[(3 + 72) * 2] > 0f)
    }

    @Test fun ordinaryScheduledStopsDoNotCoalesceOrEraseIntermediateTriggers() {
        val output = OfflineRender.render(constantProgram(), listOf(
            EngineCommand.Trigger(0, 0, 0), EngineCommand.Panic(100, 1),
            EngineCommand.Trigger(200, 2, 0), EngineCommand.Panic(300, 3)), 400)
        assertTrue(output[10 * 2] != 0f)
        assertTrue(output[220 * 2] != 0f)
    }

    @Test fun lateCommandsReportRequestedAndAppliedFramesAndReadoutLossIsVisible() {
        val engine = EngineCore(constantProgram(), EngineConfig(eventCapacity = 2))
        engine.render(FloatArray(200))
        engine.controls.offer(EngineCommand.Trigger(30, 0, 0))
        engine.render(FloatArray(2))
        val event = MutableEngineEvent()
        assertTrue(engine.events.poll(event))
        assertEquals(EngineEventType.LATE, event.type)
        assertEquals(30L, event.requestedFrame)
        assertEquals(100L, event.appliedFrame)
        for (i in 1..4) engine.controls.offer(EngineCommand.Trigger(101, i.toLong(), 0))
        engine.render(FloatArray(2))
        val snapshot = EngineSnapshot()
        assertTrue(engine.readout.copyInto(snapshot))
        assertEquals(102L, snapshot.frame)
        assertEquals(1L, snapshot.lateCommands)
        assertEquals(2L, snapshot.eventOverflows)
    }

    @Test fun voicesAndStealTailsAreBoundedAndOverloadPreservesExistingTails() {
        val engine = EngineCore(constantProgram(0.01f))
        for (i in 0..48) engine.controls.offer(EngineCommand.Trigger(0, i.toLong(), 0))
        engine.render(FloatArray(2))
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(16, engine.fadeVoiceCount)
        assertEquals(1L, engine.rejectedVoices)
        engine.render(FloatArray(400))
        assertEquals(0, engine.fadeVoiceCount)
        engine.controls.offer(EngineCommand.Stop(engine.frame, 49))
        val tail = FloatArray((EngineCore.STOP_TAIL_FRAMES + 10) * 2)
        engine.render(tail)
        assertEquals(0, engine.activeVoiceCount)
        assertTrue(tail.drop(EngineCore.STOP_TAIL_FRAMES * 2).all { it == 0f })
    }

    @Test fun programSwapCountsStillPlayingAssetsAndRejectsMemoryOverflow() {
        val one = constantProgram(0.1f, 1)
        val two = constantProgram(0.2f, 2)
        val engine = EngineCore(one, EngineConfig(residentByteLimit = one.residentBytes))
        engine.controls.offer(EngineCommand.Trigger(0, 0, 0))
        assertEquals(OfferResult.PCM_LIMIT, engine.controls.offer(EngineCommand.SwapProgram(1, 1, two)))
        engine.render(FloatArray(4))
        assertEquals(1L, engine.programRevision)
        val event = MutableEngineEvent()
        while (engine.events.poll(event)) assertTrue(event.type != EngineEventType.PROGRAM_MEMORY_LIMIT)
        engine.controls.offer(EngineCommand.Panic(2, 2))
        engine.controls.offer(EngineCommand.SwapProgram(2, 3, EngineProgram.EMPTY))
        engine.render(FloatArray(2))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SwapProgram(3, 4, two)))
        engine.render(FloatArray(2))
        assertEquals(2L, engine.programRevision)
        assertEquals(two.residentBytes, engine.residentBytes)
    }

    @Test fun stopShortensAnExistingLongReleaseWithoutExceedingTheStopTail() {
        val source = PcmAsset.fromMono(FloatArray(256) { .1f })
        val program = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP,
            attackFrames = 0, releaseFrames = 48000, loopCrossfadeFrames = 0)))
        val engine = EngineCore(program)
        engine.controls.offer(EngineCommand.Trigger(0, 0, 0))
        engine.controls.offer(EngineCommand.Release(100, 1, 0))
        engine.controls.offer(EngineCommand.Stop(200, 2))
        val output = FloatArray(1000)
        engine.render(output)
        assertEquals(0, engine.activeVoiceCount)
        assertTrue(output.drop((200 + EngineCore.STOP_TAIL_FRAMES) * 2).all { it == 0f })
        assertTrue(abs(output[200 * 2] - output[199 * 2]) < .001)
    }

    @Test fun adsrDecaysToSustainAndReleaseHoldsTheActualNoteOffLevel() {
        val source = PcmAsset.fromMono(FloatArray(256) { .2f })
        val pad = Pad(0, source, mode = PlayMode.LOOP, attackFrames = 100,
            decayFrames = 100, sustainLevel = .25f, releaseFrames = 100, loopCrossfadeFrames = 48)
        val program = EngineProgram(listOf(pad))
        val result = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0),
            EngineCommand.Release(300, 1, 0)), 500)
        assertEquals(0f, result[0])
        assertEquals(.2f, result[100 * 2])
        assertEquals(.05f, result[200 * 2])
        assertEquals(.05f, result[300 * 2])
        assertEquals(0f, result[399 * 2])
        val early = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0),
            EngineCommand.Release(50, 1, 0)), 200)
        assertEquals(.1f, early[50 * 2])
        for (i in 51 until 150) assertTrue(early[i * 2] <= early[(i - 1) * 2], "release may not keep attacking")
    }

    @Test fun stereoReverseAndLoopPeriodKeepChannelIdentity() {
        val source = PcmAsset.fromInterleaved(floatArrayOf(.1f, -.05f, .2f, -.1f, .3f, -.15f, .4f, -.2f))
        for (reverse in listOf(false, true)) {
            val program = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP,
                reverse = reverse, attackFrames = 0, loopCrossfadeFrames = 0)))
            val out = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0)), 20)
            for (frame in 0 until 20) {
                val index = if (reverse) 3 - frame % 4 else frame % 4
                assertEquals(source.sample(index, 0), out[frame * 2])
                assertEquals(source.sample(index, 1), out[frame * 2 + 1])
            }
        }
    }

    @Test fun oneFrameSampleIsAudibleAndEndIsExclusive() {
        val program = EngineProgram(listOf(Pad(0, PcmAsset.fromInterleaved(floatArrayOf(.2f, -.1f)))))
        val result = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0)), 5)
        assertContentEquals(floatArrayOf(.2f, -.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), result)
    }

    @Test fun scratchIsSilentAtRestCutClosesAndTransportResumes() {
        val source = asset(4096)
        val program = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0)))
        val output = OfflineRender.render(program, listOf(
            EngineCommand.ScratchStart(0, 0, 0, 1500.0),
            EngineCommand.ScratchPosition(200, 1, 2500.0, 1000),
            EngineCommand.ScratchCut(400, 2, 0f),
            EngineCommand.ScratchPosition(900, 3, 1000.0, 1000),
            EngineCommand.ScratchCut(1300, 4, 1f), EngineCommand.ScratchEnd(1800, 5)), 2200)
        assertTrue(output.take(400).all { it == 0f })
        assertTrue(output.slice(500 * 2 until 1200 * 2).all { it == 0f })
        assertTrue(output.slice(1400 * 2 until 1700 * 2).any { abs(it) > .01f })
        val resumed = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0),
            EngineCommand.ScratchStart(200, 1, 0, 1500.0), EngineCommand.ScratchEnd(400, 2)), 1000)
        assertTrue(resumed.slice(300 * 2 until 390 * 2).all { it == 0f })
        assertTrue(resumed.slice(600 * 2 until 900 * 2).any { abs(it) > .01f })
    }

    @Test fun callerOffsetIsRespectedAndInvalidBuffersFailBeforeRendering() {
        val engine = EngineCore()
        val output = FloatArray(20) { 9f }
        engine.render(output, 2, 3)
        assertEquals(9f, output[3]); assertEquals(0f, output[4]); assertEquals(0f, output[9]); assertEquals(9f, output[10])
        assertFailsWith<IllegalArgumentException> { engine.render(output, Int.MAX_VALUE, 1) }
        assertEquals(3L, engine.frame)
        assertFalse(engine.events.poll(MutableEngineEvent()))
    }
}
