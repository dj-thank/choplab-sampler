package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps command ownership separate from playback that the audio thread actually applied.
 * Issuing a command invalidates older queued commands without claiming that audio has started.
 */
class SourcePlaybackState {
    private val issuedGeneration = AtomicLong(0L)
    private val appliedState = AtomicLong(encode(generation = 0L, playing = false))

    val isPlaying: Boolean
        get() = appliedState.get() and PLAYING_MASK != 0L

    fun issuePlay(): Long = issue()

    fun issueStop(): Long = issue()

    fun isCurrent(generation: Long): Boolean = issuedGeneration.get() == generation

    /** Called by the audio thread when this queued play command is accepted. */
    fun applyPlay(generation: Long): Boolean = apply(generation, playing = true)

    /** Called by the audio thread when this queued stop command is accepted. */
    fun applyStop(generation: Long): Boolean = apply(generation, playing = false)

    /**
     * Applies an out-of-band Stop All boundary.
     *
     * A newer play may have been issued concurrently after the stop generation. Publishing
     * stopped against the newest issued generation keeps the UI truthful; a play command that
     * is sequenced after the stop boundary can still publish playing when the audio thread
     * subsequently applies it.
     */
    fun applyStopBoundary(generation: Long): Boolean {
        val currentGeneration = issuedGeneration.get()
        if (generation > currentGeneration) return false
        appliedState.set(encode(currentGeneration, playing = false))
        return true
    }

    /** Returns true only when this exact active generation owned the published stop. */
    fun complete(generation: Long): Boolean = appliedState.compareAndSet(
        encode(generation, playing = true),
        encode(generation, playing = false),
    )

    /** Invalidates queued work when no audio-thread acknowledgement can arrive. */
    fun forceStopped() {
        val generation = issue()
        appliedState.set(encode(generation, playing = false))
    }

    private fun issue(): Long = issuedGeneration.incrementAndGet()

    private fun apply(generation: Long, playing: Boolean): Boolean {
        if (!isCurrent(generation)) return false
        appliedState.set(encode(generation, playing))
        return true
    }

    private companion object {
        const val PLAYING_MASK = 1L

        fun encode(generation: Long, playing: Boolean): Long =
            (generation shl 1) or if (playing) PLAYING_MASK else 0L
    }
}
