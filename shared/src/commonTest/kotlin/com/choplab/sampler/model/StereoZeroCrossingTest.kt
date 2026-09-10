package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StereoZeroCrossingTest {
    private fun audio(values: ShortArray, channels: Int = 1) =
        PcmAudio(name = "synthetic", samples = values, sampleRate = 8_000, channelCount = channels)

    @Test fun antiPhaseCannotPretendEveryFrameIsAZeroCrossing() {
        val mono = ShortArray(100) { if (it < 40) -8_000 else 8_000 }
        val stereo = ShortArray(200) { if (it % 2 == 0) mono[it / 2] else (-mono[it / 2]).toShort() }
        val original = stereo.copyOf()
        assertEquals(40, snapFrameToZeroCrossing(audio(stereo, 2), 50, 0, 100))
        assertContentEquals(original, stereo)
    }

    @Test fun absenceOfARealCrossingUsesTheQuietestJointChannelMagnitude() {
        val stereo = ShortArray(200) { if (it % 2 == 0) 8_000 else -8_000 }
        stereo[130] = 100
        stereo[131] = -100
        assertEquals(65, snapFrameToZeroCrossing(audio(stereo, 2), 50, 0, 100))
    }

    @Test fun oneSilentChannelDoesNotMaskTheOtherChannelsCrossing() {
        val mono = ShortArray(100) { if (it < 40) -8_000 else 8_000 }
        val stereo = ShortArray(200) { if (it % 2 == 0) 0 else mono[it / 2] }
        assertEquals(40, snapFrameToZeroCrossing(audio(stereo, 2), 50, 0, 100))
    }

    @Test fun duplicatedMonoAndChannelOrderRemainEquivalent() {
        val mono = ShortArray(500) { ((it % 53 - 26) * 300).toShort() }
        val duplicated = audio(ShortArray(1_000) { mono[it / 2] }, 2)
        val antiPhase = audio(ShortArray(1_000) { if (it % 2 == 0) mono[it / 2] else (-mono[it / 2]).toShort() }, 2)
        val swapped = audio(ShortArray(1_000) { if (it % 2 == 1) mono[it / 2] else (-mono[it / 2]).toShort() }, 2)
        for (target in 0..500) {
            val expected = snapFrameToZeroCrossing(audio(mono), target, 0, 500)
            assertEquals(expected, snapFrameToZeroCrossing(duplicated, target, 0, 500))
            assertEquals(expected, snapFrameToZeroCrossing(antiPhase, target, 0, 500))
            assertEquals(expected, snapFrameToZeroCrossing(swapped, target, 0, 500))
        }
    }

    @Test fun endpointsAndConstrainedRangesRemainBounded() {
        val source = audio(ShortArray(100) { if (it < 40) -8_000 else 8_000 })
        assertEquals(0, snapFrameToZeroCrossing(source, 0, 0, 100))
        assertEquals(100, snapFrameToZeroCrossing(source, 100, 0, 100))
        assertEquals(50, snapFrameToZeroCrossing(source, 50, 45, 55))
    }
}
