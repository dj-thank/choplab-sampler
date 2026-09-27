package com.choplab.engine

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class PcmReadStatus { READY, PREFETCHING, FAILED, CLOSED }

/** Worker/control snapshot. Reading PCM never allocates this object. */
data class PcmPageStats(val status: PcmReadStatus, val retainedBytes: Long, val capacityBytes: Long,
    val misses: Long, val droppedRequests: Long, val pendingRequests: Int, val loadedPages: Int)

/**
 * Bounded immutable-page handoff. Only [sample] and [request] run on render; neither calls the provider,
 * allocates, locks, spins indefinitely, or waits for a page. Missing PCM is silence and increments [misses].
 * One worker publishes defensive copies. A retired page remains immutable while a reader holds it, so
 * eviction cannot rewrite a sounding frame. Metadata is bounded by the declared source frame count.
 */
@OptIn(ExperimentalAtomicApi::class)
class PagedPcm(val frameCount: Int, val pageFrames: Int = PAGE_FRAMES,
    val maximumPages: Int = DEFAULT_PAGES, requestCapacity: Int = 256, workerBytes: Long = 0) {
    private class Page(val samples: FloatArray) {
        @Volatile var accessed = true
        val hinted = AtomicInt(0)
    }
    private class Entry {
        @Volatile var page: Page? = null
        val requested = AtomicInt(0)
        @Volatile var retryAfter = 0L
    }
    private val entries: Array<Entry>
    private val requests = PcmRequestRing(requestCapacity)
    private val slots: IntArray
    private var clock = 0 // worker only
    @Volatile private var loaded = 0
    @Volatile private var failed = false
    @Volatile private var closed = false
    private val missCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    /** Includes one worker copy and one retired read page; pages never grow with playback time. */
    val capacityBytes: Long
    val pageCount: Int
    val misses: Long get() = missCount.load()
    init {
        require(frameCount > 0 && pageFrames in 256..65_536 && pageFrames.countOneBits() == 1)
        require(maximumPages in 4..4096 && workerBytes in 0..EngineFormat.MAX_RESIDENT_BYTES)
        pageCount = ((frameCount.toLong() + pageFrames - 1) / pageFrames).toInt()
        entries = Array(pageCount) { Entry() }
        slots = IntArray(minOf(pageCount, maximumPages)) { -1 }
        capacityBytes = (slots.size.toLong() + 2) * pageFrames * 8 + workerBytes + pageCount * 64L
        require(capacityBytes <= EngineFormat.MAX_RESIDENT_BYTES)
    }

    /** Source frame, never a sample index. End-exclusive/outside requests are harmless. */
    fun request(frame: Int): Boolean = frame in 0 until frameCount && requestPage(frame / pageFrames)

    private fun requestPage(index: Int): Boolean {
        if (closed || failed || index !in entries.indices) return false
        val entry = entries[index]
        if (entry.page != null || entry.requested.load() != 0) return true
        if (requests.consumed < entry.retryAfter) return false
        if (!entry.requested.compareAndSet(0, 1)) return true
        if (requests.offer(index)) return true
        entry.retryAfter = requests.consumed + 1
        entry.requested.store(0)
        droppedCount.fetchAndAdd(1)
        return false
    }

    internal fun sample(frame: Int, channel: Int): Float {
        val index = frame / pageFrames
        val page = entries[index].page
        if (page == null || closed || failed) {
            missCount.fetchAndAdd(1)
            requestPage(index)
            return 0f
        }
        page.accessed = true
        if (page.hinted.compareAndSet(0, 1)) {
            // Both directions matter: reverse PADs and HAND may move at negative speed.
            requestPage(index + 1); requestPage(index - 1)
            requestPage(index + 2); requestPage(index - 2)
        }
        return page.samples[(frame % pageFrames) * 2 + channel]
    }

    /** One worker consumes page numbers, then calls [publish] or [fail]. No render-side callback. */
    fun nextRequest(): Int = requests.poll()
    fun isLoaded(page: Int): Boolean = page in entries.indices && entries[page].page != null && !closed && !failed

    /** Fully decoded, finite stereo PCM. Caller may reuse its input after this returns. Worker only. */
    fun publish(page: Int, samples: FloatArray): Boolean {
        require(page in entries.indices)
        val count = minOf(pageFrames, frameCount - page * pageFrames)
        require(samples.size == count * 2 && samples.all { it.isFinite() })
        if (closed || failed) { entries[page].requested.store(0); return false }
        if (entries[page].page == null) {
            var chosen = -1
            for (attempt in 0 until slots.size * 2 + 1) {
                val slot = clock
                clock = (clock + 1) % slots.size
                val previous = slots[slot]
                if (previous < 0) { chosen = slot; loaded++; break }
                val old = entries[previous].page
                if (old == null || !old.accessed) {
                    entries[previous].page = null
                    chosen = slot
                    break
                }
                old.accessed = false
            }
            check(chosen >= 0)
            slots[chosen] = page
            entries[page].page = Page(samples.copyOf())
        }
        entries[page].requested.store(0)
        return true
    }

    /** Failure/cancellation never publishes a partial page. A new owner can retry with a fresh cache. */
    fun fail() { failed = true }
    fun close() { closed = true }
    fun statistics(): PcmPageStats = PcmPageStats(
        when { closed -> PcmReadStatus.CLOSED; failed -> PcmReadStatus.FAILED
            requests.pending > 0 -> PcmReadStatus.PREFETCHING; else -> PcmReadStatus.READY },
        loaded.toLong() * pageFrames * 8, capacityBytes, misses, droppedCount.load(), requests.pending, loaded)

    companion object {
        const val PAGE_FRAMES = 4096
        const val DEFAULT_PAGES = 512
    }
}

/** Bounded multi-producer/single-worker primitive queue; only eight CAS attempts on an audio call. */
@OptIn(ExperimentalAtomicApi::class)
private class PcmRequestRing(private val capacity: Int) {
    private class Slot { @Volatile var value = -1; val ready = AtomicLong(-1) }
    private val slots = Array(capacity) { Slot() }
    private val write = AtomicLong(0)
    private val read = AtomicLong(0)
    val consumed: Long get() = read.load()
    val pending: Int get() = (write.load() - read.load()).coerceIn(0, capacity.toLong()).toInt()
    init { require(capacity in 4..8192 && capacity.countOneBits() == 1) }
    fun offer(value: Int): Boolean {
        repeat(8) {
            val at = write.load()
            if (at - read.load() >= capacity) return false
            if (write.compareAndSet(at, at + 1)) {
                val slot = slots[(at and (capacity - 1).toLong()).toInt()]
                slot.value = value
                slot.ready.store(at)
                return true
            }
        }
        return false
    }
    fun poll(): Int {
        val at = read.load()
        val slot = slots[(at and (capacity - 1).toLong()).toInt()]
        if (slot.ready.load() != at) return -1
        val value = slot.value
        read.store(at + 1)
        return value
    }
}
