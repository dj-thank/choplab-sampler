package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio

/** Recoverable failure while preparing or starting a complete Beat-loop candidate session. */
class DesktopLoopSessionStartupException(cause: Exception) : Exception(
    cause.message ?: "Beat-loop session startup failed",
    cause,
)

/** Started candidate voices resolved exactly once by handoff success or fail-closed abandonment. */
interface DesktopStartedLoopSession {
    fun retirePriorPlayback()
    fun abandonCandidates()
}

/** Prepared clips whose potentially slow startup remains outside the controller handoff boundary. */
fun interface DesktopPreparedLoopSession {
    fun startCandidates(): DesktopStartedLoopSession
}

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
     * Prepares a complete Beat-loop session without retiring existing source/PAD playback.
     * [DesktopPreparedLoopSession.startCandidates] may also be slow and must run before the
     * controller enters its handoff boundary. Preparation or startup failure must abandon every
     * candidate, preserve prior playback, and throw [DesktopLoopSessionStartupException]. Once
     * startup succeeds, retirement failures are non-recoverable: callers must abandon the started
     * candidates before propagating the original failure unchanged.
     */
    fun prepareExclusiveLoopSession(
        loopPad: PadModel,
        companionPads: List<PadModel>,
    ): DesktopPreparedLoopSession
    fun releasePad(index: Int)
    /** Releases only the GATE voice created by [triggerPad] with [ownership]. */
    fun releasePadIfOwned(index: Int, ownership: Long)
    fun stopPad(index: Int)
    fun stopAll()
}
