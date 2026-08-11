package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplerEngineVoiceTest {
    @Test
    fun runningLoopAppliesLivePitchToneAndLevelWithoutRestartingItsCursor() {
        val audio = PcmAudio(
            name = "loop.wav",
            samples = ShortArray(128) { 8_000 },
            sampleRate = 48_000,
        )
        val initialPad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            pitchSemitones = 0f,
            tone = 1f,
            gain = 1f,
            playMode = PadPlayMode.LOOP,
        )
        val voice = SamplerEngine.Voice(
            SamplerEngine.PadSnapshot.from(initialPad),
            outputSampleRate = 48_000,
        )

        repeat(10) { voice.render(outputSampleRate = 48_000) }
        val frameBeforeEdit = voice.currentFrame

        voice.updateLiveParameters(
            SamplerEngine.PadSnapshot.from(
                initialPad.copy(
                    pitchSemitones = 12f,
                    tone = 0.25f,
                    gain = 0.5f,
                ),
            ),
            outputSampleRate = 48_000,
        )

        assertEquals(frameBeforeEdit, voice.currentFrame)
        assertEquals(2.0, voice.liveSourceStep, 0.0001)
        assertEquals(0.25f, voice.liveTone, 0.0001f)
        assertEquals(0.5f, voice.liveGain, 0.0001f)

        voice.render(outputSampleRate = 48_000)
        assertEquals(frameBeforeEdit + 2, voice.currentFrame)
    }

    @Test
    fun pooledVoiceCanBeDeactivatedAndStartedWithDifferentAudio() {
        val firstAudio = PcmAudio(
            name = "first.wav",
            samples = ShortArray(32) { 4_000 },
            sampleRate = 48_000,
        )
        val secondAudio = PcmAudio(
            name = "second.wav",
            samples = ShortArray(48) { 8_000 },
            sampleRate = 48_000,
        )
        val voice = SamplerEngine.Voice()

        assertFalse(voice.active)
        voice.start(
            SamplerEngine.PadSnapshot.from(
                PadModel(globalIndex = 0, audio = firstAudio, startFrame = 0, endFrame = 32),
            ),
            outputSampleRate = 48_000,
        )
        assertTrue(voice.active)
        voice.render(outputSampleRate = 48_000)

        voice.deactivate()
        voice.start(
            SamplerEngine.PadSnapshot.from(
                PadModel(
                    globalIndex = 17,
                    audio = secondAudio,
                    startFrame = 8,
                    endFrame = 40,
                    reverse = true,
                ),
            ),
            outputSampleRate = 48_000,
        )

        assertTrue(voice.active)
        assertEquals(17, voice.padIndex)
        assertEquals(39, voice.currentFrame)
    }
}
