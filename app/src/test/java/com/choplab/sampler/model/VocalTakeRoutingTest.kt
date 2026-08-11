package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VocalTakeRoutingTest {
    @Test
    fun nextVocalTakeUsesTheFirstEmptyPadInBankD() {
        val audio = PcmAudio(name = "voice", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index in 48..50) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2, contentKind = PadContentKind.VOCAL)
            } else {
                PadModel(index)
            }
        }

        assertEquals(51, pads.nextVocalPadIndex())
    }

    @Test
    fun fullVocalBankRefusesToOverwriteAnExistingTake() {
        val audio = PcmAudio(name = "voice", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index >= 48) {
                PadModel(index, audio = audio, startFrame = 0, endFrame = 2, contentKind = PadContentKind.VOCAL)
            } else {
                PadModel(index)
            }
        }

        assertEquals(null, pads.nextVocalPadIndex())
    }
}
