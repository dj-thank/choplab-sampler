package com.choplab.sampler.model

/**
 * Carries the project-replacement epoch from recording start to its delayed file result.
 * This object is owned and called by the ViewModel dispatcher.
 */
class SourceCaptureOperation(
    private val projectOperations: ProjectOperationEpoch,
) {
    private var pendingEpoch: Long? = null

    fun begin(): Long = projectOperations.begin().also { pendingEpoch = it }

    fun ensureStarted(): Long = pendingEpoch ?: begin()

    fun consumeOrBegin(): Long = pendingEpoch
        ?.also { pendingEpoch = null }
        ?: projectOperations.begin()

    fun discard(epoch: Long) {
        if (pendingEpoch == epoch) pendingEpoch = null
    }
}
