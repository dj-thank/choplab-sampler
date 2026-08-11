package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayablePadSelectionTest {
    @Test
    fun emptyPadDoesNotReplaceCurrentPlayableSelection() {
        val current = assignedPad(3)
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = current.globalIndex,
            pads = padsWith(current),
            statusMessage = "ready",
        )

        val result = selectPlayablePad(state, globalIndex = 19)

        assertEquals(3, result.selectedPad)
        assertEquals(0, result.selectedBank)
        assertTrue(result.statusMessage.contains("A-20は空"))
        assertTrue(result.statusMessage.contains("音の入ったPAD"))
    }

    @Test
    fun playablePadSelectionReplacesStaleEmptyPadGuidance() {
        val current = assignedPad(3)
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = current.globalIndex,
            pads = padsWith(current),
            statusMessage = "A-06は空です。音の入ったPADを選んでください",
        )

        val result = selectPlayablePad(state, globalIndex = 3)

        assertEquals("A-04を選択しました", result.statusMessage)
    }

    @Test
    fun pageSelectionKeepsTheSamePadPositionWhenThatSoundExists() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3), assignedPad(19)),
        )

        val result = selectPlayablePadPage(state, pageIndex = 1)

        assertEquals(19, result.selectedPad)
        assertEquals(0, result.selectedBank)
    }

    @Test
    fun pageSelectionFallsBackToFirstPlayableSoundOnThatPage() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3), assignedPad(16)),
        )

        val result = selectPlayablePadPage(state, pageIndex = 1)

        assertEquals(16, result.selectedPad)
    }

    @Test
    fun emptyPageKeepsCurrentPlayableSelection() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3)),
        )

        val result = selectPlayablePadPage(state, pageIndex = 1)

        assertEquals(3, result.selectedPad)
        assertTrue(result.statusMessage.contains("PAD 17–32には音がありません"))
    }

    @Test
    fun bankSelectionKeepsTheSamePadPositionWhenThatSoundExists() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3), assignedPad(35)),
        )

        val result = selectPlayableBank(state, bankIndex = 1)

        assertEquals(1, result.selectedBank)
        assertEquals(35, result.selectedPad)
    }

    @Test
    fun bankSelectionFallsBackToFirstPlayableSoundInThatBank() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3), assignedPad(32)),
        )

        val result = selectPlayableBank(state, bankIndex = 1)

        assertEquals(1, result.selectedBank)
        assertEquals(32, result.selectedPad)
    }

    @Test
    fun emptyBankKeepsCurrentPlayableSelection() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 3,
            pads = padsWith(assignedPad(3)),
        )

        val result = selectPlayableBank(state, bankIndex = 1)

        assertEquals(0, result.selectedBank)
        assertEquals(3, result.selectedPad)
        assertTrue(result.statusMessage.contains("BANK B ドラムには音がありません"))
    }

    @Test
    fun beatEntryMovesEmptySelectionToPlayableSoundInCurrentBank() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 19,
            pads = padsWith(assignedPad(3), assignedPad(32)),
        )

        val result = ensurePlayablePadSelected(state)

        assertEquals(0, result.selectedBank)
        assertEquals(3, result.selectedPad)
    }

    @Test
    fun beatEntryFallsBackToPlayableSoundInAnotherBank() {
        val state = SamplerUiState(
            selectedBank = 0,
            selectedPad = 19,
            pads = padsWith(assignedPad(32)),
        )

        val result = ensurePlayablePadSelected(state)

        assertEquals(1, result.selectedBank)
        assertEquals(32, result.selectedPad)
    }

    @Test
    fun beatEntryWithoutSoundsExplainsHowToContinue() {
        val state = SamplerUiState(selectedBank = 0, selectedPad = 19)

        val result = ensurePlayablePadSelected(state)

        assertEquals(19, result.selectedPad)
        assertTrue(result.statusMessage.contains("先にチョップ"))
    }

    private fun assignedPad(index: Int): PadModel {
        val audio = PcmAudio(name = "sound-$index", samples = ShortArray(100), sampleRate = 48_000)
        return PadModel(index, audio = audio, startFrame = 0, endFrame = 100)
    }

    private fun padsWith(vararg assigned: PadModel): List<PadModel> {
        val byIndex = assigned.associateBy(PadModel::globalIndex)
        return List(SamplerConfig.PAD_COUNT) { index -> byIndex[index] ?: PadModel(index) }
    }
}
