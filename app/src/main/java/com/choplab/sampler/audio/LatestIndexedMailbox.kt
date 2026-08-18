package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Fixed-capacity, latest-wins handoff for independently addressable realtime state.
 *
 * Producers allocate [Update] objects away from the audio thread. The consumer takes
 * dirty bit words and existing objects only; it never grows a collection or blocks.
 */
internal class LatestIndexedMailbox<T>(val capacity: Int) {
    internal data class Update<T>(val value: T?)

    private val slots: AtomicReferenceArray<Update<T>?>
    private val dirtyWords: AtomicLongArray

    init {
        require(capacity > 0) { "capacity must be positive" }
        slots = AtomicReferenceArray(capacity)
        dirtyWords = AtomicLongArray((capacity + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
    }

    val wordCount: Int
        get() = dirtyWords.length()

    fun publish(index: Int, value: T?) {
        require(index in 0 until capacity) { "index out of range: $index" }
        slots.set(index, Update(value))
        val wordIndex = index / Long.SIZE_BITS
        val mask = 1L shl (index % Long.SIZE_BITS)
        while (true) {
            val previous = dirtyWords.get(wordIndex)
            if (dirtyWords.compareAndSet(wordIndex, previous, previous or mask)) return
        }
    }

    /** Audio-thread only. */
    fun takeDirtyWord(wordIndex: Int): Long = dirtyWords.getAndSet(wordIndex, 0L)

    /** Audio-thread only. */
    fun take(index: Int): Update<T>? = slots.getAndSet(index, null)

    fun clear() {
        var index = 0
        while (index < capacity) {
            slots.set(index, null)
            index++
        }
        var word = 0
        while (word < wordCount) {
            dirtyWords.set(word, 0L)
            word++
        }
    }
}
