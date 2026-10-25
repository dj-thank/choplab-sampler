package com.choplab.apple

import com.choplab.engine.EngineFormat
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class PcmBudgetExceeded : IllegalStateException("Audio memory limit reached")

/** One host ledger: cache, input/copy/resample, export and live output reserve before allocation. */
@OptIn(ExperimentalAtomicApi::class)
internal class PcmAdmission(val limit: Long = EngineFormat.MAX_RESIDENT_BYTES) {
    private val used = AtomicLong(0)
    private val peak = AtomicLong(0)
    init { require(limit in 8..EngineFormat.MAX_RESIDENT_BYTES) }
    val usedBytes: Long get() = used.load()
    val peakBytes: Long get() = peak.load()
    fun reserve(bytes: Long): Reservation {
        require(bytes > 0)
        while (true) {
            val before = used.load()
            if (bytes > limit - before) throw PcmBudgetExceeded()
            if (used.compareAndSet(before, before + bytes)) {
                while (true) { val old = peak.load(); if (old >= before + bytes || peak.compareAndSet(old, before + bytes)) break }
                return Reservation(bytes)
            }
        }
    }
    inner class Reservation internal constructor(bytes: Long) : AutoCloseable {
        private val held = AtomicLong(bytes)
        fun shrinkTo(bytes: Long) {
            require(bytes >= 0)
            while (true) {
                val before = held.load(); require(bytes <= before)
                if (held.compareAndSet(before, bytes)) { used.fetchAndAdd(bytes - before); return }
            }
        }
        override fun close() { used.fetchAndAdd(-held.exchange(0)) }
    }
    companion object {
        /** Conservative simultaneous native, stereo, resampler input/output, defensive output and kernel bound. */
        fun decodeBytes(frames: Long, channels: Int, rate: Int): Long {
            require(frames in 1..30_000_000L && channels in 1..2 && rate in 8_000..192_000)
            val output = (frames * 48_000 + rate - 1) / rate
            return frames * channels * 4 + frames * 16 + output * 16 +
                (if (rate == 48_000) 0 else 512L * 4097 * 4) + 512 * 1024
        }
    }
}
