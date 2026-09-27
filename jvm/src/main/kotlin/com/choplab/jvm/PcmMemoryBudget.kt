package com.choplab.jvm

import com.choplab.engine.EngineFormat
import com.choplab.engine.PcmAsset
import com.choplab.engine.PcmLease
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

class PcmMemoryLimit(val requestedBytes: Long, val retainedBytes: Long, val limitBytes: Long) :
    IllegalStateException("PCM memory budget exceeded")

data class PcmMemoryStats(val usedBytes: Long, val peakBytes: Long, val limitBytes: Long,
    val cachedAssets: Int, val leasedAssets: Int)

/**
 * One process-wide PCM admission ledger: active/queued programs, SOURCE, idle LRU and worker copies.
 * Reserve BEFORE allocating. Asset leases only change atomics; eviction and provider disposal run here
 * on a worker, never on render. An idle cache entry is evicted before an admission can be refused.
 */
class PcmMemoryBudget(val limitBytes: Long = EngineFormat.MAX_RESIDENT_BYTES) {
    private class Entry(val pcm: PcmAsset, val owner: Any, val dispose: suspend () -> Unit, var touched: Long)
    private val guard = Mutex()
    private val used = AtomicLong()
    private val peak = AtomicLong()
    private val entries = mutableListOf<Entry>()
    private val closing = mutableMapOf<Any, () -> Unit>()
    private var clock = 0L
    private var reaper: Job? = null
    init { require(limitBytes in 8..EngineFormat.MAX_RESIDENT_BYTES) }

    suspend fun reserve(bytes: Long): Reservation {
        require(bytes > 0)
        return guard.withLock {
            currentCoroutineContext().ensureActive()
            if (bytes > limitBytes) throw PcmMemoryLimit(bytes, used.get(), limitBytes)
            while (bytes > limitBytes - used.get()) {
                val candidate = entries.filter { it.pcm.leaseCount == 0 }.minByOrNull { it.touched }
                    ?: throw PcmMemoryLimit(bytes, used.get(), limitBytes)
                if (!evict(candidate)) continue
            }
            currentCoroutineContext().ensureActive()
            used.addAndGet(bytes).also { value -> peak.accumulateAndGet(value, ::maxOf) }
            Reservation(bytes)
        }
    }

    inner class Reservation internal constructor(bytes: Long) : Closeable {
        private val held = AtomicLong(bytes)
        override fun close() { used.addAndGet(-held.getAndSet(0)) }
        /** After the corresponding buffers are gone. Growth must make a separate checked reservation. */
        fun shrinkTo(bytes: Long) {
            require(bytes >= 0)
            while (true) {
                val before = held.get()
                require(bytes <= before) { "PCM reservations can only shrink" }
                if (held.compareAndSet(before, bytes)) { used.addAndGet(bytes - before); return }
            }
        }
        /** Transfers just the published storage charge; the peak includes its input/defensive copy. */
        internal suspend fun publish(pcm: PcmAsset, owner: Any, dispose: suspend () -> Unit): PcmLease = guard.withLock {
            currentCoroutineContext().ensureActive()
            check(owner !in closing && held.get() >= pcm.residentBytes && entries.none { it.pcm === pcm })
            val lease = pcm.acquire()
            entries.add(Entry(pcm, owner, dispose, ++clock))
            used.addAndGet(pcm.residentBytes - held.getAndSet(0))
            lease
        }
    }

    /** A cancelled unpublished decode has no cache consumer; dispose it without waiting for pressure. */
    internal suspend fun discard(pcm: PcmAsset) = withContext(NonCancellable) {
        guard.withLock { entries.firstOrNull { it.pcm === pcm && pcm.leaseCount == 0 }?.let { evict(it) } }
    }
    suspend fun touch(pcm: PcmAsset) = guard.withLock { entries.firstOrNull { it.pcm === pcm }?.touched = ++clock }
    suspend fun statistics(): PcmMemoryStats = guard.withLock {
        PcmMemoryStats(used.get(), peak.get(), limitBytes, entries.size, entries.count { it.pcm.leaseCount > 0 })
    }
    /** A closing host can leave leases with a late preparation; the worker finishes disposal after it returns. */
    suspend fun releaseOwner(owner: Any, afterLastAsset: () -> Unit) = withContext(NonCancellable + Dispatchers.IO) {
        guard.withLock {
            closing[owner] = afterLastAsset
            collectClosed()
            if (closing.isNotEmpty() && reaper?.isActive != true) reaper = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    delay(20)
                    val done = guard.withLock {
                        collectClosed()
                        closing.isEmpty().also { if (it) reaper = null }
                    }
                    if (done) break
                }
            }
        }
    }
    private suspend fun collectClosed() {
        for (entry in entries.toList()) if (entry.owner in closing && entry.pcm.leaseCount == 0) evict(entry)
        for (owner in closing.keys.toList()) if (entries.none { it.owner === owner }) closing.remove(owner)?.invoke()
    }
    private suspend fun evict(entry: Entry): Boolean = withContext(NonCancellable) {
        val claimed = withContext(Dispatchers.IO) eviction@ {
            if (!entry.pcm.evicted && !entry.pcm.evictIfUnleased()) return@eviction false
            // Wait for the provider's in-flight read/copies before returning any of its charge.
            entry.dispose()
            true
        }
        if (claimed) { entries.remove(entry); used.addAndGet(-entry.pcm.residentBytes) }
        claimed
    }
    companion object { val shared = PcmMemoryBudget() }
}
