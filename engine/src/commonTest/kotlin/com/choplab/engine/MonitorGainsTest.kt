package com.choplab.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitorGainsTest {
    private fun pcm() = PcmAsset.fromInterleaved(FloatArray(2_048 * 2) {
        if (it % 2 == 0) .125f else -.0625f
    })

    private fun EngineCore.offer(command: EngineCommand) =
        assertEquals(OfferResult.ACCEPTED, controls.offer(command))

    private fun EngineCore.startOriginal(asset: PcmAsset, hand: Boolean = false) {
        offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        offer(EngineCommand.PlayOriginalSource(0, 1))
        if (hand) {
            offer(EngineCommand.ScratchOriginalStart(0, 2, 100.0, 0, asset.frameCount))
            offer(EngineCommand.ScratchOriginalPosition(0, 3, 612.0, 512))
        }
    }

    private fun originalOutput(gains: MonitorGains? = null, hand: Boolean = false): FloatArray {
        val engine = if (gains == null) EngineCore() else EngineCore(monitorGains = gains)
        return try {
            engine.startOriginal(pcm(), hand)
            FloatArray(512 * 2).also { engine.render(it) }
        } finally { engine.close() }
    }

    @Test fun mutedSourceAndHandAreSilentFromTheirFirstRenderedSignal() {
        val engine = EngineCore(monitorGains = MonitorGains(original = 0f, hand = 0f))
        try {
            val initial = EngineSnapshot()
            assertTrue(engine.readout.copyInto(initial))
            assertEquals(0f, initial.originalMonitorGain)
            assertEquals(0f, initial.handMonitorGain)
            assertFalse(initial.originalLoaded || initial.originalPlaying)
            assertEquals(-1.0, initial.handSourceFrame)
            engine.startOriginal(pcm(), hand = true)
            val output = FloatArray(512 * 2)
            engine.render(output)
            assertTrue(output.all { it == 0f }, "No unity-gain ramp may leak into the first block")
            assertTrue(engine.originalPlaying)
            assertEquals(512L, engine.originalSourceFrame, "Muting keeps SOURCE transport running")
            assertEquals(612.0, engine.handSourceFrame, 1e-8, "Muting keeps HAND transport running")
            assertTrue(originalOutput(hand = true).any { it != 0f }, "The same source and movement are audible at default gain")
        } finally { engine.close() }
    }

    @Test fun fractionalSongGainScalesBothChannelsFromTheFirstSignal() {
        val asset = pcm()
        val program = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("song", asset, 0))))
        val baseline = EngineCore(program)
        val quiet = EngineCore(program, monitorGains = MonitorGains(song = .25f))
        try {
            val initial = EngineSnapshot()
            assertTrue(quiet.readout.copyInto(initial))
            assertEquals(.25f, initial.songMonitorGain)
            baseline.offer(EngineCommand.StartSequence(0, 0))
            quiet.offer(EngineCommand.StartSequence(0, 0))
            val expected = FloatArray(512 * 2).also { baseline.render(it) }
            val actual = FloatArray(512 * 2).also { quiet.render(it) }
            assertTrue(expected.indices.any { it % 2 == 0 && expected[it] > 0f })
            assertTrue(expected.indices.any { it % 2 == 1 && expected[it] < 0f })
            for (sample in expected.indices) {
                assertEquals(expected[sample] * .25f, actual[sample], 1e-8f, "Stereo sample $sample")
            }
            assertEquals(baseline.sequenceFrame, quiet.sequenceFrame)
        } finally { baseline.close(); quiet.close() }
    }

    @Test fun exportIgnoresMonitorSeedsAndStillRejectsSourcePlayback() {
        val asset = pcm()
        val program = EngineProgram(arrangement = Arrangement(listOf(ArrangementClip("song", asset, 0))))
        val config = EngineConfig(outputMode = EngineOutputMode.EXPORT)
        val baseline = EngineCore(program, config)
        val seeded = EngineCore(program, config, MonitorGains(original = 0f, song = 0f, hand = 0f))
        try {
            assertEquals(OfferResult.MONITOR_DISABLED, seeded.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset))))
            assertEquals(OfferResult.MONITOR_DISABLED, seeded.controls.offer(EngineCommand.PlayOriginalSource(0, 1)))
            baseline.offer(EngineCommand.StartSequence(0, 2))
            seeded.offer(EngineCommand.StartSequence(0, 2))
            val expected = FloatArray(512 * 2).also { baseline.render(it) }
            val actual = FloatArray(512 * 2).also { seeded.render(it) }
            assertTrue(expected.any { it != 0f }, "A muted listening preference cannot mute production export")
            assertContentEquals(expected, actual)
            assertFalse(seeded.originalLoaded || seeded.originalPlaying)
        } finally { baseline.close(); seeded.close() }
    }

    @Test fun defaultAndMaximumOriginalGainKeepTheirAudioMeaningAndRejectInvalidSeeds() {
        val baseline = originalOutput()
        assertContentEquals(baseline, originalOutput(MonitorGains()), "Explicit defaults preserve existing playback bytes")
        val doubled = originalOutput(MonitorGains(original = 2f, song = 0f, hand = 0f))
        assertTrue(baseline.any { it != 0f })
        for (sample in baseline.indices) {
            assertEquals(baseline[sample] * 2f, doubled[sample], 1e-8f, "The valid SOURCE maximum is two, sample $sample")
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.001f)) {
            assertFailsWith<IllegalArgumentException> { MonitorGains(original = invalid) }
            assertFailsWith<IllegalArgumentException> { MonitorGains(song = invalid) }
            assertFailsWith<IllegalArgumentException> { MonitorGains(hand = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { MonitorGains(original = 2.001f) }
        assertFailsWith<IllegalArgumentException> { MonitorGains(song = 1.001f) }
        assertFailsWith<IllegalArgumentException> { MonitorGains(hand = 1.001f) }
    }
}
