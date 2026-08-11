package com.choplab.sampler.ui

import com.choplab.sampler.model.LaneStepState
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.SamplerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatLaneAccessibilityTest {
    @Test
    fun `bank pad description uses configured bank size`() {
        assertEquals("BANK A メロディー PAD 20", beatLanePadDescription(bankIndex = 0, padIndex = 19))
        assertEquals("BANK B ドラム PAD 4", beatLanePadDescription(bankIndex = 1, padIndex = 35))
        assertEquals(
            "BANK C ワンショット PAD 1 空",
            beatLanePadDescription(bankIndex = 2, padIndex = 64, playable = false),
        )
    }

    @Test
    fun `lane step states are announced in plain Japanese`() {
        assertEquals("選択音", laneStepAccessibilityLabel(LaneStepState.SELECTED_SOUND))
        assertEquals("別の音", laneStepAccessibilityLabel(LaneStepState.OTHER_SOUND))
        assertEquals("オフ", laneStepAccessibilityLabel(LaneStepState.OFF))
    }

    @Test
    fun `empty bank has no beat lane target`() {
        val pads = List(SamplerConfig.PAD_COUNT) { PadModel(it) }

        assertEquals(null, beatLaneTargetPad(pads, bankIndex = 1, selectedPad = 3))
    }

    @Test
    fun `beat lane keeps selected playable pad or falls back within bank`() {
        val audio = PcmAudio(name = "beat", samples = ShortArray(100), sampleRate = 48_000)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 35) PadModel(index, audio, 0, 100) else PadModel(index)
        }

        assertEquals(35, beatLaneTargetPad(pads, bankIndex = 1, selectedPad = 35))
        assertEquals(35, beatLaneTargetPad(pads, bankIndex = 1, selectedPad = 3))
    }
}
