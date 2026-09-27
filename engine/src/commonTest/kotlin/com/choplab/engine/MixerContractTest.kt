package com.choplab.engine

import kotlin.math.*
import kotlin.test.*

class MixerContractTest {
    private fun sample(dsp: MixerDsp, left: Double, right: Double, input: Boolean = true) {
        dsp.beginFrame()
        if (input) dsp.add(0, left, right)
        dsp.process(left, right)
    }
    private fun toneGain(insert: MixInsert, hz: Double): Double {
        val dsp = MixerDsp(MixerProgram(listOf(TrackFx(insert))))
        try {
            var sine = 0.0; var cosine = 0.0
            repeat(24_000) { frame ->
                val phase = 2 * PI * hz * frame / 48_000
                sample(dsp, .01 * sin(phase), -.0025 * sin(phase))
                assertEquals(-dsp.outputLeft / 4, dsp.outputRight, 1e-12)
                if (frame >= 12_000) { sine += dsp.outputLeft * sin(phase); cosine += dsp.outputLeft * cos(phase) }
            }
            return 2 * sqrt(sine * sine + cosine * cosine) / 12_000 / .01
        } finally { dsp.close() }
    }

    @Test fun eqCentersAndFilterCutoffMatchIndependentFrequencyOracleWithoutStereoCollapse() {
        for ((hz, eq) in listOf(160.0 to MixEq(lowDb = 12f), 1000.0 to MixEq(midDb = -12f), 8000.0 to MixEq(highDb = 6f))) {
            val expected = if (hz == 160.0) 12.0 else if (hz == 1000.0) -12.0 else 6.0
            assertEquals(expected, 20 * log10(toneGain(MixInsert(eq = eq), hz)), .001)
        }
        for (mode in listOf(MixFilterMode.LOW_PASS, MixFilterMode.HIGH_PASS)) {
            assertEquals(-3.01029995664, 20 * log10(toneGain(MixInsert(filter = MixFilter(mode, 1000f)), 1000.0)), .001)
        }
        val high = MixerDsp(MixerProgram(listOf(TrackFx(MixInsert(filter = MixFilter(MixFilterMode.HIGH_PASS, 20f))))))
        try { repeat(48_000) { sample(high, .5, -.25) }; assertTrue(abs(high.outputLeft) < 1e-12) }
        finally { high.close() }
    }

    @Test fun linkedCompressorFollowsStaticRatioAndMeterReportsActualPostInsertFrames() {
        val dsp = MixerDsp(MixerProgram(listOf(TrackFx(MixInsert(compressor = MixCompressor(true, -20f, 4f, .1f, 100f))))))
        try {
            repeat(4800) { sample(dsp, .5, -.125) }
            val expected = .1 * 5.0.pow(.25)
            assertEquals(expected, dsp.outputLeft, 1e-9)
            assertEquals(-expected / 4, dsp.outputRight, 1e-9)
            dsp.beginBlock()
            repeat(192) { sample(dsp, .5, -.125) }
            dsp.endBlock(4992, 192)
            val read = MixerSnapshot()
            assertTrue(dsp.readout.copyInto(read)); assertEquals(4992, read.frame)
            assertEquals(expected.toFloat(), read.peak[0]); assertEquals(expected.toFloat(), read.rms[0])
            assertEquals((expected / 4).toFloat(), read.rms[1])
            assertEquals("bus-0", read.program.busId(0))
            val replacement = MixerProgram(listOf(TrackFx()), busIds = listOf("other-track"))
            dsp.use(replacement); dsp.beginBlock(); sample(dsp, .03125, -.015625); dsp.endBlock(4993, 1)
            assertTrue(dsp.readout.copyInto(read)); assertSame(replacement, read.program)
            assertEquals(4993, read.frame); assertEquals(.03125f, read.peak[0]); assertEquals(.015625f, read.peak[1])
        } finally { dsp.close() }
    }

    @Test fun delayHasExactFrameFeedbackAndFiniteStereoTailAndResetCannotRecallOldHistory() {
        val program = MixerProgram(listOf(TrackFx(delaySend = 1f)), MixSettings(delay = MixDelay(true, 7, .5f, .5f)))
        val dsp = MixerDsp(program)
        val stem = FloatArray(MixerProgram.STEM_COUNT * 2)
        try {
            repeat(program.tailFrames + 10) { frame ->
                sample(dsp, if (frame == 0) .25 else 0.0, if (frame == 0) -.125 else 0.0, frame == 0)
                dsp.writeStems(stem, 0)
                if (frame in 1..70) {
                    val expected = if (frame % 7 == 0) .125 * .5.pow(frame / 7 - 1) else 0.0
                    assertEquals(expected.toFloat(), stem[MixerProgram.DELAY_RETURN * 2])
                    assertEquals((if (expected == 0.0) 0.0 else -expected / 2).toFloat(), stem[MixerProgram.DELAY_RETURN * 2 + 1])
                }
                if (frame >= program.tailFrames) assertEquals(0.0, dsp.outputLeft)
            }
            sample(dsp, 1.0, -1.0); dsp.reset()
            repeat(100) { sample(dsp, 0.0, 0.0, false); assertEquals(0.0, dsp.outputLeft); assertEquals(0.0, dsp.outputRight) }
            val maximum = MixerProgram(listOf(TrackFx(delaySend = 1f, reverbSend = 1f)),
                MixSettings(delay = MixDelay(true, 96_000, .6f), reverb = MixReverb(true, 3f)))
            assertTrue(maximum.tailFrames <= 48_000 * 60)
            assertEquals(2_832_096, maximum.tailFrames)
        } finally { dsp.close() }
    }

