package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class StopAllPlaybackTest {
    @Test
    fun layeredTransportStartsLoopBeforeStepsAndFailsClosedOnRejectedSteps() {
        val audio = PcmAudio(name = "loop", samples = ShortArray(1024), sampleRate = 48000)
        val state = com.choplab.sampler.model.SamplerUiState(pads = List(com.choplab.sampler.model.SamplerConfig.PAD_COUNT) {
            if (it == 0) PadModel(0, audio, 0, 1024, playMode = com.choplab.sampler.model.PadPlayMode.LOOP) else PadModel(it)
        })
        val accepted = RecordingPlaybackEngine()
        org.junit.Assert.assertTrue(startAndroidLayeredTransport(accepted, state))
        assertEquals(listOf("loop", "transport"), accepted.calls)
        val rejectedTransport = RecordingPlaybackEngine().apply { transportAccepted = false }
        org.junit.Assert.assertFalse(startAndroidLayeredTransport(rejectedTransport, state))
        assertEquals(listOf("loop", "transport", "stop-all-boundary", "stop-transport"), rejectedTransport.calls)
        val rejectedLoop = RecordingPlaybackEngine().apply { loopAccepted = false }
        org.junit.Assert.assertFalse(startAndroidLayeredTransport(rejectedLoop, state))
        assertEquals(listOf("loop"), rejectedLoop.calls)
    }

    @Test fun addingDrumsToAnActiveCoreStartsOnlyTheTransport() {
        val audio = PcmAudio(name = "loop", samples = ShortArray(1024), sampleRate = 48000)
        val state = com.choplab.sampler.model.SamplerUiState(loopingPadIndex = 1,
            pads = List(com.choplab.sampler.model.SamplerConfig.PAD_COUNT) {
                if (it < 2) PadModel(it, audio, 0, 1024, playMode = com.choplab.sampler.model.PadPlayMode.LOOP) else PadModel(it)
            })
        val engine = RecordingPlaybackEngine()
        org.junit.Assert.assertTrue(startAndroidLayeredTransport(engine, state))
        assertEquals(listOf("transport"), engine.calls)
    }

    @Test
    fun stopBoundaryIsPublishedBeforeTransportStop() {
        val engine = RecordingPlaybackEngine()

        engine.stopAllPlayback()

        assertEquals(listOf("stop-all-boundary", "stop-transport"), engine.calls)
    }
}

private class RecordingPlaybackEngine : SamplerPlaybackEngine {
    val calls = mutableListOf<String>()
    var loopAccepted = true
    var transportAccepted = true

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
    override fun triggerPad(globalIndex: Int): Long? = 1L
    override fun setPadLoopLayer(pad: PadModel, enabled: Boolean): Boolean = true
    override fun startPadLoopSession(loopPad: PadModel, companionPads: List<PadModel>): Boolean { calls += "loop"; return loopAccepted }
    override fun stopPad(globalIndex: Int) = Unit
    override fun beginScratch(globalIndex: Int, startFrame: Int) = Unit
    override fun beginSourceScratch(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
    override fun updateScratchSpeed(speed: Float) = Unit
    override fun endScratch() = Unit
    override fun releasePad(globalIndex: Int) = Unit
    override fun releasePadIfOwned(globalIndex: Int, ownership: Long) = Unit
    override fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int) = Unit
    override fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float) = true
    override fun stopSource() = Unit
    override fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float) = Unit
    override fun startTransport(): Boolean { calls += "transport"; return transportAccepted }
    override fun stopTransport() {
        calls += "stop-transport"
    }
    override fun stopAllVoices() {
        calls += "stop-all-boundary"
    }
    override fun shutdown() = Unit
}
