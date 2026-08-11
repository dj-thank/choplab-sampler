package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceReplacementTest {
    @Test
    fun `new source starts a separate project without old pads or steps`() {
        val oldAudio = PcmAudio(1L, "old.wav", ShortArray(2_000), 1_000)
        val newAudio = PcmAudio(2L, "new.wav", ShortArray(4_000), 1_000)
        val drumIndex = SamplerConfig.PADS_PER_BANK
        val voiceIndex = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(index, oldAudio, 100, 800)
                drumIndex -> PadModel(index, oldAudio, 0, 200, contentKind = PadContentKind.DRUM)
                voiceIndex -> PadModel(index, oldAudio, 0, 500, contentKind = PadContentKind.VOCAL)
                else -> PadModel(index)
            }
        }
        val old = SamplerUiState(
            currentAudio = oldAudio,
            rangeStartFrame = 100,
            rangeEndFrame = 1_500,
            sliceMarkers = listOf(400),
            activeSliceIndex = 0,
            selectedBank = 0,
            selectedPad = 0,
            pads = pads,
            activeSteps = setOf(stepKey(0, 0), stepKey(drumIndex, 4), stepKey(voiceIndex, 8)),
            loopingPadIndex = 0,
        )

        val replaced = replaceSourceAudio(old, newAudio)

        assertSame(newAudio, replaced.currentAudio)
        assertEquals(0, replaced.rangeStartFrame)
        assertEquals(newAudio.frameCount, replaced.rangeEndFrame)
        assertTrue(replaced.sliceMarkers.isEmpty())
        assertNull(replaced.activeSliceIndex)
        assertTrue(replaced.pads.none(PadModel::isAssigned))
        assertTrue(replaced.activeSteps.isEmpty())
        assertNull(replaced.loopingPadIndex)
        assertEquals(0, replaced.selectedBank)
        assertEquals(0, replaced.selectedPad)
    }
}
