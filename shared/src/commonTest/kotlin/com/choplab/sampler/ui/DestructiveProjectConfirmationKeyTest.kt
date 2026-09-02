package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DestructiveProjectConfirmationKeyTest {
    private val audio = PcmAudio(
        id = 7L,
        name = "confirmation.wav",
        samples = ShortArray(16),
        sampleRate = 8_000,
    )
    private val baseline = SamplerUiState(
        currentAudio = audio,
        rangeEndFrame = audio.frameCount,
        projectLaunchRevision = 3L,
    )

    @Test
    fun transientPlaybackAndSelectionDoNotDisarmTheSameDestructiveTarget() {
        val changed = baseline.copy(
            statusMessage = "元曲を再生中です",
            selectedBank = 2,
            selectedPad = 70,
            sourcePlaying = true,
            sourcePlayheadFrame = 8,
        )

        assertEquals(
            destructiveProjectConfirmationKey(baseline),
            destructiveProjectConfirmationKey(changed),
        )
    }

    @Test
    fun projectSourceAndProductionEditsDisarmConfirmation() {
        val assignedPad = baseline.pads.toMutableList().apply {
            this[0] = PadModel(0, audio = audio, startFrame = 0, endFrame = 8)
        }

        assertNotEquals(
            destructiveProjectConfirmationKey(baseline),
            destructiveProjectConfirmationKey(baseline.copy(projectLaunchRevision = 4L)),
        )
        assertNotEquals(
            destructiveProjectConfirmationKey(baseline),
            destructiveProjectConfirmationKey(
                baseline.copy(currentAudio = audio.copy(id = 8L)),
            ),
        )
        assertNotEquals(
            destructiveProjectConfirmationKey(baseline),
            destructiveProjectConfirmationKey(baseline.copy(pads = assignedPad)),
        )
        assertNotEquals(
            destructiveProjectConfirmationKey(baseline),
            destructiveProjectConfirmationKey(baseline.copy(activeSteps = setOf(0, 4))),
        )
    }
}
