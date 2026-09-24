package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatLoopLayersTest {
    private val audio = PcmAudio(name = "layer", samples = ShortArray(256), sampleRate = 8000)
    private fun state() = SamplerUiState(loopingPadIndex = 1, loopPlayheadFrame = 99,
        pads = List(SamplerConfig.PAD_COUNT) {
            if (it < 10) PadModel(it, audio, 0, 256, playMode = if (it == 1) PadPlayMode.LOOP else PadPlayMode.ONE_SHOT) else PadModel(it)
        })

    @Test fun addingAndRemovingLayersKeepsCoreBoundsOwnerAndRhythm() {
        val before = state().copy(activeSteps = setOf(stepKey(8, 4)))
        assertNull(before.loopLayerChangeBlockedReason(0, true))
        val added = before.withLoopLayer(0, true)
        assertEquals(1, added.loopingPadIndex)
        assertEquals(99, added.loopPlayheadFrame)
        assertEquals(before.pads[1], added.pads[1])
        assertEquals(before.activeSteps, added.activeSteps)
        assertEquals(listOf(0), added.pads.loopCompanionPadIndicesForLoopStart(1))
        assertEquals(PadPlayMode.ONE_SHOT, added.withLoopLayer(0, false).pads[0].playMode)
        assertNotNull(added.loopLayerChangeBlockedReason(1, false))
        val gate = before.copy(pads = before.pads.map { if (it.globalIndex == 0) it.copy(playMode = PadPlayMode.GATE) else it })
        assertEquals(gate, gate.withLoopLayer(0, false))
    }

    @Test fun chokeConflictBusyStateAndBoundedLayerCountAreRejected() {
        val original = state()
        val conflicted = original.copy(pads = original.pads.map { if (it.globalIndex < 2) it.copy(chokeGroup = 2) else it })
        assertNotNull(conflicted.loopLayerChangeBlockedReason(0, true))
        assertNotNull(original.copy(pendingSourceCommand = PendingSourceCommand.STOP).loopLayerChangeBlockedReason(0, true))
        val full = original.copy(pads = original.pads.map { if (it.globalIndex < 8) it.copy(playMode = PadPlayMode.LOOP) else it })
        assertNotNull(full.loopLayerChangeBlockedReason(8, true))
    }

    @Test fun allLoopChokeGroupsProtectCoreAndLayersFromCompanionVocals() {
        val original = state().withLoopLayer(0, true)
        val pads = original.pads.map {
            when (it.globalIndex) {
                0 -> it.copy(chokeGroup = 2)
                5 -> it.copy(contentKind = PadContentKind.VOCAL, chokeGroup = 2)
                6 -> it.copy(contentKind = PadContentKind.VOCAL)
                else -> it
            }
        }
        assertEquals(listOf(0, 6), pads.loopCompanionPadIndicesForLoopStart(1))
        assertEquals(listOf(0), pads.loopCompanionPadIndicesForLoopStart(1, includeVocals = false))
        assertTrue(5 !in pads.loopCompanionPadIndicesForLoopStart(1))
    }
}
