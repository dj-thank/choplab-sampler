package com.choplab.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalSourceContractTest {
    private fun pcm(frames: Int = 4096, left: Float = .1f, right: Float = -.05f) =
        PcmAsset.fromInterleaved(FloatArray(frames * 2) { if (it % 2 == 0) left else right })
    private fun program(asset: PcmAsset) = EngineProgram(listOf(Pad(0, asset, mode = PlayMode.LOOP,
        attackFrames = 0, loopCrossfadeFrames = 0)), arrangement = Arrangement(listOf(ArrangementClip("song", asset, 0))))

    @Test fun originalCursorSurvivesPadProgramChangesAndSongStopSeekPause() {
        val asset = pcm()
        val engine = EngineCore(program(asset))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset, 100, 4000)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        engine.controls.offer(EngineCommand.Trigger(40, 3, 0))
        engine.controls.offer(EngineCommand.SwapProgram(80, 4, program(asset)))
        engine.controls.offer(EngineCommand.Stop(120, 5))
        engine.controls.offer(EngineCommand.Seek(160, 6, 40))
        engine.controls.offer(EngineCommand.Pause(200, 7))
        engine.render(FloatArray(600))
        assertTrue(engine.originalLoaded && engine.originalPlaying)
        assertEquals(400L, engine.originalSourceFrame)
        assertEquals(40L, engine.sequenceFrame)
        val snapshot = EngineSnapshot()
        assertTrue(engine.readout.copyInto(snapshot))
        assertEquals(400L, snapshot.originalSourceFrame)
        assertTrue(snapshot.originalLoaded && snapshot.originalPlaying)
        assertTrue(snapshot.sequencePaused)
    }

    @Test fun originalPauseSeekResumeAndClearDoNotChangeSongTransport() {
        val asset = pcm()
        val engine = EngineCore(program(asset))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        engine.controls.offer(EngineCommand.PauseOriginalSource(100, 3))
        engine.render(FloatArray(400))
        assertEquals(100L, engine.originalSourceFrame)
        assertFalse(engine.originalPlaying)
        assertEquals(200L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.SeekOriginalSource(200, 4, 50))
        engine.controls.offer(EngineCommand.PlayOriginalSource(200, 5))
        engine.render(FloatArray(200))
        assertEquals(150L, engine.originalSourceFrame)
        assertEquals(300L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.SetOriginalSource(300, 6, null))
        engine.render(FloatArray(200))
        assertFalse(engine.originalLoaded)
        assertEquals(400L, engine.sequenceFrame)
    }

    @Test fun songAndOriginalMonitorGainsAreIndependentAndReachDigitalZero() {
        val song = pcm(left = .1f, right = 0f)
        val original = pcm(left = 0f, right = .2f)
        val engine = EngineCore(program(song))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(original)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        val baseline = FloatArray(600)
        engine.render(baseline)
        for (i in 168 until 300) { assertEquals(.1f, baseline[i * 2]); assertEquals(.2f, baseline[i * 2 + 1]) }
        engine.controls.offer(EngineCommand.SetSongMonitorGain(300, 3, 0f))
        val first = FloatArray(600)
        engine.render(first)
        for (i in 168 until 300) { assertEquals(0f, first[i * 2]); assertEquals(.2f, first[i * 2 + 1]) }
        engine.controls.offer(EngineCommand.SetSongMonitorGain(600, 4, 1f))
        val second = FloatArray(600)
        engine.render(second)
        for (i in 168 until 300) { assertEquals(.1f, second[i * 2]); assertEquals(.2f, second[i * 2 + 1]) }
        engine.controls.offer(EngineCommand.SetOriginalMonitorGain(900, 5, 0f))
        val third = FloatArray(600)
        engine.render(third)
        for (i in 168 until 300) { assertEquals(.1f, third[i * 2]); assertEquals(0f, third[i * 2 + 1]) }
        assertEquals(1f, engine.songMonitorGain)
        assertEquals(0f, engine.originalMonitorGain)
        assertTrue(engine.originalPlaying)
    }

    @Test fun exportRejectsMonitorCommandsAndOfflineAlwaysUsesUnitySongGain() {
        val asset = pcm()
        val program = program(asset)
        val monitorCommands = listOf(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)),
            EngineCommand.PlayOriginalSource(0, 1), EngineCommand.SetOriginalMonitorGain(0, 2, 0f),
            EngineCommand.SetSongMonitorGain(0, 3, 0f), EngineCommand.PauseOriginalSource(0, 4),
            EngineCommand.SeekOriginalSource(0, 5, 10))
        val export = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
        for (command in monitorCommands) assertEquals(OfferResult.MONITOR_DISABLED, export.controls.offer(command))
        val expected = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 0)), 500)
        val actual = OfflineRender.render(program, monitorCommands + EngineCommand.StartSequence(0, 6), 500)
        assertContentEquals(expected, actual)
        assertFalse(export.originalLoaded)
    }

    @Test fun sourceCancelBypassesFullQueueAndInvalidatesOnlyOldOriginalCommands() {
        val asset = pcm()
        val engine = EngineCore(program(asset), EngineConfig(controlCapacity = 4))
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.controls.offer(EngineCommand.SetOriginalSource(1000, 1, OriginalSource(asset)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(1000, 2))
        engine.controls.offer(EngineCommand.SetTempo(1000, 3, Tempo(93000)))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 4, null)))
        engine.render(FloatArray(2400))
        assertTrue(engine.sequencePlaying)
        assertEquals(1200L, engine.sequenceFrame)
        assertFalse(engine.originalLoaded || engine.originalPlaying)
        val event = MutableEngineEvent()
        var invalidated = 0
        var tempoApplied = false
        while (engine.events.poll(event)) {
            if (event.type == EngineEventType.INVALIDATED) invalidated++
            if (event.orderId == 3L && event.type == EngineEventType.APPLIED) tempoApplied = true
        }
        assertEquals(2, invalidated)
        assertTrue(tempoApplied)
    }

    @Test fun globalStopAllAndPanicStopBothAndCannotBeUndoneByOldSourceCommands() {
        val asset = pcm()
        val engine = EngineCore(program(asset), EngineConfig(controlCapacity = 4))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        engine.render(FloatArray(200))
        engine.controls.offer(EngineCommand.PlayOriginalSource(1000, 3))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StopAll(100, 4)))
        val output = FloatArray(2400)
        engine.render(output)
        assertTrue(output.take(72 * 2).any { it != 0f })
        assertTrue(output.drop(168 * 2).all { it == 0f })
        assertFalse(engine.originalPlaying || engine.sequencePlaying)
        assertEquals(0L, engine.originalSourceFrame)
        assertEquals(0L, engine.sequenceFrame)
        engine.controls.offer(EngineCommand.PlayOriginalSource(1300, 5))
        engine.controls.offer(EngineCommand.Panic(1301, 6))
        engine.render(FloatArray(200))
        assertFalse(engine.originalPlaying)
    }

    @Test fun songStopDoesNotCancelAnIndependentFutureOriginalPlayOrBlockNewSongCommands() {
        val asset = pcm()
        val engine = EngineCore(program(asset), EngineConfig(controlCapacity = 2))
        engine.controls.offer(EngineCommand.SetOriginalSource(50, 0, OriginalSource(asset)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(50, 1))
        engine.controls.offer(EngineCommand.Trigger(1000, 2, 0))
        engine.controls.offer(EngineCommand.Trigger(1100, 3, 0))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.Stop(0, 4)))
        engine.render(FloatArray(2))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StartSequence(1, 5)))
        engine.render(FloatArray(198))
        assertEquals(50L, engine.originalSourceFrame)
        assertTrue(engine.originalPlaying)
        assertEquals(99L, engine.sequenceFrame)
    }

    @Test fun pcmUnionCountsPendingProgramAndOriginalAndReclaimsOnlyAfterRenderRelease() {
        val a = pcm(16)
        val b = pcm(16, .2f)
        val c = pcm(16, .3f)
        val limit = a.residentBytes * 2
        val engine = EngineCore(EngineProgram(listOf(Pad(0, a))), EngineConfig(residentByteLimit = limit))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(b))))
        assertEquals(OfferResult.PCM_LIMIT, engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(c))))
        engine.render(FloatArray(2))
        assertEquals(limit, engine.residentBytes)
        assertEquals(OfferResult.PCM_LIMIT, engine.controls.offer(EngineCommand.SwapProgram(1, 2, EngineProgram(listOf(Pad(0, c))))))
        engine.controls.offer(EngineCommand.SetOriginalSource(1, 3, null))
        engine.render(FloatArray(2))
        assertEquals(a.residentBytes, engine.residentBytes)
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SwapProgram(2, 4, EngineProgram(listOf(Pad(0, c))))))
        engine.render(FloatArray(2))
        assertEquals(c.residentBytes, engine.residentBytes)
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(3, 5, OriginalSource(b))))
        engine.render(FloatArray(2))
        assertEquals(limit, engine.residentBytes)
    }

    @Test fun sharedPcmIsChargedOnceAndFailedOriginalCommandsLeaveOtherBusesIntact() {
        val a = pcm(64)
        val engine = EngineCore(program(a), EngineConfig(residentByteLimit = a.residentBytes))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(a, 4, 40))))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        engine.controls.offer(EngineCommand.SeekOriginalSource(4, 3, 50))
        engine.render(FloatArray(20))
        assertEquals(14L, engine.originalSourceFrame)
        assertTrue(engine.originalPlaying && engine.sequencePlaying)
        assertEquals(a.residentBytes, engine.residentBytes)
        assertFailsWith<IllegalArgumentException> { EngineCommand.SetSongMonitorGain(10, 4, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { OriginalSource(a, 10, 10) }
    }

    @Test fun originalLoopRangeAndMixedHeadroomRemainFinite() {
        val a = pcm(256, 2f, -2f)
        val engine = EngineCore(program(a))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(a, 10, 20, true)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.controls.offer(EngineCommand.StartSequence(0, 2))
        val output = FloatArray(1000)
        engine.render(output)
        assertEquals(10L, engine.originalSourceFrame)
        assertTrue(engine.originalPlaying)
        assertTrue(output.all { it.isFinite() && abs(it) <= MasterLimiter.CEILING.toFloat() })
    }

    @Test fun monitorBusPartitionsAreBitIdenticalAcrossIndependentCommands() {
        val a = pcm()
        val b = pcm(4096, .15f, .07f)
        val program = program(a)
        val commands = listOf(EngineCommand.SetOriginalSource(0, 0, OriginalSource(a, 5, 2053, true)),
            EngineCommand.PlayOriginalSource(0, 1), EngineCommand.StartSequence(0, 2),
            EngineCommand.SetSongMonitorGain(97, 3, .3f), EngineCommand.SetOriginalMonitorGain(173, 4, .4f),
            EngineCommand.Pause(237, 5), EngineCommand.SeekOriginalSource(331, 6, 1111),
            EngineCommand.Seek(421, 7, 200), EngineCommand.Resume(500, 8),
            EngineCommand.PauseOriginalSource(621, 9), EngineCommand.PlayOriginalSource(701, 10),
            EngineCommand.SetOriginalSource(799, 11, null), EngineCommand.SetOriginalSource(901, 12, OriginalSource(b)),
            EngineCommand.PlayOriginalSource(901, 13), EngineCommand.Stop(1307, 14), EngineCommand.StopAll(1901, 15))
        fun render(block: Int): FloatArray {
            val engine = EngineCore(program)
            for (command in commands) assertEquals(OfferResult.ACCEPTED, engine.controls.offer(command))
            val output = FloatArray(5000)
            var position = 0
            while (position < 2500) {
                val count = minOf(block, 2500 - position)
                engine.render(output, position, count)
                position += count
            }
            return output
        }
        val expected = render(1)
        for (block in intArrayOf(17, 96, 192, 480, 4096)) assertContentEquals(expected, render(block), "block=$block")
    }

    @Test fun nonzeroDcPlayPauseSeekAndReplacementAreCrossfadedAndPartitionIndependent() {
        val split = PcmAsset.fromInterleaved(FloatArray(4096) { i ->
            val value = if (i / 2 < 1024) .2f else -.2f
            if (i % 2 == 0) value else value / 2
        })
        val positive = pcm(2048, .2f, .1f)
        val commands = listOf(EngineCommand.SetOriginalSource(0, 0, OriginalSource(split)),
            EngineCommand.PlayOriginalSource(0, 1), EngineCommand.SeekOriginalSource(400, 2, 1500),
            EngineCommand.SetOriginalSource(700, 3, OriginalSource(positive)),
            EngineCommand.PauseOriginalSource(1000, 4), EngineCommand.PlayOriginalSource(1200, 5),
            EngineCommand.StopAll(1400, 6))
        fun render(block: Int): FloatArray {
            val engine = EngineCore()
            for (command in commands) assertEquals(OfferResult.ACCEPTED, engine.controls.offer(command))
            val output = FloatArray(3600)
            var frame = 0
            while (frame < 1800) {
                val count = minOf(block, 1800 - frame)
                engine.render(output, frame, count)
                frame += count
            }
            return output
        }
        val output = render(1)
        var maximumJump = 0f
        for (i in 1 until 1800) maximumJump = maxOf(maximumJump, abs(output[i * 2] - output[(i - 1) * 2]))
        println("ORIGINAL DC transitions maxAdjacentJump=$maximumJump expectedBound=0.0065 fadeFrames=96")
        assertTrue(maximumJump <= .0065f)
        assertEquals(.2f, output[300 * 2])
        assertEquals(-.2f, output[600 * 2])
        assertEquals(.2f, output[900 * 2])
        assertEquals(0f, output[1199 * 2])
        assertTrue(output.drop(1568 * 2).all { it == 0f })
        for (block in intArrayOf(17, 96, 192, 480, 4096)) assertContentEquals(output, render(block), "block=$block")
    }

    @Test fun pauseFreezesImmediatelyAndResumeUsesSameCursorWhileTailOwnsNoOldPcm() {
        val song = pcm(512, 0f, 0f)
        val positive = pcm(512, .2f, .1f)
        val negative = pcm(512, -.2f, -.1f)
        val engine = EngineCore(EngineProgram(listOf(Pad(0, song))), EngineConfig(residentByteLimit = song.residentBytes * 2))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(positive)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.render(FloatArray(400))
        engine.controls.offer(EngineCommand.PauseOriginalSource(200, 2))
        engine.render(FloatArray(20))
        assertEquals(200L, engine.originalSourceFrame)
        assertFalse(engine.originalPlaying)
        engine.controls.offer(EngineCommand.PlayOriginalSource(210, 3))
        engine.render(FloatArray(2))
        assertEquals(201L, engine.originalSourceFrame)
        engine.controls.offer(EngineCommand.SetOriginalSource(211, 4, null))
        engine.render(FloatArray(2))
        assertFalse(engine.originalLoaded)
        // The old positive output still fades, but its asset reservation has been retired.
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(212, 5, OriginalSource(negative))))
        engine.controls.offer(EngineCommand.PlayOriginalSource(212, 6))
        val tail = FloatArray(400)
        engine.render(tail)
        assertEquals(song.residentBytes * 2, engine.residentBytes)
        assertEquals(-.2f, tail[199 * 2])
    }

    @Test fun originalTransitionsPreserveIndependentMusicSamples() {
        val song = pcm(2048, .03f, 0f)
        val positive = pcm(2048, 0f, .2f)
        val negative = pcm(2048, 0f, -.2f)
        val engine = EngineCore(program(song))
        engine.controls.offer(EngineCommand.StartSequence(0, 0))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(positive)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 2))
        engine.controls.offer(EngineCommand.PauseOriginalSource(200, 3))
        engine.controls.offer(EngineCommand.SeekOriginalSource(400, 4, 700))
        engine.controls.offer(EngineCommand.PlayOriginalSource(500, 5))
        engine.controls.offer(EngineCommand.SetOriginalSource(700, 6, OriginalSource(negative)))
        engine.controls.offer(EngineCommand.SetOriginalSource(900, 7, null))
        val output = FloatArray(2400)
        engine.render(output)
        for (i in 72 until 1200) assertEquals(.03f, output[i * 2], "music sample at $i")
        assertEquals(1200L, engine.sequenceFrame)
    }

    @Test fun urgentStopAllFadesButPanicStillSilencesImmediately() {
        val asset = pcm(2048, .2f, .1f)
        val engine = EngineCore()
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        engine.render(FloatArray(512))
        engine.controls.offer(EngineCommand.PlayOriginalSource(1000, 2))
        engine.controls.offer(EngineCommand.StopAll(256, 3))
        val faded = FloatArray(384)
        engine.render(faded)
        assertEquals(.2f, faded[0])
        assertTrue(faded.drop(168 * 2).all { it == 0f })
        assertEquals(0L, engine.originalSourceFrame)
        assertFalse(engine.originalPlaying)
        engine.controls.offer(EngineCommand.PlayOriginalSource(448, 4))
        engine.render(FloatArray(400))
        engine.controls.offer(EngineCommand.Panic(648, 5))
        val panic = FloatArray(200)
        engine.render(panic)
        assertTrue(panic.all { it == 0f })
    }

    @Test fun naturalNonzeroEndReleasesHeldOutputWithoutAdvancingPastEnd() {
        val engine = EngineCore()
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(pcm(256, .2f, .1f))))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
        val output = FloatArray(1000)
        engine.render(output)
        assertFalse(engine.originalPlaying)
        assertEquals(256L, engine.originalSourceFrame)
        var jump = 0f
        for (i in 1 until 500) jump = maxOf(jump, abs(output[i * 2] - output[(i - 1) * 2]))
        assertTrue(jump <= .0032f, "natural-end maximum adjacent jump=$jump")
        assertTrue(output[350 * 2] > 0f)
        assertTrue(output.drop(424 * 2).all { it == 0f })
    }
}
