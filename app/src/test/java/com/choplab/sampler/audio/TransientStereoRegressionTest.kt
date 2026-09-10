package com.choplab.sampler.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientStereoRegressionTest {
    private fun attacks(): ShortArray = ShortArray(48_000) { frame ->
        val offset = frame % 12_000
        if (offset in 2_400 until 4_800) (18_000 * (4_800 - offset) / 2_400).toShort() else 0
    }
    private fun markers(samples: ShortArray, channels: Int) = TransientDetector.detect(
        samples, 0, samples.size / channels, 48_000, maxSlices = 16, channelCount = channels,
    )

    @Test fun oppositePolarityStereoKeepsAllDetectedAttacks() {
        val mono = attacks()
        val stereo = ShortArray(mono.size * 2) { i -> if (i % 2 == 0) mono[i / 2] else (-mono[i / 2]).toShort() }
        val before = stereo.copyOf()
        val expected = markers(mono, 1)
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, markers(stereo, 2))
        assertArrayEquals(before, stereo)
    }

    @Test fun swappingChannelsDoesNotChangeMarkers() {
        val mono = attacks()
        val left = ShortArray(mono.size * 2) { i -> if (i % 2 == 0) mono[i / 2] else 0 }
        val right = ShortArray(mono.size * 2) { i -> if (i % 2 == 1) mono[i / 2] else 0 }
        assertEquals(markers(mono, 1), markers(left, 2))
        assertEquals(markers(left, 2), markers(right, 2))
    }

    @Test fun duplicatedMonoAndSilenceKeepExistingResults() {
        val mono = attacks()
        val stereo = ShortArray(mono.size * 2) { mono[it / 2] }
        assertEquals(markers(mono, 1), markers(stereo, 2))
        assertEquals(emptyList<Int>(), markers(ShortArray(48_000), 1))
        assertEquals(emptyList<Int>(), markers(ShortArray(96_000), 2))
    }
}