    @Test fun reverbDecaysAndStemSumIsExactlyThePreMasterGraphWithinFloatRounding() {
        val program = MixerProgram(listOf(TrackFx(reverbSend = 1f, delaySend = .25f)),
            MixSettings(delay = MixDelay(true, 97, .2f), reverb = MixReverb(true, .2f, .2f), masterGain = 2f))
        val dsp = MixerDsp(program)
        val stems = FloatArray(MixerProgram.STEM_COUNT * 2)
        var early = 0.0; var late = 0.0
        try {
            repeat(program.tailFrames + 1) { frame ->
                sample(dsp, if (frame == 0) .25 else 0.0, 0.0, frame == 0)
                dsp.writeStems(stems, 0)
                var sum = 0.0
                for (bus in 0 until MixerProgram.STEM_COUNT) { sum += stems[bus * 2]; assertEquals(0f, stems[bus * 2 + 1]) }
                assertEquals(dsp.preMasterLeft, sum, 2e-8)
                assertEquals(dsp.preMasterLeft * 2, dsp.outputLeft, 1e-12)
                val wet = stems[MixerProgram.REVERB_RETURN * 2].toDouble()
                if (frame in 2_000..12_000) early += wet * wet
                if (frame in 24_000..34_000) late += wet * wet
            }
            assertTrue(early > 1e-6); assertTrue(late < early * 1e-4)
            assertEquals(0.0, dsp.outputLeft)
        } finally { dsp.close() }
    }

