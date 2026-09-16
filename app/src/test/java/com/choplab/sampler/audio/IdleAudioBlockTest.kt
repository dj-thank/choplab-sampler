package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleAudioBlockTest {
    private fun voices() = Array(32) { SamplerEngine.Voice() }

    private fun activeVoice(index: Int, mode: PadPlayMode = PadPlayMode.ONE_SHOT): SamplerEngine.Voice {
        val audio = PcmAudio(name = "synthetic", samples = ShortArray(512) { 8_000 }, sampleRate = 48_000)
        return SamplerEngine.Voice(
            SamplerEngine.PadSnapshot.from(PadModel(index, audio, 0, 512, playMode = mode)),
            48_000,
        )
    }

    @Test fun idleSkipsMixingAtEverySupportedBlockSize() {
        val voices = voices()
        for (size in listOf(96, 128, 192, 256, 512)) {
            assertEquals(0, activeRenderFrameCount(size, false, false, false, voices))
        }
    }

    @Test fun everyNonIdleSourceCombinationKeepsTheCompleteBlock() {
        val voices = voices()
        for (flags in 1..7) {
            assertEquals(192, activeRenderFrameCount(
                192, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, voices,
            ))
        }
    }

    @Test fun anyVoiceSlotIncludingTheLastOneKeepsMixing() {
        for (index in 0 until 32) {
            val voices = voices()
            voices[index] = activeVoice(index)
            assertEquals(192, activeRenderFrameCount(192, false, false, false, voices))
        }
    }

    @Test fun releaseTailIsNotMistakenForIdle() {
        val voices = voices()
        voices[7] = activeVoice(7)
        voices[7].release(48)
        assertTrue(voices[7].active)
        assertEquals(192, activeRenderFrameCount(192, false, false, false, voices))
    }

    @Test fun loopVoiceKeepsMixingWithoutTheTransport() {
        val voices = voices()
        voices[31] = activeVoice(31, PadPlayMode.LOOP)
        assertEquals(192, activeRenderFrameCount(192, false, false, false, voices))
    }

    @Test fun deactivatedLastVoiceReturnsToIdle() {
        val voices = voices()
        voices[31] = activeVoice(31)
        voices[31].deactivate()
        assertEquals(0, activeRenderFrameCount(192, false, false, false, voices))
    }

    @Test fun idleDecisionDoesNotAdvanceOrDeactivateAVoice() {
        val voices = voices()
        val voice = activeVoice(4)
        voices[4] = voice
        val before = voice.currentFrame
        repeat(1_000) { activeRenderFrameCount(192, false, false, false, voices) }
        assertEquals(before, voice.currentFrame)
        assertTrue(voice.active)
    }
}
