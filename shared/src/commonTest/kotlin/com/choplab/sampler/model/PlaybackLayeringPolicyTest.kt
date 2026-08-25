package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLayeringPolicyTest {
    @Test
    fun retriggerConflictsWithTheSamePadButNotAnotherPad() {
        assertTrue(samePadVoiceConflictsForRetrigger(activePadIndex = 4, requestedPadIndex = 4))
        assertFalse(samePadVoiceConflictsForRetrigger(activePadIndex = 7, requestedPadIndex = 4))
    }

    @Test
    fun selectedVocalLoopIsNotStartedAgainAsItsOwnCompanionLayer() {
        val audio = PcmAudio(
            name = "voice.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val pads = listOf(
            PadModel(4, audio, 0, audio.frameCount, contentKind = PadContentKind.VOCAL),
            PadModel(5, audio, 0, audio.frameCount, contentKind = PadContentKind.VOCAL),
            PadModel(6, audio, 0, audio.frameCount, contentKind = PadContentKind.SAMPLE),
            PadModel(7, contentKind = PadContentKind.VOCAL),
        )

        assertEquals(
            listOf(5),
            pads.vocalCompanionPadIndicesForLoopStart(loopPadIndex = 4),
        )
    }
}
