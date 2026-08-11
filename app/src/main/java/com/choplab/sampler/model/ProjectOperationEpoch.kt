package com.choplab.sampler.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Owns completion rights for the newest asynchronous project replacement.
 * Callers run [completeIfCurrent] on the owning ViewModel dispatcher so the check and mutation
 * cannot be interleaved by another project action.
 */
class ProjectOperationEpoch {
    private val currentEpoch = AtomicLong(0L)

    fun begin(): Long = currentEpoch.incrementAndGet()

    fun invalidate(): Long = currentEpoch.incrementAndGet()

    fun isCurrent(epoch: Long): Boolean = currentEpoch.get() == epoch

    fun completeIfCurrent(epoch: Long, completion: () -> Unit): Boolean {
        if (!isCurrent(epoch)) return false
        completion()
        return true
    }
}
