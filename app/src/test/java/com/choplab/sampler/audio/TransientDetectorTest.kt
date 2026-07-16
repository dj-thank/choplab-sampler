package com.choplab.sampler.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientDetectorTest {
    @Test
    fun detectsSeparatedPercussiveOnsets() {
        val sampleRate = 48_000
        val samples = ShortArray(sampleRate * 2)
        val expectedFrames = listOf(12_000, 36_000, 60_000, 84_000)

        expectedFrames.forEach { onset ->
            repeat(1_000) { offset ->
                val envelope = 1f - offset / 1_000f
                samples[onset + offset] = (
                    sin(2.0 * PI * 900.0 * offset / sampleRate) * envelope * Short.MAX_VALUE
                ).toInt().toShort()
            }
        }

        val markers = TransientDetector.detect(
            samples = samples,
            startFrame = 0,
            endFrame = samples.size,
            sampleRate = sampleRate,
            maxSlices = 8,
        )

        assertTrue("Expected at least three detected onsets: $markers", markers.size >= 3)
        assertTrue(markers.zipWithNext().all { (left, right) -> right > left })
    }
}
