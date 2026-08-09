package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio

/** Temporary coexistence boundary for the legacy AudioTrack and future native engines. */
interface SamplerPlaybackEngine {
    val currentStep: Int
    val currentSourceFrame: Int
    val sourcePlaying: Boolean
    val outputSampleRate: Int

    fun start(): Result<Unit>
    fun updatePad(pad: PadModel)
    fun updateAllPads(pads: List<PadModel>)
    fun triggerPad(globalIndex: Int)
    fun releasePad(globalIndex: Int)
    fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int)
    fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float)
    fun stopSource()
    fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float)
    fun startTransport()
    fun stopTransport()
    fun stopAllVoices()
    fun shutdown()
}
