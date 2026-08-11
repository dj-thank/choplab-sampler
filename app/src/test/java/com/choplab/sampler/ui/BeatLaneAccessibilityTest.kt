package com.choplab.sampler.ui

import com.choplab.sampler.model.LaneStepState
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatLaneAccessibilityTest {
    @Test
    fun `bank pad description uses configured bank size`() {
        assertEquals("BANK A メロディー PAD 20", beatLanePadDescription(bankIndex = 0, padIndex = 19))
        assertEquals("BANK B ドラム PAD 4", beatLanePadDescription(bankIndex = 1, padIndex = 35))
    }

    @Test
    fun `lane step states are announced in plain Japanese`() {
        assertEquals("選択音", laneStepAccessibilityLabel(LaneStepState.SELECTED_SOUND))
        assertEquals("別の音", laneStepAccessibilityLabel(LaneStepState.OTHER_SOUND))
        assertEquals("オフ", laneStepAccessibilityLabel(LaneStepState.OFF))
    }
}
