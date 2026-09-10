package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaveformDisplayPolicyTest {
    @Test
    fun quietRecordingGetsBoundedDisplayGainWithoutChangingPcm() {
        val samples = shortArrayOf(-1_000, 0, 500, 1_000)
        val original = samples.copyOf()
        assertEquals(16f, waveformDisplayGain(samples))
        assertContentEquals(original, samples)
    }

    @Test
    fun ordinaryQuietRecordingFitsBelowFullHeight() {
        val samples = shortArrayOf(-4_096, 2_048)
        val gain = waveformDisplayGain(samples)
        assertEquals(6.4f, gain)
        assertEquals(0.8f, 4_096f / 32_768f * gain)
    }

    @Test
    fun silenceAndNearSilenceAreNotInflatedIntoApparentSignal() {
        assertEquals(1f, waveformDisplayGain(shortArrayOf()))
        assertEquals(1f, waveformDisplayGain(ShortArray(128)))
        assertEquals(1f, waveformDisplayGain(shortArrayOf(-32, 32)))
    }

    @Test
    fun loudRecordingIsNeverAttenuatedOrAmplified() {
        assertEquals(1f, waveformDisplayGain(shortArrayOf(Short.MAX_VALUE)))
        assertEquals(1f, waveformDisplayGain(shortArrayOf(Short.MIN_VALUE)))
    }

    @Test
    fun gainFindsOffGridPeaksAndUsesTheWholeSource() {
        val samples = ShortArray(200_001) { 100 }
        samples[199_999] = Short.MIN_VALUE
        assertEquals(1f, waveformDisplayGain(samples))
    }

    @Test
    fun oppositeStereoChannelsDoNotCancelTheDisplayPeak() {
        assertEquals(6.4f, waveformDisplayGain(shortArrayOf(4_096, -4_096)))
        val envelope = buildWaveformEnvelope(
            samples = shortArrayOf(16_384, -16_384),
            visibleStart = 0,
            visibleEnd = 1,
            pixelWidth = 2,
            channelCount = 2,
        )
        assertContentEquals(floatArrayOf(-0.5f), envelope.minimums)
        assertContentEquals(floatArrayOf(0.5f), envelope.maximums)
    }

    @Test
    fun aSilentStereoChannelDoesNotHalveTheOtherChannel() {
        val envelope = buildWaveformEnvelope(shortArrayOf(16_384, 0), 0, 1, 2, channelCount = 2)
        assertContentEquals(floatArrayOf(0f), envelope.minimums)
        assertContentEquals(floatArrayOf(0.5f), envelope.maximums)
    }

    @Test
    fun monoEnvelopeStillUsesTruePcmAmplitudeRatherThanDisplayGain() {
        val samples = shortArrayOf(-1_024, 1_024)
        val original = samples.copyOf()
        val envelope = buildWaveformEnvelope(samples, 0, 2, 2)
        assertContentEquals(floatArrayOf(-0.03125f), envelope.minimums)
        assertContentEquals(floatArrayOf(0.03125f), envelope.maximums)
        assertContentEquals(original, samples)
    }

    @Test
    fun emptyAndInvalidEnvelopeInputsKeepTheirContract() {
        assertTrue(buildWaveformEnvelope(shortArrayOf(), 0, 0, 100).minimums.isEmpty())
        assertTrue(buildWaveformEnvelope(shortArrayOf(1), 0, 1, 0).maximums.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            buildWaveformEnvelope(shortArrayOf(1), 0, 1, 10, channelCount = 2)
        }
    }

    @Test
    fun displayGainIsSanitizedAndLabelled() {
        assertEquals(1f, sanitizeWaveformDisplayGain(Float.NaN))
        assertEquals(1f, sanitizeWaveformDisplayGain(Float.POSITIVE_INFINITY))
        assertEquals(1f, sanitizeWaveformDisplayGain(-10f))
        assertEquals(16f, sanitizeWaveformDisplayGain(100f))
        assertEquals("表示のみ ×6.4", waveformDisplayGainLabel(6.4f))
    }

    @Test
    fun longAnalysisChecksCancellationAndDoesNotPublishPartialResults() {
        var checks = 0
        assertFailsWith<IllegalStateException> {
            waveformDisplayGain(ShortArray(200_000) { 100 }) {
                checks++
                if (checks == 2) error("cancelled")
            }
        }
        assertEquals(2, checks)
    }
}
