package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio

/** Recoverable failure while preparing or starting a complete Beat-loop candidate session. */
class DesktopLoopSessionStartupException(cause: Exception) : Exception(
    cause.message ?: "Beat-loop session startup failed",
    cause,
)

/** Platform audio port used by the desktop controller and test fakes. */
interface DesktopSamplerAudioEngine : AutoCloseable {
    val isSourcePlaying: Boolean

    fun loadPcm(audio: PcmAudio, pitchSemitones: Float = 0f)
    fun playFrom(frame: Int)
    fun seekSource(frame: Int)
    fun sourceFramePosition(): Int
    fun padFramePosition(index: Int): Int?
    fun stop()
    /**
     * Retriggers one PAD monophonically while allowing different PADs to layer.
     * If replacement startup fails, implementations must leave existing voices intact.
     * Returns the exact ownership token for the successfully started voice.
     */
    fun triggerPad(pad: PadModel, forceLoop: Boolean = false): Long
    /**
     * Starts the complete Beat-loop session before retiring existing source/PAD playback.
     * Preparation or startup failure must abandon every candidate, preserve prior playback,
     * and throw [DesktopLoopSessionStartupException]. Failures after candidate startup are not
     * recoverable under this contract and must propagate unchanged.
     */
    fun startExclusiveLoopSession(loopPad: PadModel, companionPads: List<PadModel>)
    fun releasePad(index: Int)
    /** Releases only the GATE voice created by [triggerPad] with [ownership]. */
    fun releasePadIfOwned(index: Int, ownership: Long)
    fun stopPad(index: Int)
    fun stopAll()
}
