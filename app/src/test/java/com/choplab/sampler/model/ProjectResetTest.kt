package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectResetTest {
    @Test
    fun `complete reset removes loaded audio pad history and beat history`() {
        val audio = PcmAudio(
            id = 42L,
            name = "old-song.wav",
            samples = ShortArray(2_000) { 1 },
            sampleRate = 1_000,
        )
        val assignedPads = List(SamplerConfig.PAD_COUNT) { index ->
            PadModel(
                globalIndex = index,
                audio = audio,
                startFrame = 100,
                endFrame = 500,
                playMode = if (index == 0) PadPlayMode.LOOP else PadPlayMode.ONE_SHOT,
            )
        }
        val dirty = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 100,
            rangeEndFrame = 1_900,
            sliceMarkers = listOf(250, 750),
            activeSliceIndex = 1,
            manualChopEnabled = true,
            selectedBank = 2,
            selectedPad = 70,
            pads = assignedPads,
            activeSteps = setOf(stepKey(0, 0), stepKey(70, 8)),
            transportPlaying = true,
            sourcePlaying = true,
            loopingPadIndex = 0,
            scratchingPadIndex = 0,
            canUndo = true,
            canRedo = true,
        )

        val reset = resetProjectState(dirty)

        assertNull(reset.currentAudio)
        assertTrue(reset.pads.none(PadModel::isAssigned))
        assertTrue(reset.activeSteps.isEmpty())
        assertTrue(reset.sliceMarkers.isEmpty())
        assertNull(reset.activeSliceIndex)
        assertEquals(0, reset.selectedBank)
        assertEquals(0, reset.selectedPad)
        assertFalse(reset.transportPlaying)
        assertFalse(reset.sourcePlaying)
        assertNull(reset.loopingPadIndex)
        assertNull(reset.scratchingPadIndex)
        assertFalse(reset.canUndo)
        assertFalse(reset.canRedo)
        assertEquals("新しい素材を入れてください", reset.statusMessage)
    }
}
