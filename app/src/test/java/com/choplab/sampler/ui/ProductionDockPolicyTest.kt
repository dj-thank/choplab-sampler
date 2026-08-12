package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionDockPolicyTest {
    @Test
    fun captureDockKeepsResetConfirmationAndFollowsSourceTruth() {
        val empty = captureProductionDockItems(SamplerUiState())
        assertEquals(listOf(ProductionDockIntent.START_CHOP), empty.map { it.intent })
        assertFalse(empty.single().enabled)

        val ready = captureProductionDockItems(SamplerUiState(currentAudio = audio))
        assertEquals(
            listOf(
                ProductionDockIntent.RESET_ALL,
                ProductionDockIntent.START_CHOP,
            ),
            ready.map { it.intent },
        )
        assertEquals("もう一度で完全リセット", ready.first().confirmLabel)
        assertEquals("チョップ開始\nSTART CHOP", ready.last().label)
        assertTrue(ready.last().enabled)
        assertTrue(ready.last().active == true)

        val starting = captureProductionDockItems(
            SamplerUiState(
                currentAudio = audio,
                pendingSourceCommand = PendingSourceCommand.START,
            ),
        )
        assertEquals("チョップへ\nOPEN CHOP", starting.last().label)
        assertTrue(starting.last().enabled)

        val stopping = captureProductionDockItems(
            SamplerUiState(
                currentAudio = audio,
                sourcePlaying = true,
                pendingSourceCommand = PendingSourceCommand.STOP,
            ),
        )
        assertEquals("停止中\nPLEASE WAIT", stopping.last().label)
        assertFalse(stopping.last().enabled)
        assertFalse(stopping.last().active == true)
    }

    @Test
    fun chopDockKeepsPrimaryJourneyAndOnlyEnablesTruthfulActions() {
        val empty = chopProductionDockItems(SamplerUiState())
        assertEquals(
            listOf(
                ProductionDockIntent.OPEN_BEAT,
                ProductionDockIntent.OPEN_PAD_EDIT,
                ProductionDockIntent.OPEN_ADD,
                ProductionDockIntent.OPEN_SCRATCH,
            ),
            empty.map { it.intent },
        )
        assertFalse(empty[0].enabled)
        assertFalse(empty[1].enabled)
        assertTrue(empty[2].enabled)
        assertFalse(empty[3].enabled)

        val assignedPads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) PadModel(index, audio, 0, audio.frameCount) else PadModel(index)
        }
        val assigned = chopProductionDockItems(
            SamplerUiState(currentAudio = audio, pads = assignedPads, selectedPad = 0),
        )
        assertTrue(assigned[0].enabled)
        assertTrue(assigned[0].active == true)
        assertTrue(assigned[1].enabled)
        assertTrue(assigned[3].enabled)
        assertEquals(1.15f, assigned[0].weight)
    }

    @Test
    fun quickAndStepsKeepAddAndScratchInTheSameOrderAndContract() {
        val quick = beatProductionDockItems(stepsVisible = false)
        val steps = beatProductionDockItems(stepsVisible = true)
        val expected = listOf(
            ProductionDockIntent.SHOW_QUICK,
            ProductionDockIntent.SHOW_STEPS,
            ProductionDockIntent.OPEN_ADD,
            ProductionDockIntent.OPEN_SCRATCH,
        )

        assertEquals(expected, quick.map { it.intent })
        assertEquals(expected, steps.map { it.intent })
        assertTrue(quick[0].active == true)
        assertFalse(quick[1].active == true)
        assertFalse(steps[0].active == true)
        assertTrue(steps[1].active == true)
        assertNull(quick[2].active)
        assertTrue(quick.all { it.enabled })
    }

    private companion object {
        val audio = PcmAudio(1L, "source.wav", ShortArray(128), 48_000)
    }
}
