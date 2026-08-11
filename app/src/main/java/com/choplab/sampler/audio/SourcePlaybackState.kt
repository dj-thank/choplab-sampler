package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes source playback and command ownership in one atomic generation value.
 * The low bit is the playing flag; the remaining bits are the newest issued generation.
 */
class SourcePlaybackState {
    private val encodedState = AtomicLong(encode(generation = 0L, playing = false))

    val isPlaying: Boolean
        get() = encodedState.get() and PLAYING_MASK != 0L

    fun issuePlay(): Long = issue(playing = true)

    fun issueStop(): Long = issue(playing = false)

    fun isCurrent(generation: Long): Boolean = generationOf(encodedState.get()) == generation

    /** Returns true only when this exact active generation owned the published stop. */
    fun complete(generation: Long): Boolean = encodedState.compareAndSet(
        encode(generation, playing = true),
        encode(generation, playing = false),
    )

    private fun issue(playing: Boolean): Long {
        while (true) {
            val current = encodedState.get()
            val generation = generationOf(current) + 1L
            if (encodedState.compareAndSet(current, encode(generation, playing))) return generation
        }
    }

    private companion object {
        const val PLAYING_MASK = 1L

        fun encode(generation: Long, playing: Boolean): Long =
            (generation shl 1) or if (playing) PLAYING_MASK else 0L

        fun generationOf(encoded: Long): Long = encoded ushr 1
    }
}
