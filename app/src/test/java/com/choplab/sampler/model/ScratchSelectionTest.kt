package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ScratchSelectionTest {
    @Test
    fun activeSourceChopBecomesTheScratchRange() {
        val audio = PcmAudio(name = "beat", samples = ShortArray(1_000), sampleRate = 48_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 100,
            rangeEndFrame = 900,
            sliceMarkers = listOf(300, 700),
            activeSliceIndex = 1,
        )

        assertEquals(SliceRange(300, 700), state.sourceScratchRange())
    }

    @Test
    fun sourceRangeModeKeepsChopsAndOnlyClearsTheActiveSlice() {
        val audio = PcmAudio(name = "beat", samples = ShortArray(1_000), sampleRate = 48_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 100,
            rangeEndFrame = 900,
            sliceMarkers = listOf(300, 700),
            activeSliceIndex = 1,
            manualChopEnabled = true,
        )

        val selected = state.selectSourceRangeForScratch()

        assertEquals(listOf(300, 700), selected.sliceMarkers)
        assertEquals(100, selected.rangeStartFrame)
        assertEquals(900, selected.rangeEndFrame)
        assertEquals(null, selected.activeSliceIndex)
        assertEquals(false, selected.manualChopEnabled)
    }
}
