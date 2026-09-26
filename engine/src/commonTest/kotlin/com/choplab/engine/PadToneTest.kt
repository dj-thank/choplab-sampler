package com.choplab.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

/** A PAD's tone is a one-pole low-pass on its voice, bypassed bit for bit at the top of the range. */
class PadToneTest {
    private fun sine(frequency: Double, frames: Int = 48_000, level: Double = .5) =
        PcmAsset.fromMono(FloatArray(frames) { (level * sin(2 * PI * frequency * it / EngineFormat.SAMPLE_RATE)).toFloat() })
    /** Steady looped playback with no envelope, so the output is the filtered source itself. */
    private fun steady(asset: PcmAsset, tone: Float) = EngineProgram(listOf(Pad(0, asset, mode = PlayMode.LOOP, attackFrames = 0,
        loopCrossfadeFrames = 0, tone = tone)))
    private fun rms(samples: FloatArray, fromFrame: Int, channel: Int = 0): Double {
        var sum = 0.0; var count = 0
        for (frame in fromFrame until samples.size / 2) { val v = samples[frame * 2 + channel].toDouble(); sum += v * v; count++ }
        return sqrt(sum / count)
    }

    @Test fun topOfTheRangeRendersExactlyAsWithoutTone() {
        val source = PcmAsset.fromInterleaved(FloatArray(8_000) { if (it % 2 == 0) (.3 * sin(it * .37)).toFloat() else (-.2 * sin(it * .11)).toFloat() })
        val commands = listOf(EngineCommand.Trigger(0, 0, 0), EngineCommand.Trigger(1_000, 1, 0, .6f))
        val plain = OfflineRender.render(EngineProgram(listOf(Pad(0, source))), commands, 5_000)
        for (tone in listOf(1f, Pad.TONE_BYPASS)) {
            assertContentEquals(plain, OfflineRender.render(EngineProgram(listOf(Pad(0, source, tone = tone))), commands, 5_000), "tone=$tone")
        }
        assertFalse(plain.contentEquals(OfflineRender.render(EngineProgram(listOf(Pad(0, source, tone = .99f))), commands, 5_000)))
    }

    @Test fun curveMatchesTheEarlierAppFrom80HzUpward() {
        for (tone in listOf(0f, .15f, .5f, .65f, .99f)) {
            val corner = 80.0 * 225.0.pow(tone.toDouble())
            val earlier = (1.0 - exp(-2.0 * PI * corner / 48_000)).toFloat()
            assertEquals(earlier.toDouble(), Pad(0, sine(100.0, 16), tone = tone).toneAlpha, 1e-6, "tone=$tone")
        }
        assertEquals(1.0, Pad(0, sine(100.0, 16), tone = 1f).toneAlpha)
        assertFailsWith<IllegalArgumentException> { Pad(0, sine(100.0, 16), tone = 1.01f) }
        assertFailsWith<IllegalArgumentException> { Pad(0, sine(100.0, 16), tone = -.01f) }
        assertFailsWith<IllegalArgumentException> { Pad(0, sine(100.0, 16), tone = Float.NaN) }
    }

    @Test fun darkToneCutsHighsAndKeepsLows() {
        // tone .3 puts the corner near 406 Hz: a 100 Hz tone passes, a 10 kHz tone is far below it.
        val settle = 4_800
        val low = OfflineRender.render(steady(sine(100.0), .3f), listOf(EngineCommand.Trigger(0, 0, 0)), 48_000)
        val high = OfflineRender.render(steady(sine(10_000.0), .3f), listOf(EngineCommand.Trigger(0, 0, 0)), 48_000)
        val lowOpen = OfflineRender.render(steady(sine(100.0), 1f), listOf(EngineCommand.Trigger(0, 0, 0)), 48_000)
        val highOpen = OfflineRender.render(steady(sine(10_000.0), 1f), listOf(EngineCommand.Trigger(0, 0, 0)), 48_000)
        val lowLoss = 20 * log10(rms(low, settle) / rms(lowOpen, settle))
        val highLoss = 20 * log10(rms(high, settle) / rms(highOpen, settle))
        assertTrue(lowLoss > -1.0, "100 Hz stays within 1 dB: $lowLoss")
        assertTrue(highLoss < -25.0, "10 kHz drops by more than 25 dB: $highLoss")
        assertTrue((low + high).all { it.isFinite() })
    }

    @Test fun filteredStereoKeepsChannelsAndIsIndependentOfBlockSize() {
        val frames = 6_000
        val interleaved = FloatArray(frames * 2) { if (it % 2 == 0) (.4 * sin(it * .9)).toFloat() else (-.3 * sin(it * .05)).toFloat() }
        val program = steady(PcmAsset.fromInterleaved(interleaved), .45f)
        val commands = listOf(EngineCommand.Trigger(0, 0, 0))
        val reference = OfflineRender.render(program, commands, 4_000, blockFrames = 1)
        for (block in intArrayOf(17, 96, 192, 480, 4096)) assertContentEquals(reference, OfflineRender.render(program, commands, 4_000, blockFrames = block), "block=$block")

        // Each channel is its own one-pole filter over that channel only; left and right never mix or swap.
        val alpha = program.pad(0)!!.toneAlpha
        var left = 0.0; var right = 0.0
        for (frame in 0 until 4_000) {
            left += alpha * (interleaved[frame * 2] - left); right += alpha * (interleaved[frame * 2 + 1] - right)
            assertEquals(left, reference[frame * 2].toDouble(), 1e-6, "left $frame")
            assertEquals(right, reference[frame * 2 + 1].toDouble(), 1e-6, "right $frame")
        }
    }
}
