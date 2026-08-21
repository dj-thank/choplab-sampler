package com.choplab.desktop.provider

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic lease for asynchronous provider work.
 *
 * A disconnect cannot forcibly stop an in-flight HTTP call, but it can make every
 * pending callback, credential write, and status update stale before that work commits.
 */
internal class ProviderSessionGeneration {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun snapshot(): Long = generation.get()

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate

    fun requireCurrent(candidate: Long) {
        check(isCurrent(candidate)) { "Provider session was cancelled" }
    }
}
