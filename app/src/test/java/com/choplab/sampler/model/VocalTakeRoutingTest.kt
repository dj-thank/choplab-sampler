package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VocalTakeRoutingTest {
    @Test
    fun nextVocalTakeUsesTheFirstEmptyPadInBankD() {
        val audio = PcmAudio(name = "voice", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val vocalStart = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index in vocalStart..vocalStart + 2) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2, contentKind = PadContentKind.VOCAL)
            } else {
                PadModel(index)
            }
        }

        assertEquals(vocalStart + 3, pads.nextVocalPadIndex())
    }

    @Test
    fun fullVocalBankRefusesToOverwriteAnExistingTake() {
        val audio = PcmAudio(name = "voice", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val vocalStart = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index >= vocalStart) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2, contentKind = PadContentKind.VOCAL)
            } else {
                PadModel(index)
            }
        }

        assertEquals(null, pads.nextVocalPadIndex())
    }
}