    @Test fun bypassPreservesOldVoiceOrderAndFxIsBitExactAcrossCallbackPartitionsAndSeeks() {
        val pcm = PcmAsset.fromInterleaved(FloatArray(4096 * 2) { if (it % 2 == 0) .03f * sin(it * .031f) else -.007f * cos(it * .013f) })
        val pads = listOf(Pad(0, pcm, 0, 4096, mode = PlayMode.LOOP, mixBus = 0), Pad(1, pcm, 0, 4096, mode = PlayMode.LOOP, mixBus = 1))
        fun program(mix: MixerProgram) = EngineProgram(pads, arrangement = Arrangement(listOf(ArrangementClip("clip", pcm, 0, trackIndex = 0))), mixer = mix)
        val commands = listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Trigger(0, 2, 0), EngineCommand.Trigger(53, 3, 1),
            EngineCommand.Seek(2003, 4, 100), EngineCommand.StopAll(6000, 5))
        val bypass = OfflineRender.render(program(MixerProgram.BYPASS), commands, 6500, blockFrames = 480)
        assertContentEquals(bypass, OfflineRender.render(program(MixerProgram(listOf(TrackFx(), TrackFx()))), commands, 6500, blockFrames = 17))
        val fx = MixerProgram(List(2) { TrackFx(MixInsert(MixEq(3f, -2f, 4f), MixFilter(MixFilterMode.LOW_PASS, 12000f),
            MixCompressor(true)), .25f, .3f) }, MixSettings(MixDelay(true, 113, .3f), MixReverb(true), MixInsert(compressor = MixCompressor(true))))
        val reference = OfflineRender.render(program(fx), commands, 6500, blockFrames = 480)
        assertFalse(reference.contentEquals(bypass))
        for (block in listOf(1, 17, 96, 192, 4096)) assertContentEquals(reference,
            OfflineRender.render(program(fx), commands, 6500, blockFrames = block), "block=$block")
        assertTrue(reference.drop((6000 + EngineCore.STEAL_FADE_FRAMES) * 2).all { it == 0f })
    }

    @Test fun sequenceWrapKeepsTheSharedDelayAndExportOnlyRemovesTheExisting72FrameLatency() {
        val pcm = PcmAsset.fromInterleaved(FloatArray(256 * 2).also { it[0] = .2f; it[1] = -.1f })
        val program = EngineProgram(listOf(Pad(0, pcm, 0, 256, attackFrames = 0, mixBus = 0)),
            Pattern(3840, listOf(SequenceNote(0, 0))), mixer = MixerProgram(listOf(TrackFx(delaySend = 1f)),
                MixSettings(delay = MixDelay(true, 60_000, .5f, 1f))))
        val output = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1)), 125_000, blockFrames = 192)
        assertEquals(.2f, output[0]); assertEquals(.2f, output[60_000 * 2])
        assertEquals(.1f, output[120_000 * 2], "A delay begun before the 96,000-frame pattern wrap survives it")
        assertEquals(-.05f, output[120_000 * 2 + 1])
        val live = EngineCore(program)
        try {
            live.controls.offer(EngineCommand.StartSequence(0, 1))
            val monitored = FloatArray((125_000 + live.latencyFrames) * 2)
            live.render(monitored)
            assertEquals(72, live.latencyFrames)
            assertContentEquals(output, monitored.copyOfRange(72 * 2, monitored.size))
        } finally { live.close() }
    }

    @Test fun sourceHandAndClickBypassMusicFxAndAreNeverPresentInProductionStemsOrExport() {
        val pcm = PcmAsset.fromInterleaved(FloatArray(4096 * 2) { if (it % 2 == 0) .015f else -.007f })
        fun monitor(mix: MixerProgram, mode: EngineOutputMode): Pair<FloatArray, FloatArray> {
            val engine = EngineCore(EngineProgram(mixer = mix), EngineConfig(outputMode = mode, controlCapacity = 16))
            try {
                engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(pcm, 0, 4096, loop = true)))
                engine.controls.offer(EngineCommand.PlayOriginalSource(0, 2))
                engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 3, 1000.0, 0, 4096))
                engine.controls.offer(EngineCommand.ScratchOriginalPosition(0, 4, 2000.0, 500))
                engine.controls.offer(EngineCommand.SetMetronome(0, 5, true))
                engine.controls.offer(EngineCommand.StartSequence(0, 6))
                val output = FloatArray(2048 * 2)
                val stems = FloatArray(2048 * MixerProgram.STEM_COUNT * 2)
                engine.render(output, stemOutput = stems)
                return output to stems
            } finally { engine.close() }
        }
        val mutedMusic = MixerProgram(settings = MixSettings(masterGain = 0f, master = MixInsert(MixEq(18f, 18f, 18f))))
        val ordinary = monitor(MixerProgram.BYPASS, EngineOutputMode.MONITOR)
        val processed = monitor(mutedMusic, EngineOutputMode.MONITOR)
        assertTrue(ordinary.first.any { it != 0f })
        assertContentEquals(ordinary.first, processed.first)
        assertTrue(processed.second.all { it == 0f })
        val exported = monitor(mutedMusic, EngineOutputMode.EXPORT)
        assertTrue(exported.first.all { it == 0f }); assertTrue(exported.second.all { it == 0f })
    }

    @Test fun fxHeadroomStillEndsAtMinusOneDbSampleCeilingAndPanicFlushesItsRings() {
        val pcm = PcmAsset.fromInterleaved(FloatArray(2048 * 2) { if (it % 2 == 0) 2f else -1f })
        val program = EngineProgram(listOf(Pad(0, pcm, 0, 2048, mode = PlayMode.LOOP, gain = 8f, mixBus = 0)),
            mixer = MixerProgram(listOf(TrackFx(MixInsert(MixEq(18f, 18f, 18f)), 1f, 1f)),
                MixSettings(MixDelay(true, 31, .6f, 2f), MixReverb(true, 3f, .95f, 2f), masterGain = 8f)))
        val engine = EngineCore(program)
        try {
            engine.controls.offer(EngineCommand.Trigger(0, 1, 0))
            val output = FloatArray(4096 * 2); engine.render(output)
            assertTrue(output.all { it.isFinite() && abs(it) <= MasterLimiter.CEILING.toFloat() })
            assertTrue(output.any { abs(it) > .8f })
            engine.controls.offer(EngineCommand.Panic(engine.frame, 2)); engine.render(output)
            assertTrue(output.all { it == 0f })
        } finally { engine.close() }
    }

    @Test fun stopAllAlsoFadesTheMastersStoredFilterResponseBeforeClearingIt() {
        val pcm = PcmAsset.fromInterleaved(FloatArray(2048) { .2f })
        val program = EngineProgram(listOf(Pad(0, pcm, mode = PlayMode.LOOP, attackFrames = 0)),
            mixer = MixerProgram(settings = MixSettings(master = MixInsert(filter = MixFilter(MixFilterMode.LOW_PASS, 20f)))))
        val engine = EngineCore(program)
        try {
            engine.controls.offer(EngineCommand.Trigger(0, 1, 0)); engine.render(FloatArray(48_000 * 2))
            engine.controls.offer(EngineCommand.StopAll(engine.frame, 2))
            val output = FloatArray(400 * 2); engine.render(output)
            val end = EngineCore.STEAL_FADE_FRAMES + engine.latencyFrames
            assertTrue(output.take(72 * 2).any { it > .19f })
            val resetEdge = (end - 4..end + 2).maxOf { abs(output[it * 2] - output[(it - 1) * 2]) }
            assertTrue(resetEdge <= .001f, "The master filter state must not become a hard cut: edge=$resetEdge")
            assertTrue(output.drop(end * 2).all { it == 0f })
        } finally { engine.close() }
    }
}
