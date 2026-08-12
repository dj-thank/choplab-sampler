package com.choplab.sampler.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A non-blocking MPSC queue with a hard reservation limit.
 *
 * Producers allocate command nodes away from the audio thread. The audio-thread consumer
 * only polls accepted commands and can never be trapped draining an unbounded backlog.
 */
internal class BoundedRealtimeQueue<T>(capacity: Int) {
    private val capacity = capacity.coerceAtLeast(1)
    private val queue = ConcurrentLinkedQueue<T>()
    private val reservedSlots = AtomicInteger(0)

    val size: Int
        get() = reservedSlots.get()

    fun offer(value: T): Boolean {
        return offerPrepared { value }
    }

    /** Reserves capacity before running producer-side command preparation. */
    fun offerPrepared(valueFactory: () -> T): Boolean {
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
        queue.add(value)
        return true
    }

    fun poll(): T? {
        val value = queue.poll() ?: return null
        reservedSlots.decrementAndGet()
        return value
    }

    fun clear() {
        while (poll() != null) {
            // Drain accepted nodes so every reservation is released.
        }
    }
}
