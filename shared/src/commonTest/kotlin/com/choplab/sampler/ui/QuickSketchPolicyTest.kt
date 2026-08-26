package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stepKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickSketchPolicyTest {
    @Test
    fun emptyMelodySourceOffersSketchInTheDisabledPadEditSlot() {
        val state = readyState()

        val items = chopProductionDockItems(state)

        assertEquals(4, items.size)
        assertEquals(ProductionDockIntent.CREATE_QUICK_SKETCH, items[1].intent)
        assertEquals("8つの下書き\nQUICK SKETCH", items[1].label)
        assertTrue(items[1].enabled)
        assertEquals(ProductionDockIntent.OPEN_BEAT, items[0].intent)
        assertFalse(items[0].enabled)
        assertEquals("1音入れる\nTHEN BEAT", items[0].label)
    }

    @Test
    fun existingMelodyRestoresTheNormalBeatAndPadEditDock() {
        val source = readyState().currentAudio!!
        val assigned = PadModel(0, source, 0, 200)
        val state = readyState().copy(
            pads = List(SamplerConfig.PAD_COUNT) { index -> if (index == 0) assigned else PadModel(index) },
        )

        val items = chopProductionDockItems(state)

        assertEquals(ProductionDockIntent.OPEN_PAD_EDIT, items[1].intent)
        assertTrue(items[0].enabled)
        assertTrue(items[1].enabled)
    }

    @Test
    fun staleMelodyStepNeverOffersAnOverwritingSketch() {
        val items = chopProductionDockItems(readyState().copy(activeSteps = setOf(stepKey(4, 2))))

        assertEquals(ProductionDockIntent.OPEN_PAD_EDIT, items[1].intent)
        assertFalse(items[1].enabled)
        assertTrue(items.none { it.intent == ProductionDockIntent.CREATE_QUICK_SKETCH })
    }

    private fun readyState(): SamplerUiState {
        val audio = PcmAudio(
            name = "source.wav",
            samples = ShortArray(1_600),
            sampleRate = 8_000,
        )
        return SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)
    }
}
