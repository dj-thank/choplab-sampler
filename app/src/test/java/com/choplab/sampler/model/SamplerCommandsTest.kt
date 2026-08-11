package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerCommandsTest {
    @Test
    fun sliceAssignmentUsesNextEmptyPadInsteadOfOverwritingExistingAudio() {
        val original = PcmAudio(name = "original", samples = ShortArray(800), sampleRate = 48_000)
        val incoming = PcmAudio(name = "incoming", samples = ShortArray(2_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) PadModel(index, audio = original, startFrame = 0, endFrame = 800)
            else PadModel(index)
        }
        val state = SamplerUiState(
            currentAudio = incoming,
            rangeStartFrame = 0,
            rangeEndFrame = 2_000,
            pads = pads,
            selectedBank = 0,
            selectedPad = 0,
        )

        val result = assignRangesToPads(state, listOf(SliceRange(100, 500)), "assigned")

        assertSame(original, result.state.pads[0].audio)
        assertSame(incoming, result.state.pads[1].audio)
        assertEquals(listOf(1), result.changedPads.map(PadModel::globalIndex))
    }

    @Test
    fun liveChopRefusesToReplaceAnAssignedPad() {
        val original = PcmAudio(name = "original", samples = ShortArray(800), sampleRate = 48_000)
        val incoming = PcmAudio(name = "incoming", samples = ShortArray(2_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) PadModel(index, audio = original, startFrame = 0, endFrame = 800)
            else PadModel(index)
        }
        val state = SamplerUiState(
            currentAudio = incoming,
            rangeStartFrame = 0,
            rangeEndFrame = 2_000,
            pads = pads,
            selectedBank = 0,
            selectedPad = 0,
        )

        val result = assignLiveChopToPad(state, padIndex = 0, startFrame = 200)

        assertTrue(result.changedPads.isEmpty())
        assertSame(original, result.state.pads[0].audio)
        assertTrue(result.state.statusMessage.contains("上書きしません"))
    }

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
            selectedPad = 63,
            autoNextPad = true,
        )

        val result = assignRangesToPads(
            state = state,
            ranges = listOf(SliceRange(0, 500)),
            statusMessage = "assigned",
        )

        assertEquals(63, result.changedPads.single().globalIndex)
        assertSame(audio, result.state.pads[63].audio)
        assertEquals(32, result.state.selectedPad)
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

    @Test
    fun liveChopClosesPreviousPadAtNewStartAndPreservesPadSettings() {
        val audio = PcmAudio(name = "source", samples = ShortArray(4_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 500,
                    endFrame = 4_000,
                    pitchSemitones = 5f,
                )
                1 -> PadModel(globalIndex = index, tone = 0.35f, gain = 1.2f)
                else -> PadModel(index)
            }
        }
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = 4_000,
            selectedBank = 0,
            selectedPad = 0,
            pads = pads,
        )

        val result = assignLiveChopToPad(state, padIndex = 1, startFrame = 1_500)

        assertEquals(listOf(0, 1), result.changedPads.map(PadModel::globalIndex))
        assertEquals(500, result.state.pads[0].startFrame)
        assertEquals(1_500, result.state.pads[0].endFrame)
        assertEquals(5f, result.state.pads[0].pitchSemitones)
        assertSame(audio, result.state.pads[1].audio)
        assertEquals(1_500, result.state.pads[1].startFrame)
        assertEquals(4_000, result.state.pads[1].endFrame)
        assertEquals(0.35f, result.state.pads[1].tone)
        assertEquals(1.2f, result.state.pads[1].gain)
        assertEquals(1, result.state.selectedPad)
    }

    @Test
    fun liveChopOnlyReflowsCurrentBankAndCurrentAudio() {
        val audio = PcmAudio(name = "source", samples = ShortArray(5_000), sampleRate = 48_000)
        val otherAudio = PcmAudio(name = "other", samples = ShortArray(5_000), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(index, otherAudio, 200, 900)
                32 -> PadModel(index, audio, 1_000, 5_000)
                else -> PadModel(index)
            }
        }
        val state = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = 5_000,
            selectedBank = 1,
            selectedPad = 32,
            pads = pads,
        )

        val result = assignLiveChopToPad(state, padIndex = 33, startFrame = 2_500)

        assertEquals(900, result.state.pads[0].endFrame)
        assertEquals(2_500, result.state.pads[32].endFrame)
        assertEquals(5_000, result.state.pads[33].endFrame)
        assertTrue(result.changedPads.all { it.bankIndex == 1 })
    }
}
