package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded command ingress with an out-of-band stop boundary.
 *
 * Producers allocate immutable entries away from the audio thread. A stop request never
 * competes for bounded queue capacity, and commands issued before the newest applied stop
 * can be discarded deterministically by the consumer.
 */
internal class RealtimeCommandMailbox<T, S>(capacity: Int) {
    internal data class Entry<T>(val sequence: Long, val value: T)
    internal data class StopRequest<S>(val sequence: Long, val payload: S)

    private val nextSequence = AtomicLong(0L)
    private val queue = BoundedRealtimeQueue<Entry<T>>(capacity)
    private val pendingStop = AtomicReference<StopRequest<S>?>(null)
    private val appliedStopSequence = AtomicLong(0L)
    private val producerOrderLock = Any()

    val size: Int
        get() = queue.size

    fun offer(value: T): Boolean = synchronized(producerOrderLock) {
        queue.offer(Entry(sequence = nextSequence.incrementAndGet(), value = value))
    }

    /** Reserves a bounded slot before invoking producer-side state changes. */
    fun offerPrepared(valueFactory: () -> T): Boolean = synchronized(producerOrderLock) {
        queue.offerPrepared {
            Entry(
                sequence = nextSequence.incrementAndGet(),
                value = valueFactory(),
            )
        }
    }

    fun requestStop(payload: S) = synchronized(producerOrderLock) {
        pendingStop.set(
            StopRequest(
                sequence = nextSequence.incrementAndGet(),
                payload = payload,
            ),
        )
    }

    /** Called only by the audio-thread consumer. */
    fun takeLatestStop(): StopRequest<S>? {
        while (true) {
            val request = pendingStop.getAndSet(null) ?: return null
            while (true) {
                val applied = appliedStopSequence.get()
                if (request.sequence <= applied) break
                if (appliedStopSequence.compareAndSet(applied, request.sequence)) return request
            }
        }
    }

    fun pollEntry(): Entry<T>? = queue.poll()

    fun shouldProcess(entry: Entry<T>): Boolean {
        val pendingBoundary = pendingStop.get()?.sequence ?: 0L
        val stopBoundary = maxOf(appliedStopSequence.get(), pendingBoundary)
        return entry.sequence > stopBoundary
    }

    fun clear() {
        synchronized(producerOrderLock) {
            queue.clear()
            pendingStop.set(null)
        }
    }
}
