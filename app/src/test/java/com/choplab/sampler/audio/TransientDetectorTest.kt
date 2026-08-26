package com.choplab.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientDetectorTest {
    @Test
    fun stereoDetectionReturnsFramePositionsInsteadOfInterleavedSamplePositions() {
        val sampleRate = 48_000
        val frameCount = sampleRate * 2
        val samples = ShortArray(frameCount * 2)
        val expectedFrames = listOf(12_000, 36_000, 60_000, 84_000)
        expectedFrames.forEach { onset ->
            repeat(1_000) { offset ->
                val envelope = 1f - offset / 1_000f
                val value = (
                    sin(2.0 * PI * 900.0 * offset / sampleRate) * envelope * Short.MAX_VALUE
                    ).toInt().toShort()
                samples[(onset + offset) * 2] = value
                samples[(onset + offset) * 2 + 1] = value
            }
        }

        val markers = TransientDetector.detect(
            samples = samples,
            channelCount = 2,
            startFrame = 0,
            endFrame = frameCount,
            sampleRate = sampleRate,
            maxSlices = 8,
        )

        val toleranceFrames = sampleRate / 200
        expectedFrames.forEach { expected ->
            assertTrue(markers.any { actual -> abs(actual - expected) <= toleranceFrames })
        }
    }

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

        val toleranceFrames = sampleRate / 200
        expectedFrames.forEach { expected ->
            assertTrue(
                "Expected onset near $expected within $toleranceFrames frames, actual=$markers",
                markers.any { actual -> abs(actual - expected) <= toleranceFrames },
            )
        }
        assertTrue(markers.size <= 7)
        assertTrue(markers.zipWithNext().all { (left, right) -> right > left })
    }

    @Test
    fun silenceAndTooShortInputProduceNoFalseMarkers() {
        val sampleRate = 48_000

        assertEquals(
            emptyList<Int>(),
            TransientDetector.detect(ShortArray(sampleRate), 0, sampleRate, sampleRate),
        )
        assertEquals(
            emptyList<Int>(),
            TransientDetector.detect(ShortArray(500), 0, 500, sampleRate),
        )
    }

    @Test
    fun maximumSliceCountAndMinimumDistanceAreEnforced() {
        val sampleRate = 48_000
        val samples = ShortArray(sampleRate)
        listOf(4_800, 5_800, 14_400, 24_000, 33_600, 43_200).forEachIndexed { index, onset ->
            repeat(480) { offset ->
                samples[onset + offset] = if (offset == 0) {
                    (Short.MAX_VALUE - index * 1_000).toShort()
                } else {
                    0
                }
            }
        }

        val markers = TransientDetector.detect(
            samples = samples,
            startFrame = 0,
            endFrame = samples.size,
            sampleRate = sampleRate,
            maxSlices = 4,
            sensitivity = 0.5f,
        )

        assertTrue(markers.size <= 3)
        assertTrue(markers.zipWithNext().all { (left, right) -> right - left >= 3_120 })
        assertTrue(markers.count { it in 4_000..7_000 } <= 1)
    }
}
