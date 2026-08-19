package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.ui.WorkflowStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSamplerControllerTest {
    private fun controller(): DesktopSamplerController =
        DesktopSamplerController(JavaSoundWavPlayer())

    @Test
    fun sharedWorkflowUsesTheCurrentFourAndroidStages() {
        assertEquals(listOf("入れる", "チョップ", "ビート", "保存"), WorkflowStage.entries.map { it.label })
        assertEquals(listOf("CAPTURE", "CHOP", "BEAT", "SAVE"), WorkflowStage.entries.map { it.caption })
    }

    @Test
    fun controllerUsesSharedTempoAndSelectionState() {
        val controller = controller()
        try {
            controller.setBpm(140f)
            controller.setSwing(61f)
            controller.selectBank(2)
            controller.selectPadPage(1)

            assertEquals(140f, controller.state.value.bpm)
            assertEquals(61f, controller.state.value.swing)
            assertEquals(2, controller.state.value.selectedBank)
            assertEquals(2 * SamplerConfig.PADS_PER_BANK + SamplerConfig.PAD_PAGE_SIZE, controller.state.value.selectedPad)
        } finally {
            controller.close()
        }
    }

    @Test
    fun builtInDrumKitUsesTheSharedAndroidCatalog() {
        val controller = controller()
        try {
            controller.applyBuiltInDrumKit("boom-bap", replaceExisting = false)
            val drumPads = controller.state.value.pads.subList(
                SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK,
                SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK + SamplerConfig.DRUM_KIT_PAD_COUNT,
            )
            assertTrue(drumPads.all { it.isAssigned })
            assertTrue(drumPads.all { it.contentKind.name == "DRUM" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun resetReturnsToTheSharedEmptySourceState() {
        val controller = controller()
        try {
            controller.setBpm(120f)
            controller.resetProject()
            assertEquals(null, controller.state.value.currentAudio)
            assertEquals("音声を読み込むか録音してください", controller.state.value.statusMessage)
        } finally {
            controller.close()
        }
    }
}
