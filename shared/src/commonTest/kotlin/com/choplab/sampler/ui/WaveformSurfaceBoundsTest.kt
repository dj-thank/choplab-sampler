package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaveformSurfaceBoundsTest {
    private fun pad(samples: ShortArray, channels: Int = 1): PadModel {
        val audio = PcmAudio(name = "synthetic", samples = samples, sampleRate = 48_000, channelCount = channels)
        return PadModel(globalIndex = 0, audio = audio, startFrame = 0, endFrame = audio.frameCount)
    }

    @Test fun anExtremePixelWidthHasBoundedStorage() {
        val envelope = buildWaveformEnvelope(shortArrayOf(-100, 200), 0, 2, Int.MAX_VALUE, pixelStep = 1)
        assertTrue(envelope.minimums.size <= 8_192)
        assertEquals(envelope.minimums.size, envelope.maximums.size)
        assertTrue(envelope.minimums.all { it.isFinite() })
    }

    @Test fun finalSampleIsIncludedEvenWhenRangeDoesNotDivideTheBucketCount() {
        val samples = ShortArray(1_031).also { it[it.lastIndex] = 16_384 }
        val model = pad(samples)
        assertEquals(1f, buildPadMiniPeaks(model).last())
        assertEquals(1f, buildSliceEnvelope(model).maximums.last())
        assertEquals(0.5f, buildWaveformEnvelope(samples, 0, samples.size, 16).maximums.last())
    }

    @Test fun everySurfaceKeepsOppositePolarityStereoVisibleWithoutChangingPcm() {
        val samples = ShortArray(4_096) { if (it % 2 == 0) 8_192 else -8_192 }
        val original = samples.copyOf()
        val model = pad(samples, 2)
        assertTrue(buildPadMiniPeaks(model).all { it == 1f })
        val timeline = buildSliceEnvelope(model)
        assertTrue(timeline.minimums.all { it == -1f })
        assertTrue(timeline.maximums.all { it == 1f })
        assertContentEquals(original, samples)
    }

    @Test fun silenceKeepsTheExistingMiniFloorButNoTimelineSignal() {
        val model = pad(ShortArray(100))
        assertTrue(buildPadMiniPeaks(model).all { it == 0.12f })
        assertTrue(buildSliceEnvelope(model).minimums.all { it == 0f })
        assertTrue(buildSliceEnvelope(model).maximums.all { it == 0f })
    }

    @Test fun trimmingNeverSamplesOutsideTheAssignedRange() {
        val samples = ShortArray(100) { 30_000 }.also { it.fill(0, 10, 70) }
        val model = pad(samples).copy(startFrame = 10, endFrame = 70)
        assertTrue(buildPadMiniPeaks(model).all { it == 0.12f })
        assertTrue(buildSliceEnvelope(model).maximums.all { it == 0f })
    }

    @Test fun monoAndDuplicatedStereoHaveIdenticalMiniAndTimelineBounds() {
        val mono = ShortArray(1_007) { ((it % 97 - 48) * 600).toShort() }
        val a = pad(mono)
        val b = pad(ShortArray(mono.size * 2) { mono[it / 2] }, 2)
        assertContentEquals(buildPadMiniPeaks(a), buildPadMiniPeaks(b))
        assertContentEquals(buildSliceEnvelope(a).minimums, buildSliceEnvelope(b).minimums)
        assertContentEquals(buildSliceEnvelope(a).maximums, buildSliceEnvelope(b).maximums)
    }

    @Test fun malformedStereoCannotBePartiallyVisualized() {
        assertFailsWith<IllegalArgumentException> {
            buildWaveformEnvelope(shortArrayOf(1, 2, 3), 0, 1, 50, channelCount = 2)
        }
    }

    @Test fun unassignedPadsHaveNoVisualEnvelope() {
        val pad = PadModel(globalIndex = 0)
        assertTrue(buildPadMiniPeaks(pad).isEmpty())
        assertTrue(buildSliceEnvelope(pad).minimums.isEmpty())
    }
}
