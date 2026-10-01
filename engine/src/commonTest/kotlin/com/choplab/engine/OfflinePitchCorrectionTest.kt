package com.choplab.engine

import kotlin.math.*
import kotlin.test.*

class OfflinePitchCorrectionTest {
    private fun voice(seconds: Double, hz: Double, vibrato: Double = 0.0): FloatArray {
        var phase = 0.0
        return FloatArray((seconds * 48_000).toInt() * 2).also { data ->
            for (frame in 0 until data.size / 2) {
                phase += 2 * PI * hz * 2.0.pow(vibrato * sin(2 * PI * 5 * frame / 48_000.0) / 1200) / 48_000
                data[frame * 2] = (sin(phase) * .6).toFloat()
                data[frame * 2 + 1] = -data[frame * 2] * .25f
            }
        }
    }
    private fun correct(data: FloatArray, settings: PitchCorrectionSettings = PitchCorrectionSettings(retuneMs = 0f, vibrato = 0f)):
        Pair<FloatArray, PitchCorrectionReport> {
        val out = FloatArray(data.size)
        var frame = 0
        val report = OfflinePitchCorrection.process(PitchPcmReader { start, count, target ->
            data.copyInto(target, 0, start * 2, (start + count) * 2); count
        }, data.size / 2, settings, { block, count -> block.copyInto(out, frame * 2, 0, count * 2); frame += count })
        assertEquals(data.size / 2, frame)
        assertTrue(out.all { it.isFinite() })
        return out to report
    }
    /** Independent waveform crossing oracle, not the correction's YIN estimator. */
    private fun frequency(data: FloatArray, first: Int, end: Int): Double {
        val crossings = mutableListOf<Double>()
        for (frame in first + 1 until end) {
            val a = data[(frame - 1) * 2]; val b = data[frame * 2]
            if (a <= 0 && b > 0) crossings += frame - 1 + (-a / (b - a)).toDouble()
        }
        require(crossings.size > 3)
        return 48_000 * (crossings.size - 1) / (crossings.last() - crossings.first())
    }
    private fun cents(actual: Double, expected: Double) = 1200 * log2(actual / expected)

    @Test fun correctsDetunedNotesWithoutResamplingDurationOrCollapsingStereo() {
        for (target in listOf(55.0, 110.0, 220.0, 440.0, 880.0)) {
            val input = voice(.7, target * 2.0.pow(32.0 / 1200))
            val before = input.copyOf()
            val (output, report) = correct(input)
            val measured = frequency(output, 12_000, 28_800)
            println("pitch target=$target measured=$measured errorCents=${cents(measured, target)} corrected=${report.correctedFrames}")
            assertTrue(abs(cents(measured, target)) < 4, "target=$target actual=$measured")
            assertTrue(report.correctedFrames > input.size / 2 - 12_000)
            assertEquals(0, report.outputDelayFrames)
            assertContentEquals(before, input)
            for (frame in output.indices step 2) assertEquals(-output[frame] * .25f, output[frame + 1], .000001f)
            assertContentEquals(input.copyOfRange(0, 960), output.copyOfRange(0, 960))
        }
    }

    @Test fun amountAndKeyScaleChangeTheTargetButUnityIsSampleExact() {
        val input = voice(.8, 440.0 * 2.0.pow(40.0 / 1200))
        val (half, _) = correct(input, PitchCorrectionSettings(amount = .5f, retuneMs = 0f, vibrato = 0f))
        assertTrue(abs(cents(frequency(half, 12_000, 32_000), 440.0) - 20) < 4)
        assertContentEquals(input, correct(input, PitchCorrectionSettings(amount = 0f)).first)
        val bFlat = voice(.8, 466.1637615)
        // Stay off the exact A/B midpoint: tiny analysis error there may choose either permitted note.
        assertEquals(69, PitchCorrectionSettings(scale = PitchScale.MAJOR).nearest(70.0, null))
        val (major, _) = correct(voice(.8, 466.1637615 * 2.0.pow(-20.0 / 1200)),
            PitchCorrectionSettings(scale = PitchScale.MAJOR, retuneMs = 0f, vibrato = 0f))
        assertTrue(abs(cents(frequency(major, 12_000, 32_000), 440.0)) < 4)
        val (minor, report) = correct(bFlat, PitchCorrectionSettings(scale = PitchScale.NATURAL_MINOR, retuneMs = 0f, vibrato = 0f))
        assertTrue(bFlat.contentEquals(minor), "A tuned note must bypass the OLA: $report")
        assertEquals(0, report.correctedFrames)
        for (key in 0..11) assertEquals(listOf(0, 2, 4, 5, 7, 9, 11), (0..11).filter {
            PitchCorrectionSettings(key, PitchScale.MAJOR).permits(key + it)
        })
    }

    @Test fun noiseUnsupportedRangesStereoDisagreementAndOctaveAmbiguityKeepOriginal() {
        var random = 71
        val noise = FloatArray(24_000 * 2) { random = random * 1_664_525 + 1_013_904_223; ((random ushr 8) / 8388608f - 1f) * .2f }
        val stereo = voice(.5, 220.0).also { data -> for (f in 0 until data.size / 2) data[f * 2 + 1] = (.5 * sin(2 * PI * 330 * f / 48_000)).toFloat() }
        val ambiguous = FloatArray(24_000 * 2) { i ->
            (.6 * sin(2 * PI * 440 * (i / 2) / 48_000) + .02 * sin(2 * PI * 220 * (i / 2) / 48_000)).toFloat()
        }
        for (input in listOf(noise, stereo, ambiguous, voice(.5, 40.0), voice(.5, 1200.0))) {
            val (output, report) = correct(input)
            assertContentEquals(input, output)
            assertEquals(0, report.correctedFrames, report.toString())
        }
    }

