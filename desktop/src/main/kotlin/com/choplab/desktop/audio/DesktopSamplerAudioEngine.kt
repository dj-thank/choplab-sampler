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
     */
    fun triggerPad(pad: PadModel, forceLoop: Boolean = false)
    fun releasePad(index: Int)
    fun stopPad(index: Int)
    fun stopAll()
}
