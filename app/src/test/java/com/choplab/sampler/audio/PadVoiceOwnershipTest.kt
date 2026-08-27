package com.choplab.sampler.audio

import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadVoiceOwnershipTest {
    @Test
    fun rejectedRealtimeTriggerDoesNotSupersedeAnAdmittedVoice() {
        val ownership = PadVoiceOwnership(padCount = 1)
        val admittedVoice = ownership.acquire(0)
        val mailbox = RealtimeCommandMailbox<String, Unit>(capacity = 1)
        assertTrue(mailbox.offer("occupied"))

        val rejected = ownership.offerOwned(
            padIndex = 0,
            offerPrepared = mailbox::offerPrepared,
        ) { token -> "trigger:$token" }

        assertNull(rejected)
        assertEquals(admittedVoice, ownership.current(0))
        assertTrue(matchesPadVoiceRelease(admittedVoice, admittedVoice))
    }

    @Test
    fun pointerReleaseTargetsItsVoiceWithoutTouchingANewerSequencerVoice() {
        val ownership = PadVoiceOwnership(padCount = 1)
        val pointerVoice = ownership.acquire(0)
        val sequencerVoice = ownership.acquire(0)

        val audio = PcmAudio(
            name = "gate.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val pad = SamplerEngine.PadSnapshot(
            padIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            pitchSemitones = 0f,
            tone = 1f,
            gain = 1f,
            reverse = false,
            playMode = PadPlayMode.GATE,
            chokeGroup = 0,
        )
        val pointer = SamplerEngine.Voice().apply {
            start(pad, outputSampleRate = 48_000, ownership = pointerVoice)
        }
        val sequencer = SamplerEngine.Voice().apply {
            start(pad, outputSampleRate = 48_000, ownership = sequencerVoice)
        }

        assertTrue(matchesPadVoiceRelease(pointerVoice, pointerVoice))
        assertFalse(matchesPadVoiceRelease(sequencerVoice, pointerVoice))
        assertTrue(matchesPadVoiceRelease(sequencerVoice, requestedOwnership = null))

        releaseMatchingPadVoices(
            voices = arrayOf(pointer, sequencer),
            padIndex = 0,
            playMode = PadPlayMode.GATE,
            frames = 1,
            ownership = pointerVoice,
        )
        pointer.render(outputSampleRate = 48_000)
        sequencer.render(outputSampleRate = 48_000)

        assertTrue(pointer.finished)
        assertFalse(sequencer.finished)
    }
}
