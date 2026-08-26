package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatLoopControlPolicyTest {
    private val audio = PcmAudio(
        name = "loop.wav",
        samples = ShortArray(400),
        sampleRate = 1_000,
    )
    private val assigned = PadModel(0, audio, 0, audio.frameCount)

    @Test
    fun aNewLoopRequiresAnAssignedPadWithoutLoadingOrRecordingOwnership() {
        val idle = state()
        assertTrue(idle.beatLoopControlEnabled)
        assertFalse(idle.copy(isLoading = true).beatLoopControlEnabled)

        RecordingPhase.entries.forEach { phase ->
            assertFalse(
                idle.copy(
                    recordingSession = RecordingSession.Active(
                        RecordingKind.SOURCE_MICROPHONE,
                        phase,
                    ),
                ).beatLoopControlEnabled,
                "phase=$phase",
            )
        }

        assertFalse(state(assignedPad = false).beatLoopControlEnabled)
    }

    @Test
    fun anOwnedLoopRemainsStoppableWhileProductionIsBusy() {
        val active = state().copy(
            loopingPadIndex = 0,
            isLoading = true,
            recordingSession = RecordingSession.Active(
                RecordingKind.VOCAL_OVERDUB,
                RecordingPhase.RECORDING,
            ),
        )

        assertTrue(active.beatLoopControlEnabled)
    }

    private fun state(assignedPad: Boolean = true): SamplerUiState = SamplerUiState(
        selectedPad = 0,
        pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0 && assignedPad) assigned else PadModel(index)
        },
    )
}
