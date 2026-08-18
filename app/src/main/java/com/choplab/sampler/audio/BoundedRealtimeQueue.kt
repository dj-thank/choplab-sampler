package com.choplab.sampler.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A non-blocking MPSC queue with a hard reservation limit.
 *
 * Producers allocate command nodes away from the audio thread. The audio-thread consumer
 * only polls accepted commands and can never be trapped draining an unbounded backlog.
 */
internal class BoundedRealtimeQueue<T>(capacity: Int) {
    private val capacity = capacity.coerceAtLeast(1)
    private val queue = ConcurrentLinkedQueue<Entry<T>>()
    private val reservedSlots = AtomicInteger(0)
    private val generation = AtomicLong(0L)

    val size: Int
        get() = reservedSlots.get()

    fun offer(value: T): Boolean {
        return offerPrepared { value }
    }

    /** Reserves capacity before running producer-side command preparation. */
    fun offerPrepared(valueFactory: () -> T): Boolean {
        val offeredGeneration = generation.get()
        while (true) {
            val current = reservedSlots.get()
            if (current >= capacity) return false
            if (reservedSlots.compareAndSet(current, current + 1)) break
        }
        val value = try {
            valueFactory()
        } catch (throwable: Throwable) {
            reservedSlots.decrementAndGet()
            throw throwable
        }
        val entry = Entry(offeredGeneration, value)
        queue.add(entry)
        if (offeredGeneration == generation.get()) return true
        if (queue.remove(entry)) reservedSlots.decrementAndGet()
        return false
    }

    fun poll(): T? {
        while (true) {
            val entry = queue.poll() ?: return null
            reservedSlots.decrementAndGet()
            if (entry.generation == generation.get()) return entry.value
        }
    }

    fun clear() {
        generation.incrementAndGet()
        while (queue.poll() != null) {
            reservedSlots.decrementAndGet()
            // Drain accepted nodes so every reservation is released.
        }
    }

    private data class Entry<T>(val generation: Long, val value: T)
}
