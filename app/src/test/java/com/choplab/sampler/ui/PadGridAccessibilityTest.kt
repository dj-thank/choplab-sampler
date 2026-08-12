package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SourceUiPhase
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

    @Test
    fun pendingStartExplainsThatAnEmptyPadOnlyChangesSelection() {
        assertEquals(
            "PAD 02 空。再生準備中。音が鳴るまで選択のみ",
            padAccessibilityDescription(
                pad = PadModel(1),
                captureMode = false,
                sourcePhase = SourceUiPhase.STARTING,
            ),
        )
    }
}
