package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class StopAllPlaybackTest {
    @Test
    fun stopBoundaryIsPublishedBeforeTransportStop() {
        val engine = RecordingPlaybackEngine()

        engine.stopAllPlayback()

        assertEquals(listOf("stop-all-boundary", "stop-transport"), engine.calls)
    }
}

private class RecordingPlaybackEngine : SamplerPlaybackEngine {
    val calls = mutableListOf<String>()

    override val currentStep = -1
    override val currentSourceFrame = -1
    override val sourcePlaying = false
    override val currentLoopPad = -1
    override val currentLoopFrame = -1
    override val currentScratchPad = -1
    override val currentScratchFrame = -1
    override val outputSampleRate = 48_000

    override fun start(): Result<Unit> = Result.success(Unit)
    override fun updatePad(pad: PadModel) = Unit
    override fun updateAllPads(pads: List<PadModel>) = Unit
    override fun triggerPad(globalIndex: Int) = Unit
    override fun startPadLoop(globalIndex: Int) = Unit
    override fun stopPad(globalIndex: Int) = Unit
    override fun beginScratch(globalIndex: Int, startFrame: Int) = Unit
    override fun beginSourceScratch(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
    override fun updateScratchSpeed(speed: Float) = Unit
    override fun endScratch() = Unit
    override fun releasePad(globalIndex: Int) = Unit
    override fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
    override fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float) = Unit
    override fun stopSource() = Unit
    override fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float) = Unit
    override fun startTransport() = Unit
    override fun stopTransport() {
        calls += "stop-transport"
    }
    override fun stopAllVoices() {
        calls += "stop-all-boundary"
    }
    override fun shutdown() = Unit
}
