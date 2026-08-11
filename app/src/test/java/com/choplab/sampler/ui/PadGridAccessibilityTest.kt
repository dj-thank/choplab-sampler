package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class PadGridAccessibilityTest {
    @Test
    fun captureModeDescribesAssignedPadsAsAuditionAndEmptyPadsAsChopTargets() {
        val audio = PcmAudio(1L, "source.wav", ShortArray(100), 1_000)
        val assignedPad = PadModel(0, audio, 0, 50)
        val emptyPad = PadModel(1)

        assertEquals(
            "PAD 01 割り当て済み。タップで試聴。長押しで微調整",
            padAccessibilityDescription(assignedPad, captureMode = true),
        )
        assertEquals(
            "PAD 02 空。現在位置をチョップ",
            padAccessibilityDescription(emptyPad, captureMode = true),
        )
    }
}
