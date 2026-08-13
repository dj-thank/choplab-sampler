package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankRoleTest {
    @Test
    fun eachRoleBankHasTwoFixedPagesOfSixteenPads() {
        assertEquals(32, SamplerConfig.PADS_PER_BANK)
        assertEquals(16, SamplerConfig.PAD_PAGE_SIZE)
        val state = SamplerUiState(selectedBank = 0, selectedPad = 20)

        assertEquals((16 until 32).toList(), state.visiblePads().map(PadModel::globalIndex))
    }

    @Test
    fun newSourceDefaultsToTheFirstEmptyMelodyPadAcrossBothPages() {
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index < SamplerConfig.PAD_PAGE_SIZE) {
                val audio = PcmAudio(name = "used", samples = shortArrayOf(1, 2), sampleRate = 48_000)
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2)
            } else {
                PadModel(index)
            }
        }

        assertEquals(16, defaultMelodyChopPad(pads))
    }

    @Test
    fun fullMelodyBankHasNoImplicitA01OverwriteTarget() {
        val audio = PcmAudio(name = "used", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index < SamplerConfig.PADS_PER_BANK) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2)
            } else {
                PadModel(index)
            }
        }

        assertNull(defaultMelodyChopPad(pads))
    }

    @Test
    fun assignedPadCountIsReportedPerPage() {
        val audio = PcmAudio(name = "used", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index in setOf(0, 3, 17)) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2)
            } else {
                PadModel(index)
            }
        }
        val state = SamplerUiState(pads = pads)

        assertEquals(2, state.assignedPadCountOnPage(0))
        assertEquals(1, state.assignedPadCountOnPage(1))
    }

    @Test
    fun banksHaveStableBeginnerRolesWithoutChangingTheirIndexes() {
        assertEquals("A", bankRoleFor(0).letter)
        assertEquals("メロディー", bankRoleFor(0).japaneseLabel)
        assertEquals("B", bankRoleFor(1).letter)
        assertEquals("ドラム", bankRoleFor(1).japaneseLabel)
        assertEquals("ワンショット", bankRoleFor(2).japaneseLabel)
        assertEquals("ボイス", bankRoleFor(3).japaneseLabel)
    }

    @Test
    fun drumKitReplacementRequiresConfirmationWhenBankBContainsAudio() {
        val audio = PcmAudio(name = "keep-me", samples = ShortArray(20), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 20)
            } else {
                PadModel(index)
            }
        }

        assertEquals(DrumKitApplyDecision.APPLY, drumKitApplyDecision(List(SamplerConfig.PAD_COUNT) { PadModel(it) }))
        assertEquals(DrumKitApplyDecision.CONFIRM_REPLACE, drumKitApplyDecision(pads))
    }
}
