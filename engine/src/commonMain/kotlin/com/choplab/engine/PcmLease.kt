package com.choplab.engine

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Worker/control ownership. Render retains assets through PcmOwnership's preallocated slots instead. */
@OptIn(ExperimentalAtomicApi::class)
class PcmLease internal constructor(val pcm: PcmAsset) : AutoCloseable {
    private val closed = AtomicInt(0)
    override fun close() { if (closed.compareAndSet(0, 1)) pcm.releasePcm() }
}

/** Keeps every loaded asset protected while a program is prepared, including late/cancelled completions. */
@OptIn(ExperimentalAtomicApi::class)
class PcmLeaseGroup(leases: List<PcmLease>) : AutoCloseable {
    private val owned = leases.toTypedArray()
    private val closed = AtomicInt(0)
    override fun close() { if (closed.compareAndSet(0, 1)) for (lease in owned) lease.close() }
}
