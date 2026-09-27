package com.choplab.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DspContractTest {
    private fun amplitude(samples: FloatArray, rate: Int, frequency: Double, start: Int, end: Int, channel: Int = 0): Double {
        var sine = 0.0; var cosine = 0.0
        for (frame in start until end) {
            val phase = 2 * PI * frequency * frame / rate
            sine += samples[frame * 2 + channel] * sin(phase)
            cosine += samples[frame * 2 + channel] * cos(phase)
        }
        return 2 * sqrt(sine * sine + cosine * cosine) / (end - start)
    }
    private fun multitone(rate: Int, frequencies: DoubleArray, level: Double = 0.05): FloatArray = FloatArray(rate * 2) { index ->
        var value = 0.0
        for (f in frequencies) value += level * sin(2 * PI * f * (index / 2) / rate)
        (if (index % 2 == 0) value else -value * .5).toFloat()
    }

    @Test fun resamplingMeasuresPassbandAndStopbandForAllRequiredRatePairs() {
        val frequencies = doubleArrayOf(20.0, 1000.0, 10_000.0, 19_000.0, 20_000.0)
        for ((from, to) in listOf(44100 to 48000, 48000 to 44100, 96000 to 48000)) {
            val rendered = OfflineResampler.resample(multitone(from, frequencies), from, to)
            assertEquals(to * 2, rendered.size)
            var largestDb = 0.0
            for (f in frequencies) {
                val left = amplitude(rendered, to, f, to / 10, to * 9 / 10)
                val right = amplitude(rendered, to, f, to / 10, to * 9 / 10, 1)
                val error = abs(20 * log10(left / .05))
                largestDb = maxOf(largestDb, error)
                assertTrue(error <= .05, "$from->$to $f Hz pass error=$error dB")
                assertTrue(abs(right / left - .5) < 1e-6)
            }
            val stopInput = when (from) { 44100 -> 22000.0; 48000 -> 23000.0; else -> 25000.0 }
            val image = when (from) { 44100 -> 22100.0; 48000 -> 21100.0; else -> 23000.0 }
            val stop = OfflineResampler.resample(multitone(from, doubleArrayOf(stopInput), .1), from, to)
            val stopDb = 20 * log10(amplitude(stop, to, image, to / 10, to * 9 / 10) / .1)
            println("RESAMPLE $from->$to passMaxDb=$largestDb stopAt=${stopInput}Hz imageDb=$stopDb")
            assertTrue(stopDb <= -100, "$from->$to stop attenuation=$stopDb dB")
        }
    }

    @Test fun resampleImpulseHasCompensatedGroupDelayAndPreservesStereo() {
        val input = FloatArray(4410 * 2)
        input[2205 * 2] = .5f
        input[2205 * 2 + 1] = -.25f
        val output = OfflineResampler.resample(input, 44100)
        var peak = 0
        for (frame in 1 until output.size / 2) if (abs(output[frame * 2]) > abs(output[peak * 2])) peak = frame
        assertEquals(2400, peak)
        for (frame in 0 until output.size / 2) assertTrue(-output[frame * 2] / 2 == output[frame * 2 + 1])
        assertFailsWith<IllegalArgumentException> { OfflineResampler.resample(floatArrayOf(Float.NaN, 0f), 44100) }
    }

    @Test fun octavePitchMeasuresPassbandFoldbackAndStopbandSweep() {
        val interpolation = PitchInterpolator()
        for (frequency in doubleArrayOf(1000.0, 9000.0, 13_100.0, 17_000.0, 21_000.0)) {
            val source = PcmAsset.fromInterleaved(multitone(48000, doubleArrayOf(frequency), .1))
            val output = FloatArray(24000 * 2)
            for (frame in 0 until 24000) {
                output[frame * 2] = interpolation.sample(source, frame * 2.0, 2.0, 0)
                output[frame * 2 + 1] = interpolation.sample(source, frame * 2.0, 2.0, 1)
            }
            val target = if (frequency < 12000) frequency * 2 else 48000 - frequency * 2
            val level = amplitude(output, 48000, target, 2400, 21600)
            val relativeDb = 20 * log10(level / .1)
            println("PITCH +12 input=$frequency output=$target relativeDb=$relativeDb dBFS=${20 * log10(level)}")
            if (frequency < 12000) assertTrue(abs(relativeDb) < .05, "pass $frequency: $relativeDb")
            else assertTrue(relativeDb <= -70, "fold-back $frequency: $relativeDb")
        }
        val sweep = FloatArray(48000 * 2) { i ->
            val t = (i / 2) / 48000.0
            (.1 * sin(2 * PI * (12000 * t + (23000 - 12000) * t * t / 2))).toFloat()
        }
        val source = PcmAsset.fromInterleaved(sweep)
        var energy = 0.0
        for (f in 1000 until 23000) {
            val sample = interpolation.sample(source, f * 2.0, 2.0, 0).toDouble()
            energy += sample * sample
        }
        val rms = sqrt(energy / 22000)
        val relativeDb = 20 * log10(rms / (.1 / sqrt(2.0)))
        println("PITCH stop sweep12-23kHz relativeRmsDb=$relativeDb dBFS=${20 * log10(rms)}")
        assertTrue(relativeDb <= -70, "sweep fold-back=$relativeDb")
    }

    @Test fun limiterBoundsOverloadPreservesStereoAndFlushesExactDelay() {
        val limiter = MasterLimiter()
        for (frame in 0 until 2000) {
            val left = if (frame < 500) 1000.0 else if (frame < 1000) -4.0 else 0.0
            limiter.process(left, left / -2)
            assertTrue(limiter.outputLeft.isFinite() && limiter.outputRight.isFinite())
            assertTrue(abs(limiter.outputLeft) <= MasterLimiter.CEILING.toFloat())
            assertTrue(-limiter.outputLeft / 2 == limiter.outputRight)
            if (frame >= 1000 + 72) assertEquals(0f, limiter.outputLeft)
        }
        limiter.reset()
        limiter.process(.5, -.25)
        assertEquals(0f, limiter.outputLeft)
        repeat(71) { limiter.process(0.0, 0.0); assertEquals(0f, limiter.outputLeft) }
        limiter.process(0.0, 0.0)
        assertEquals(.5f, limiter.outputLeft)
        assertEquals(-.25f, limiter.outputRight)
        limiter.process(Double.NaN, Double.POSITIVE_INFINITY)
        assertTrue(limiter.outputLeft.isFinite())
    }

    @Test fun continuousLoopHasNoResidualAgainstItsCrossfadeAndKeepsOriginalPeriod() {
        val raw = FloatArray(256 * 2) { (.1 * sin(2 * PI * (it / 2) / 256)).toFloat() }
        val source = PcmAsset.fromInterleaved(raw)
        val program = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 48)))
        val output = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0)), 4096)
        var residual = 0.0
        for (frame in 0 until 4096) {
            val p = frame % 256
            val phase = if (p >= 208) .5 * (p - 208) / 47 else .5 + .5 * p / 47
            val weight = 3 * phase * phase - 2 * phase * phase * phase
            val expected = when {
                p >= 208 -> raw[p * 2] * (1 - weight) + raw[0] * weight
                p < 48 -> raw[255 * 2] * (1 - weight) + raw[p * 2] * weight
                else -> raw[p * 2].toDouble()
            }
            residual = maxOf(residual, abs(output[frame * 2] - expected))
        }
        assertTrue(residual <= .0001, "continuous tone boundary residual=$residual")
        val dc = PcmAsset.fromMono(FloatArray(256) { if (it < 128) .4f else .2f })
        val faded = OfflineRender.render(EngineProgram(listOf(Pad(0, dc, mode = PlayMode.LOOP,
            attackFrames = 0, loopCrossfadeFrames = 48))), listOf(EngineCommand.Trigger(0, 0, 0)), 1024)
        for (frame in 0 until 1024) {
            val p = frame % 256
            assertTrue(faded[frame * 2] in .2f.. .4f, "crossfade must stay in endpoint range")
        }
        for (frame in 0 until 768) assertEquals(faded[frame * 2], faded[(frame + 256) * 2])
        assertEquals(.3f, faded[255 * 2]); assertEquals(.3f, faded[256 * 2])
    }

    @Test fun endpointCrossfadeNeverDipsConstantStereoAtPitchOrForShortLoops() {
        for (length in intArrayOf(1, 2, 3, 17, 256)) {
            val source = PcmAsset.fromInterleaved(FloatArray(length * 2) { if (it % 2 == 0) .2f else -.1f })
            for (pitch in doubleArrayOf(0.0, 12.0, -12.0)) {
                val program = EngineProgram(listOf(Pad(0, source, mode = PlayMode.LOOP,
                    attackFrames = 0, pitchSemitones = pitch, reverse = true, loopCrossfadeFrames = 48)))
                val output = OfflineRender.render(program, listOf(EngineCommand.Trigger(0, 0, 0)), 1000)
                for (frame in 0 until 1000) {
                    assertTrue(abs(output[frame * 2] - .2f) < .000001, "constant left length=$length pitch=$pitch")
                    assertTrue(abs(output[frame * 2 + 1] + .1f) < .000001, "constant right length=$length pitch=$pitch")
                }
            }
        }
        val short = EngineProgram(listOf(Pad(0, PcmAsset.fromInterleaved(floatArrayOf(.4f, -.1f, .2f, .3f)),
            mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 48)))
        val result = OfflineRender.render(short, listOf(EngineCommand.Trigger(0, 0, 0)), 20)
        for (frame in 0 until 20) {
            assertTrue(abs(result[frame * 2] - .3f) < .000001)
            assertTrue(abs(result[frame * 2 + 1] - .1f) < .000001)
        }
    }

    @Test fun zeroCrossingAndOnsetUseBothChannels() {
        val source = PcmAsset.fromInterleaved(FloatArray(4800 * 2) { i ->
            val frame = i / 2
            val value = if (frame in 2400..2879) (.2 * sin(2 * PI * (frame - 2400) / 48)).toFloat() else 0f
            if (i % 2 == 0) value else -value
        })
        assertTrue(abs(SampleAnalysis.nearestZeroCrossing(source, 2449, 10) - 2448) <= 1)
        assertTrue(SampleAnalysis.onsetFrames(source).contains(2400))
        val meter = StereoMeter()
        val asymmetry = floatArrayOf(.5f, -.25f, -.5f, .25f)
        meter.measure(asymmetry)
        assertEquals(.5, meter.rmsLeft); assertEquals(.25, meter.rmsRight)
    }
}
