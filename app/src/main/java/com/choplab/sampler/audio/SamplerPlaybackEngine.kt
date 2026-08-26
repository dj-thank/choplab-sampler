package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio

/** Temporary coexistence boundary for the legacy AudioTrack and future native engines. */
interface SamplerPlaybackEngine {
    val currentStep: Int
    val currentSourceFrame: Int
    val sourcePlaying: Boolean
    val currentLoopPad: Int
    val currentLoopFrame: Int
    val currentScratchPad: Int
    val currentScratchFrame: Int
    val outputSampleRate: Int

    fun start(): Result<Unit>
    fun updatePad(pad: PadModel)
    fun updateAllPads(pads: List<PadModel>)
    /** Returns an ownership token only when the realtime trigger command was admitted. */
    fun triggerPad(globalIndex: Int): Long?
    /** Returns true only when one complete owner/companion loop session command was admitted. */
    fun startPadLoopSession(loopPad: PadModel, companionPads: List<PadModel>): Boolean
    fun stopPad(globalIndex: Int)
    fun beginScratch(globalIndex: Int, startFrame: Int)
    fun beginSourceScratch(audio: PcmAudio, startFrame: Int, endFrame: Int)
    fun updateScratchSpeed(speed: Float)
    fun endScratch()
    fun releasePad(globalIndex: Int)
    fun releasePadIfOwned(globalIndex: Int, ownership: Long)
    fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int)
    fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float)
    fun stopSource()
    fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float)
    fun setPatternSequence(patterns: List<Set<Int>>, bpm: Float, swing: Float) {
        setPattern(patterns.firstOrNull() ?: emptySet(), bpm, swing)
    }
    fun startTransport()
    fun stopTransport()
    fun stopAllVoices()
    fun stopAllPlayback() {
        stopAllVoices()
        stopTransport()
    }
    fun shutdown()
}
