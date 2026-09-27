package com.choplab.sampler.next

import java.util.concurrent.atomic.AtomicLong

/** Frames the editor drew and those that took 1/30 s or longer, for the diagnostics card. Counts only, no identifiers. */
class FrameCounter {
    val drawn = AtomicLong()
    val slow = AtomicLong()

    /** [totalNanos] is one frame's whole duration as the platform measured it. */
    fun record(totalNanos: Long) {
        drawn.incrementAndGet()
        if (totalNanos >= SLOW_NANOS) slow.incrementAndGet()
    }

    companion object {
        const val SLOW_NANOS = 1_000_000_000L / 30
    }
}
