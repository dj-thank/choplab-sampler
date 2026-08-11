package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BankRoleTest {
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
