package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio

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
    fun releasePad(index: Int)
    /** Releases only the GATE voice created by [triggerPad] with [ownership]. */
    fun releasePadIfOwned(index: Int, ownership: Long)
    fun stopPad(index: Int)
    fun stopAll()
}
