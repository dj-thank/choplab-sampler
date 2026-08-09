package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SamplerCommandsTest {
    @Test
    fun assignmentWrapsWithinSelectedBankAndAdvancesActiveSlice() {
        val audio = PcmAudio(name = "source", samples = ShortArray(2_000), sampleRate = 48_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = 2_000,
            sliceMarkers = listOf(500, 1_000),
            activeSliceIndex = 0,
            selectedBank = 1,
            selectedPad = 31,
            autoNextPad = true,
        )

        val result = assignRangesToPads(
            state = state,
            ranges = listOf(SliceRange(0, 500)),
            statusMessage = "assigned",
        )

        assertEquals(31, result.changedPads.single().globalIndex)
        assertSame(audio, result.state.pads[31].audio)
        assertEquals(16, result.state.selectedPad)
        assertEquals(1, result.state.activeSliceIndex)
        assertEquals("assigned", result.state.statusMessage)
    }

    @Test
    fun assignmentKeepsSelectionWhenAutoNextIsDisabled() {
        val audio = PcmAudio(name = "source", samples = ShortArray(2_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 4) {
                PadModel(index, pitchSemitones = 7f, gain = 1.2f, reverse = true)
            } else {
                PadModel(index)
            }
        }
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = 2_000,
            pads = pads,
            selectedPad = 4,
            autoNextPad = false,
        )

        val result = assignRangesToPads(state, listOf(SliceRange(100, 400)), "assigned")

        assertEquals(4, result.state.selectedPad)
        assertEquals(100, result.state.pads[4].startFrame)
        assertEquals(400, result.state.pads[4].endFrame)
        assertEquals(7f, result.state.pads[4].pitchSemitones)
        assertEquals(1.2f, result.state.pads[4].gain)
        assertEquals(true, result.state.pads[4].reverse)
    }
}
