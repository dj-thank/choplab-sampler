package com.choplab.sampler.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformStereoRegressionTest {
    @Test fun oppositePolarityCannotEraseVisibleAudio() {
        val samples = ShortArray(200) { if (it % 2 == 0) 16_384 else -16_384 }
        val unchanged = samples.copyOf()
        val peaks = buildWaveformEnvelope(samples, 0, 100, 20, channelCount = 2)
        assertTrue(peaks.minimums.all { it == -0.5f })
        assertTrue(peaks.maximums.all { it == 0.5f })
        assertArrayEquals(unchanged, samples)
    }
    @Test fun rightOnlySignalAndShortMinValueAreVisible() {
        val samples = ShortArray(200) { if (it % 2 == 0) 0 else Short.MIN_VALUE }
        val peaks = buildWaveformEnvelope(samples, 0, 100, 20, channelCount = 2)
        assertTrue(peaks.minimums.all { it == -1f })
        assertTrue(peaks.maximums.all { it == 0f })
    }
    @Test fun monoAndDuplicatedStereoRemainIdentical() {
        val mono = ShortArray(100) { ((it - 50) * 500).toShort() }
        val stereo = ShortArray(200) { mono[it / 2] }
        val a = buildWaveformEnvelope(mono, 0, 100, 40)
        val b = buildWaveformEnvelope(stereo, 0, 100, 40, channelCount = 2)
        assertTrue(a.minimums.contentEquals(b.minimums))
        assertTrue(a.maximums.contentEquals(b.maximums))
        assertEquals(a.pixelStep, b.pixelStep)
    }
    @Test fun noIntersectionDoesNotInventARepeatedEdgeSample() {
        val peaks = buildWaveformEnvelope(shortArrayOf(32_000, 32_000), 10, 20, 100)
        assertEquals(0, peaks.minimums.size)
    }
    @Test fun extremeViewportArithmeticDoesNotOverflow() {
        val peaks = buildWaveformEnvelope(shortArrayOf(-100, 200), Int.MIN_VALUE, Int.MAX_VALUE, 4)
        assertEquals(2, peaks.minimums.size)
        assertTrue(peaks.minimums.all { it.isFinite() })
        assertTrue(peaks.maximums.all { it.isFinite() })
    }
}
