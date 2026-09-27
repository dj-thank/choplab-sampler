package com.choplab.engine

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** The original's transport and the hand share PCM, never a cursor or a monitor gain. */
class SourceHandContractTest {
    private fun pcm(frames: Int = 8_192) = PcmAsset.fromInterleaved(FloatArray(frames * 2) { i ->
        // The source's first half sounds only left, the hand's range only right.
        if (i / 2 < frames / 2) { if (i % 2 == 0) .1f else 0f }
        else { if (i % 2 == 0) 0f else .2f }
    })
    private fun EngineCore.offer(command: EngineCommand) = assertEquals(OfferResult.ACCEPTED, controls.offer(command))
    private fun EngineCore.renderFrames(count: Int) = FloatArray(count * 2).also { render(it) }
    private fun EngineCore.eventFor(id: Long): EngineEventType? {
        val event = MutableEngineEvent()
        var result: EngineEventType? = null
        while (events.poll(event)) if (event.orderId == id) result = event.type
        return result
    }

    @Test fun sourceAndHandHaveIndependentStereoGainCutAndCursor() {
        val asset = pcm()
        val engine = EngineCore()
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        engine.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 2, 4_500.0, 4_096, 8_192))
        engine.offer(EngineCommand.ScratchOriginalPosition(0, 3, 7_500.0, 3_000))
        fun levels(left: Float, right: Float) {
            val output = engine.renderFrames(300)
            for (f in 168 until 300) {
                assertEquals(left, output[f * 2], 1e-6f, "SOURCE at $f")
                assertEquals(right, output[f * 2 + 1], 1e-6f, "HAND at $f")
            }
        }
        levels(.1f, .2f)
        engine.offer(EngineCommand.SetHandMonitorGain(300, 4, .25f))
        levels(.1f, .05f)
        engine.offer(EngineCommand.ScratchOriginalCut(600, 5, 0f))
        levels(.1f, 0f)
        engine.offer(EngineCommand.SetOriginalMonitorGain(900, 6, 0f))
        engine.offer(EngineCommand.ScratchOriginalCut(900, 7, 1f))
        levels(0f, .05f)
        assertEquals(1_200L, engine.originalSourceFrame)
        assertEquals(5_700.0, engine.handSourceFrame)
        engine.offer(EngineCommand.ScratchOriginalEnd(1_200, 8))
        levels(0f, 0f)
        assertEquals(1_500L, engine.originalSourceFrame)
        assertTrue(engine.originalPlaying)
        assertEquals(-1.0, engine.handSourceFrame)
        assertEquals(0, engine.activeVoiceCount)
        val readout = EngineSnapshot()
        assertTrue(engine.readout.copyInto(readout))
        assertEquals(.25f, readout.handMonitorGain)
        assertEquals(-1.0, readout.handSourceFrame)
    }

    @Test fun sourcePlaySeekAndNaturalEndNeverMoveOrReleaseHand() {
        val engine = EngineCore()
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(pcm(512), 100, 400)))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 1, 300.0, 0, 500))
        engine.offer(EngineCommand.PlayOriginalSource(10, 2))
        engine.offer(EngineCommand.SeekOriginalSource(20, 3, 395))
        engine.renderFrames(100)
        assertFalse(engine.originalPlaying)
        assertEquals(400L, engine.originalSourceFrame)
        assertEquals(300.0, engine.handSourceFrame)
        engine.offer(EngineCommand.ScratchOriginalPosition(100, 4, 0.0, 25))
        engine.renderFrames(25)
        assertEquals(100.0, engine.handSourceFrame, "Clamp to the loaded SOURCE region's inclusive start")
        engine.offer(EngineCommand.ScratchOriginalPosition(125, 5, 999.0, 100))
        engine.renderFrames(100)
        assertEquals(399.0, engine.handSourceFrame, 1e-8, "End remains exclusive")
        assertEquals(400L, engine.originalSourceFrame)
        engine.offer(EngineCommand.ScratchOriginalEnd(225, 6))
        engine.renderFrames(200)
        assertFalse(engine.originalPlaying, "HAND release cannot restart a naturally ended SOURCE")
        assertEquals(400L, engine.originalSourceFrame)
    }

    @Test fun stoppingReplacingAndSwappingCleanUpHandAndInvalidateItsQueuedRestart() {
        val asset = pcm()
        val stopCommands: List<(Long) -> EngineCommand> = listOf(
            { EngineCommand.PauseOriginalSource(300, it) },
            { EngineCommand.SetOriginalSource(300, it, null) },
            { EngineCommand.SetOriginalSource(300, it, OriginalSource(asset)) },
            { EngineCommand.SwapProgram(300, it, EngineProgram.EMPTY) },
            { EngineCommand.Stop(300, it) },
            { EngineCommand.StopAll(300, it) },
            { EngineCommand.Panic(300, it) },
        )
        for ((index, stop) in stopCommands.withIndex()) {
            val engine = EngineCore()
            engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
            engine.offer(EngineCommand.PlayOriginalSource(0, 1))
            engine.offer(EngineCommand.ScratchOriginalStart(0, 2, 4_500.0, 4_096, 8_192))
            engine.offer(EngineCommand.ScratchOriginalPosition(0, 3, 7_500.0, 3_000))
            engine.renderFrames(300)
            engine.offer(EngineCommand.ScratchOriginalStart(900, 4, 5_000.0, 4_096, 8_192))
            engine.offer(EngineCommand.ScratchOriginalPosition(900, 5, 6_000.0, 300))
            engine.offer(stop(6))
            val output = engine.renderFrames(1_000)
            assertEquals(-1.0, engine.handSourceFrame, "stop kind $index")
            assertEquals(0, engine.activeVoiceCount, "Return the reserved slot after stop kind $index")
            assertTrue((168 until 1_000).all { output[it * 2 + 1] == 0f }, "No old HAND/restart after stop kind $index")
            assertEquals(EngineEventType.INVALIDATED, engine.eventFor(5), "stop kind $index")
            if (index == 2 || index == 3 || index == 4) assertTrue(engine.originalPlaying, "SOURCE survives kind $index")
            else assertFalse(engine.originalPlaying)
        }
    }

    @Test fun releaseBypassesFullFutureHandLaneWithoutCancellingSourceAndAllowsNewHand() {
        val engine = EngineCore(config = EngineConfig(controlCapacity = 4))
        val asset = pcm()
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        engine.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 2, 4_500.0, 4_096, 8_192))
        engine.renderFrames(100)
        for (i in 0 until 4) engine.offer(EngineCommand.ScratchOriginalStart(1_000L + i, 3L + i, 5_000.0, 4_096, 8_192))
        engine.offer(EngineCommand.SeekOriginalSource(200, 7, 500))
        engine.offer(EngineCommand.ScratchOriginalEnd(100, 8))
        engine.renderFrames(1)
        assertEquals(-1.0, engine.handSourceFrame)
        engine.offer(EngineCommand.ScratchOriginalStart(101, 9, 4_700.0, 4_096, 8_192))
        engine.renderFrames(1_000)
        assertEquals(4_700.0, engine.handSourceFrame, "Old queued starts must not overtake the new gesture")
        assertEquals(1_401L, engine.originalSourceFrame, "Independent SOURCE seek ran at frame 200")
        assertTrue(engine.originalPlaying)
    }

    @Test fun repeatedReleaseDoesNotExtendTailOrHoldAReservedSlot() {
        val engine = EngineCore()
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(pcm())))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 1, 4_500.0, 4_096, 8_192))
        engine.offer(EngineCommand.ScratchOriginalPosition(0, 2, 7_500.0, 3_000))
        engine.renderFrames(300)
        engine.offer(EngineCommand.ScratchOriginalEnd(300, 3))
        engine.offer(EngineCommand.ScratchOriginalEnd(350, 4))
        engine.renderFrames(96)
        assertEquals(0, engine.activeVoiceCount)
        assertEquals(-1.0, engine.handSourceFrame)
        assertTrue(engine.renderFrames(200).drop(72 * 2).all { it == 0f })
    }

    @Test fun handUsesExistingPrimaryBudgetAndNeverStealsAnExistingHandOrPadScratch() {
        val asset = pcm()
        val pads = (0 until 33).map { Pad(it, asset, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0) }
        val engine = EngineCore(EngineProgram(pads))
        var id = 0L
        engine.offer(EngineCommand.SetOriginalSource(0, id++, OriginalSource(asset, loop = true)))
        engine.offer(EngineCommand.PlayOriginalSource(0, id++))
        for (i in 0 until 31) engine.offer(EngineCommand.Trigger(0, id++, i))
        engine.offer(EngineCommand.ScratchStart(0, id++, 32, 200.0))
        // 32 primary + 15 fade, then HAND reserves one primary via the last free fade slot.
        repeat(15) { engine.offer(EngineCommand.Trigger(0, id++, 0)) }
        engine.offer(EngineCommand.ScratchOriginalStart(0, id++, 5_000.0, 4_096, 8_192))
        engine.renderFrames(1)
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(16, engine.fadeVoiceCount)
        assertEquals(200.0, engine.scratchFrame)
        assertEquals(5_000.0, engine.handSourceFrame)
        val refused = id++
        engine.offer(EngineCommand.Trigger(1, refused, 0))
        engine.renderFrames(1)
        assertEquals(EngineEventType.VOICE_LIMIT, engine.eventFor(refused))
        assertEquals(5_000.0, engine.handSourceFrame)
        assertTrue(engine.originalPlaying)
        engine.offer(EngineCommand.ScratchOriginalEnd(2, id++))
        engine.renderFrames(100)
        assertEquals(31, engine.activeVoiceCount)
        assertEquals(0, engine.fadeVoiceCount)
        engine.offer(EngineCommand.Trigger(engine.frame, id++, 0))
        engine.renderFrames(1)
        assertEquals(32, engine.activeVoiceCount, "The released reservation can be reused by a PAD")
        assertEquals(0, engine.fadeVoiceCount, "Reusing the returned slot does not steal")
        assertEquals(200.0, engine.scratchFrame)
        engine.offer(EngineCommand.StopAll(engine.frame, id))
        val stopped = engine.renderFrames(400)
        assertEquals(0, engine.activeVoiceCount)
        assertEquals(0, engine.fadeVoiceCount)
        assertTrue(stopped.drop(168 * 2).all { it == 0f })
    }

    @Test fun saturatedFadeBudgetRefusesHandWithoutPausingSourceOrLeakingAReservation() {
        val asset = pcm()
        val engine = EngineCore(EngineProgram(listOf(Pad(0, asset, mode = PlayMode.LOOP))))
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset, loop = true)))
        engine.offer(EngineCommand.PlayOriginalSource(0, 1))
        for (i in 2L until 50L) engine.offer(EngineCommand.Trigger(0, i, 0))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 50, 5_000.0, 4_096, 8_192))
        engine.renderFrames(1)
        assertEquals(EngineEventType.VOICE_LIMIT, engine.eventFor(50))
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(16, engine.fadeVoiceCount)
        assertEquals(-1.0, engine.handSourceFrame)
        assertTrue(engine.originalPlaying)
        engine.renderFrames(100)
        engine.offer(EngineCommand.ScratchOriginalStart(101, 51, 5_000.0, 4_096, 8_192))
        engine.renderFrames(1)
        assertEquals(5_000.0, engine.handSourceFrame)
        assertEquals(32, engine.activeVoiceCount)
        assertEquals(1, engine.fadeVoiceCount)
    }

    @Test fun sharedPcmIsChargedOnceAndCancelledHandTailRetainsNoAsset() {
        val a = pcm(512)
        val b = pcm(512)
        val engine = EngineCore(EngineProgram(listOf(Pad(0, a))), EngineConfig(residentByteLimit = a.residentBytes))
        engine.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(a)))
        engine.offer(EngineCommand.ScratchOriginalStart(0, 1, 300.0, 0, 512))
        engine.offer(EngineCommand.ScratchOriginalPosition(0, 2, 500.0, 300))
        engine.renderFrames(200)
        assertEquals(a.residentBytes, engine.residentBytes)
        assertEquals(OfferResult.PCM_LIMIT, engine.controls.offer(EngineCommand.SetOriginalSource(200, 3, OriginalSource(b))))
        engine.offer(EngineCommand.SwapProgram(200, 4, EngineProgram.EMPTY))
        engine.offer(EngineCommand.SetOriginalSource(200, 5, null))
        engine.renderFrames(1)
        assertEquals(0L, engine.residentBytes)
        // Both fade tails now contain scalar samples; neither pins the old immutable PCM.
        engine.offer(EngineCommand.SetOriginalSource(201, 6, OriginalSource(b)))
        engine.renderFrames(200)
        assertEquals(b.residentBytes, engine.residentBytes)
        assertEquals(-1.0, engine.handSourceFrame)
    }

    @Test fun handMonitorCommandsCannotEnterOfflineExport() {
        val asset = pcm()
        val program = EngineProgram(listOf(Pad(0, asset)))
        val commands = listOf(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)),
            EngineCommand.PlayOriginalSource(0, 1), EngineCommand.SetHandMonitorGain(0, 2, .5f),
            EngineCommand.ScratchOriginalStart(0, 3, 4_500.0, 4_096, 8_192),
            EngineCommand.ScratchOriginalPosition(100, 4, 7_000.0, 400),
            EngineCommand.ScratchOriginalCut(300, 5, 0f), EngineCommand.ScratchOriginalEnd(400, 6))
        val engine = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
        for (command in commands) assertEquals(OfferResult.MONITOR_DISABLED, engine.controls.offer(command))
        val pad = EngineCommand.Trigger(0, 7, 0)
        assertContentEquals(OfflineRender.render(program, listOf(pad), 1_000),
            OfflineRender.render(program, commands + pad, 1_000))
    }

    @Test fun simultaneousSourceAndHandCommandsAreBitIdenticalAcrossBlockPartitions() {
        val asset = PcmAsset.fromInterleaved(FloatArray(16_384) { (.03 * sin(it * .01)).toFloat() })
        val commands = listOf(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset, loop = true)),
            EngineCommand.PlayOriginalSource(0, 1),
            EngineCommand.ScratchOriginalStart(73, 2, 4_000.5, 3_000, 6_000),
            EngineCommand.ScratchOriginalPosition(97, 3, 5_900.0, 400),
            EngineCommand.SetHandMonitorGain(173, 4, .3f),
            EngineCommand.SetOriginalPitch(199, 5, 12f),
            EngineCommand.ScratchOriginalPosition(397, 6, -1_000.0, 151),
            EngineCommand.ScratchOriginalCut(501, 7, 0f),
            EngineCommand.SeekOriginalSource(531, 8, 7_000),
            EngineCommand.ScratchOriginalCut(699, 9, 1f),
            EngineCommand.ScratchOriginalPosition(701, 10, 3_000.0, 100),
            EngineCommand.ScratchOriginalEnd(839, 11),
            EngineCommand.ScratchOriginalStart(991, 12, 3_500.0, 3_000, 6_000),
            EngineCommand.ScratchOriginalPosition(991, 13, 5_000.0, 700),
            EngineCommand.PauseOriginalSource(1_397, 14),
            EngineCommand.PlayOriginalSource(1_601, 15),
            EngineCommand.ScratchOriginalStart(1_621, 16, 3_500.0, 3_000, 6_000),
            EngineCommand.ScratchOriginalPosition(1_621, 17, 5_000.0, 700),
            EngineCommand.StopAll(1_901, 18))
        fun render(block: Int): FloatArray {
            val engine = EngineCore()
            for (command in commands) engine.offer(command)
            val output = FloatArray(5_000)
            var at = 0
            while (at < 2_500) { val count = minOf(block, 2_500 - at); engine.render(output, at, count); at += count }
            assertEquals(-1.0, engine.handSourceFrame)
            assertEquals(0, engine.activeVoiceCount)
            assertFalse(engine.originalPlaying)
            return output
        }
        val expected = render(1)
        assertTrue(expected.any { abs(it) > .01f })
        assertTrue(expected.drop(2_069 * 2).all { it == 0f })
        for (block in intArrayOf(17, 96, 192, 480, 4_096)) assertContentEquals(expected, render(block), "block=$block")
    }
}