    @Test fun retuneSlowsCorrectionAndVibratoRetentionKeepsModulation() {
        val input = voice(1.5, 440.0 * 2.0.pow(35.0 / 1200), 23.0)
        val (flat, _) = correct(input)
        val (vibrato, _) = correct(input, PitchCorrectionSettings(retuneMs = 0f, vibrato = 1f))
        fun range(data: FloatArray): Double {
            val values = (15..60).map { step -> cents(frequency(data, step * 960, step * 960 + 1440), 440.0) }
            return values.max() - values.min()
        }
        println("pitch vibrato flattenedRange=${range(flat)} preservedRange=${range(vibrato)}")
        assertTrue(range(flat) < 8)
        assertTrue(range(vibrato) in 25.0..55.0)
        val steady = voice(.8, 440.0 * 2.0.pow(40.0 / 1200))
        val (fast, _) = correct(steady)
        val (slow, _) = correct(steady, PitchCorrectionSettings(retuneMs = 300f, vibrato = 0f))
        assertTrue(cents(frequency(slow, 4800, 7200), 440.0) > cents(frequency(fast, 4800, 7200), 440.0) + 15)
    }

    @Test fun consonantsAndRapidNoteChangesKeepTheirAbsoluteTimeAndOriginalNoise() {
        val input = voice(.9, 440 * 2.0.pow(35.0 / 1200))
        var random = 13
        for (frame in 19_200 until 24_000) {
            random = random * 1_664_525 + 1_013_904_223
            input[frame * 2] = ((random ushr 8) / 8388608f - 1f) * .5f
            input[frame * 2 + 1] = -input[frame * 2] * .25f
        }
        input[21_000 * 2] = 1.4f; input[21_000 * 2 + 1] = -.35f
        for (frame in 24_000 until input.size / 2) {
            input[frame * 2] = (.6 * sin(2 * PI * 659.255 * 2.0.pow(-35.0 / 1200) * frame / 48_000)).toFloat()
            input[frame * 2 + 1] = -input[frame * 2] * .25f
        }
        val (output, report) = correct(input)
        assertTrue(report.correctedFrames > 25_000)
        assertContentEquals(input.copyOfRange(20_000 * 2, 23_000 * 2), output.copyOfRange(20_000 * 2, 23_000 * 2))
        assertEquals(1.4f, output[21_000 * 2], "Consonant marker keeps both its frame and float headroom")
        assertTrue(abs(cents(frequency(output, 8_000, 15_000), 440.0)) < 4)
        assertTrue(abs(cents(frequency(output, 30_000, 40_000), 659.255)) < 4)
    }

    @Test fun psolaKeepsTheVowelEnvelopeInsteadOfResamplingItsFormants() {
        val hz = 57.9
        val input = FloatArray(48_000 * 2)
        for (harmonic in 1..70) {
            val f = hz * harmonic
            val magnitude = .02 / harmonic + exp(-((f - 700) / 65).pow(2)) + .5 * exp(-((f - 1300) / 100).pow(2))
            for (frame in 0 until 48_000) input[frame * 2] += (magnitude * sin(2 * PI * f * frame / 48_000)).toFloat()
        }
        val peak = input.maxOf { abs(it) }
        for (frame in 0 until 48_000) { input[frame * 2] *= .6f / peak; input[frame * 2 + 1] = -input[frame * 2] * .25f }
        val (output, report) = correct(input, PitchCorrectionSettings(scale = PitchScale.MAJOR, retuneMs = 0f, vibrato = 0f))
        fun power(data: FloatArray, frequency: Double): Double {
            val coefficient = 2 * cos(2 * PI * frequency / 48_000)
            var a = 0.0; var b = 0.0
            for (frame in 12_000 until 36_000) {
                val next = data[frame * 2] + coefficient * a - b
                b = a; a = next
            }
            return a * a + b * b - coefficient * a * b
        }
        val spectralPeak = (550..850 step 5).maxBy { power(output, it.toDouble()) }
        println("PSOLA vowel peak=$spectralPeak Hz, 715/660=${power(output, 715.0) / power(output, 660.0)}, reliableHops=${report.reliableHops}, bypass=${report.bypassHops}")
        assertTrue(report.correctedFrames > 36_000)
        assertTrue(spectralPeak in 690..735, "The ~700 Hz formant must not move to ~665 Hz with varispeed: $spectralPeak")
        assertTrue(power(output, 715.0) > power(output, 660.0) * 1.2)
        assertEquals(input.size, output.size)
    }

    @Test fun boundedWindowsCancellationAndInvalidAudioDoNotMutateTheSource() {
        val input = voice(.3, 225.0)
        val original = input.copyOf()
        var checks = 0
        assertFailsWith<IllegalStateException> {
            OfflinePitchCorrection.process(PitchPcmReader { first, count, out ->
                assertTrue(count <= 4096); input.copyInto(out, 0, first * 2, (first + count) * 2); count
            }, input.size / 2, PitchCorrectionSettings(), { _, _ -> }, { check(++checks < 12) { "Cancelled" } })
        }
        assertContentEquals(original, input)
        assertTrue(OfflinePitchCorrection.workspaceBytes(30_000_000) < 2 * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { OfflinePitchCorrection.workspaceBytes(30_000_001) }
        assertFailsWith<IllegalArgumentException> { correct(floatArrayOf(Float.NaN, 0f)) }
        assertFailsWith<IllegalArgumentException> { PitchCorrectionSettings(retuneMs = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { PitchCorrectionSettings(key = 12) }
    }
}
