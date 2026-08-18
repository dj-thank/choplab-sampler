package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SamplerModelsTest {
    @Test
    fun sortedMarkersCreateContiguousSlicesInsideSelection() {
        val audio = PcmAudio(
            name = "test",
            samples = ShortArray(1_000),
            sampleRate = 48_000,
        )
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 100,
            rangeEndFrame = 900,
            sliceMarkers = listOf(700, 300, 300, 50, 950),
        )

        assertEquals(
            listOf(
                SliceRange(100, 300),
                SliceRange(300, 700),
                SliceRange(700, 900),
            ),
            state.sliceRanges(),
        )
    }

    @Test
    fun stepKeyKeepsPadRowsIndependent() {
        assertEquals(0, stepKey(0, 0))
        assertEquals(15, stepKey(0, 15))
        assertEquals(16, stepKey(1, 0))
        assertEquals(63 * SamplerConfig.STEP_COUNT + 15, stepKey(63, 15))
    }

    @Test
    fun transientResultIsDiscardedAfterAnyProjectEditEvenWithTheSameRange() {
        val audio = PcmAudio(42L, "same.wav", ShortArray(1_000), 48_000)
        val snapshot = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 100,
            rangeEndFrame = 900,
        )

        assertEquals(true, transientAnalysisStillCurrent(snapshot, 7L, snapshot, 7L))
        assertEquals(false, transientAnalysisStillCurrent(snapshot, 7L, snapshot, 8L))
        assertEquals(
            false,
            transientAnalysisStillCurrent(
                snapshot,
                7L,
                snapshot.copy(rangeStartFrame = 101),
                7L,
            ),
        )
    }
}
