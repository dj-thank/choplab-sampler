package com.choplab.sampler.audio

import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.PadPlayMode
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free ownership for Android PAD voices.
 *
 * A token is allocated only from an admitted command factory. Sequencer voices allocate from the
 * same per-PAD sequence on the audio thread, so a delayed pointer release can target its own voice
 * without releasing a newer sequencer or controller voice.
 */
internal class PadVoiceOwnership(
    private val padCount: Int = SamplerConfig.PAD_COUNT,
) {
    private val generations = AtomicLongArray(padCount)

    fun acquire(padIndex: Int): Long {
        if (padIndex !in 0 until padCount) return NONE
        while (true) {
            val current = generations.get(padIndex)
            val next = if (current == Long.MAX_VALUE || current == NONE) 1L else current + 1L
            if (generations.compareAndSet(padIndex, current, next)) return next
        }
    }

    /**
     * Reserves the bounded command slot before publishing ownership.
     *
     * [offerPrepared] must invoke its factory only after it has reserved capacity, matching
     * [RealtimeCommandMailbox.offerPrepared]. A rejected trigger therefore cannot supersede a
     * voice that was actually admitted earlier.
     */
    fun <T> offerOwned(
        padIndex: Int,
        offerPrepared: ((() -> T) -> Boolean),
        command: (Long) -> T,
    ): Long? {
        if (padIndex !in 0 until padCount) return null
        var ownership = NONE
        val accepted = offerPrepared {
            acquire(padIndex).also { ownership = it }.let(command)
        }
        return ownership.takeIf { accepted && it != NONE }
    }

    internal fun current(padIndex: Int): Long =
        if (padIndex in 0 until padCount) generations.get(padIndex) else NONE

    companion object {
        const val NONE: Long = Long.MIN_VALUE
    }
}

internal fun matchesPadVoiceRelease(
    voiceOwnership: Long,
    requestedOwnership: Long?,
): Boolean = requestedOwnership == null ||
    (requestedOwnership != PadVoiceOwnership.NONE && voiceOwnership == requestedOwnership)

/** Audio-thread-only release filter; no collection or temporary allocation is created. */
internal fun releaseMatchingPadVoices(
    voices: Array<SamplerEngine.Voice>,
    padIndex: Int,
    playMode: PadPlayMode? = null,
    frames: Int,
    ownership: Long? = null,
) {
    var index = 0
    while (index < voices.size) {
        val voice = voices[index]
        if (
            voice.active &&
            voice.padIndex == padIndex &&
            (playMode == null || voice.playMode == playMode) &&
            matchesPadVoiceRelease(voice.ownership, ownership)
        ) {
            voice.release(frames)
        }
        index++
    }
}
