package com.choplab.engine

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

/** The original's song key is varispeed: pitch and tempo move together, and zero semitones stays sample-exact. */
class OriginalPitchTest {
    private fun tone(frames: Int, frequency: Double = 441.0) = PcmAsset.fromInterleaved(FloatArray(frames * 2) {
        val value = .4 * sin(2 * PI * frequency * (it / 2) / EngineFormat.SAMPLE_RATE)
        (if (it % 2 == 0) value else -.5 * value).toFloat()
    })

    /** Plays [source] on the audition voice only, after the given commands, in [block]-frame renders. */
    private fun play(source: OriginalSource, frames: Int, vararg before: (Long) -> EngineCommand, block: Int = 256): Pair<FloatArray, EngineCore> {
        val engine = EngineCore()
        var id = 0L
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, id++, source)))
        for (command in before) assertEquals(OfferResult.ACCEPTED, engine.controls.offer(command(id++)))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.PlayOriginalSource(0, id)))
        val output = FloatArray(frames * 2)
        var at = 0
        while (at < frames) { val count = minOf(block, frames - at); engine.render(output, at, count); at += count }
        return output to engine
    }
    private fun crossings(samples: FloatArray, fromFrame: Int): Int =
        (fromFrame + 1 until samples.size / 2).count { (samples[(it - 1) * 2] < 0f) != (samples[it * 2] < 0f) }

    @Test fun zeroSemitonesPlaysExactlyAsBeforeTheKeyExisted() {
        val source = OriginalSource(tone(6_000), 50, 5_900)
        val plain = play(source, 4_000).first
        assertContentEquals(plain, play(source, 4_000, { EngineCommand.SetOriginalPitch(0, it, 0f) }).first)
        // A key that returns to zero is exact again, not an accumulated rounding of 2^(st/12).
        assertContentEquals(plain, play(source, 4_000, { EngineCommand.SetOriginalPitch(0, it, 7f) }, { EngineCommand.SetOriginalPitch(0, it, 0f) }).first)
    }

    @Test fun anOctaveUpPlaysTwiceAsFastAndAnOctaveDownHalfAsFast() {
        val source = OriginalSource(tone(48_000), 100, 47_000)
        for ((semitones, sourceFrames) in listOf(12f to 2_000L, -12f to 500L, 24f to 4_000L, -24f to 250L, 0f to 1_000L)) {
            val (output, engine) = play(source, 1_000, { EngineCommand.SetOriginalPitch(0, it, semitones) })
            assertEquals(100L + sourceFrames, engine.originalSourceFrame, "$semitones st")
            val readout = EngineSnapshot().also { assertTrue(engine.readout.copyInto(it)) }
            assertEquals(100L + sourceFrames, readout.originalSourceFrame)
            assertTrue(output.all { it.isFinite() })
        }
        // The heard pitch follows: an octave up crosses zero twice as often as the original.
        val plain = play(source, 20_000).first
        val up = play(source, 20_000, { EngineCommand.SetOriginalPitch(0, it, 12f) }).first
        val ratio = crossings(up, 2_000).toDouble() / crossings(plain, 2_000)
        assertEquals(2.0, ratio, .02, "zero crossings ratio")
    }

    @Test fun aPitchedLoopRunsOnAcrossItsSeam() {
        // 999 frames at two source frames per output frame: the seam falls between reads and the offset carries over.
        val source = OriginalSource(tone(2_000), 100, 1_099, loop = true)
        for (frames in intArrayOf(400, 499, 500, 1_000, 3_333)) {
            val engine = play(source, frames, { EngineCommand.SetOriginalPitch(0, it, 12f) }).second
            assertTrue(engine.originalPlaying)
            assertEquals(100L + (2L * frames) % 999, engine.originalSourceFrame, "after $frames frames")
        }
        val slow = play(source, 2_000, { EngineCommand.SetOriginalPitch(0, it, -12f) }).second
        assertEquals(100L + 1_000 % 999, slow.originalSourceFrame)
    }

    @Test fun theKeyStaysThroughSeekPauseStopAndANewSource() {
        val asset = tone(48_000)
        val engine = EngineCore()
        var id = 0L
        fun offer(command: (Long) -> EngineCommand) = assertEquals(OfferResult.ACCEPTED, engine.controls.offer(command(id++)))
        fun advance(frames: Int) = engine.render(FloatArray(frames * 2))
        offer { EngineCommand.SetOriginalSource(0, it, OriginalSource(asset)) }
        offer { EngineCommand.SetOriginalPitch(0, it, 12f) }
        offer { EngineCommand.PlayOriginalSource(0, it) }
        advance(100)
        assertEquals(200L, engine.originalSourceFrame)
        offer { EngineCommand.SeekOriginalSource(100, it, 1_000) }
        advance(100)
        assertEquals(1_200L, engine.originalSourceFrame)
        offer { EngineCommand.PauseOriginalSource(200, it) }
        offer { EngineCommand.PlayOriginalSource(200, it) }
        advance(100)
        assertEquals(1_400L, engine.originalSourceFrame)
        offer { EngineCommand.Stop(300, it) }
        offer { EngineCommand.SetOriginalSource(300, it, OriginalSource(tone(20_000), 10)) }
        offer { EngineCommand.PlayOriginalSource(300, it) }
        advance(100)
        assertEquals(210L, engine.originalSourceFrame, "A new source plays at the same key")
    }

    @Test fun onlyMonitoringHasAKeyAndItsRangeIsBounded() {
        val export = EngineCore(EngineProgram.EMPTY, EngineConfig(outputMode = EngineOutputMode.EXPORT))
        assertEquals(OfferResult.MONITOR_DISABLED, export.controls.offer(EngineCommand.SetOriginalPitch(0, 0, 5f)))
        EngineCommand.SetOriginalPitch(0, 0, 24f); EngineCommand.SetOriginalPitch(0, 0, -24f)
        for (bad in floatArrayOf(24.01f, -24.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("$bad") { EngineCommand.SetOriginalPitch(0, 0, bad) }
        }
    }
}
