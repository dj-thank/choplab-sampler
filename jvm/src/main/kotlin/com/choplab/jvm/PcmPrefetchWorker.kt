package com.choplab.jvm

import com.choplab.core.PcmResidency
import com.choplab.core.model.Asset
import com.choplab.engine.PagedPcm
import com.choplab.engine.PcmAsset
import com.choplab.engine.PcmReadStatus
import com.choplab.engine.WindowedResampler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class PcmPrefetchFailure(val status: PcmReadStatus) : IllegalStateException("PCM prefetch is $status")

/** One worker for all page providers. Render only touches PagedPcm's immutable handoff. */
class PcmPrefetchWorker : Closeable {
    private class Source(val pages: WeakReference<PagedPcm>, val input: PcmFrameSource,
        resampler: WindowedResampler) {
        private var resampler: WindowedResampler? = resampler
        val access = Mutex()
        private val closed = AtomicBoolean()
        suspend fun close(releaseStorage: Boolean = false) = access.withLock {
            if (closed.compareAndSet(false, true)) try { input.close() } finally { resampler = null }
            if (releaseStorage) pages.get()?.releaseStorage()
        }
        suspend fun read(first: Int, end: Int, cancelled: () -> Boolean): FloatArray = access.withLock { readLocked(first, end, cancelled) }
        suspend fun publishNext(cancelled: () -> Boolean): Boolean = access.withLock {
            val pages = pages.get() ?: return@withLock false
            if (closed.get() || pages.status == PcmReadStatus.CLOSED) return@withLock false
            val page = pages.nextRequest()
            if (page < 0 || pages.isLoaded(page)) return@withLock false
            val first = page * pages.pageFrames
            val end = minOf(pages.frameCount, first + pages.pageFrames)
            val samples = readLocked(first, end, cancelled)
            if (cancelled()) throw CancellationException("PCM prefetch cancelled")
            pages.publish(page, samples)
            true
        }
        private fun readLocked(first: Int, end: Int, cancelled: () -> Boolean): FloatArray {
            check(!closed.get()) { "PCM provider is closed" }
            val resampler = checkNotNull(resampler)
            val from = resampler.inputStart(first)
            val to = resampler.inputEnd(end)
            val native = input.read(from, to - from, cancelled)
            val stereo = if (input.info.channels == 2) native else FloatArray(native.size * 2) { native[it / 2] }
            if (cancelled()) throw CancellationException("PCM prefetch cancelled")
            return resampler.render(stereo, first, end - first).also {
                if (cancelled()) throw CancellationException("PCM prefetch cancelled")
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val sources = ConcurrentHashMap<Long, Source>()
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            var worked = false
            for ((id, source) in sources) {
                val pages = source.pages.get()
                if (pages == null) {
                    if (sources.remove(id, source)) try { source.close() } catch (_: Exception) { }
                    continue
                }
                // One page per source per pass; disposal owns this same access lock.
                try {
                    if (source.publishNext { !scope.isActive || pages.status == PcmReadStatus.CLOSED }) worked = true
                } catch (cancel: CancellationException) {
                    if (!scope.isActive) throw cancel
                    pages.fail()
                    try { source.close() } catch (_: Exception) { }
                } catch (_: Exception) {
                    pages.fail()
                    try { source.close() } catch (_: Exception) { }
                }
            }
            if (!worked) delay(2) else yield()
        }
    }

    private val lifecycle = Any()
    private val offline = Mutex()
    suspend fun open(asset: Asset, input: PcmFrameSource): PcmAsset {
        var source: Source? = null
        var id = -1L
        try {
            check(scope.isActive)
            require(input.info.frames == asset.frames && input.info.channels == asset.channels && input.info.sampleRate == asset.sampleRate)
            val resampler = WindowedResampler(asset.sampleRate, asset.frames.toInt())
            currentCoroutineContext().ensureActive()
            val pages = PagedPcm(resampler.outputFrames, workerBytes = PcmResidency.workerBytes(asset))
            check(pages.capacityBytes == PcmResidency.bytes(asset))
            source = Source(WeakReference(pages), input, resampler)
            synchronized(lifecycle) {
                check(scope.isActive)
                id = ids.incrementAndGet()
                sources[id] = source
                worker.start()
            }
            val pcm = PcmAsset.paged(pages)
            prefetch(pcm, 0, minOf(pcm.frameCount, pages.pageFrames * 3))
            return pcm
        } catch (failure: Throwable) {
            val owned = source
            if (owned == null || id < 0) input.close()
            else if (sources.remove(id, owned)) withContext(NonCancellable) {
                owned.pages.get()?.close()
                owned.close(releaseStorage = true)
            }
            throw failure
        }
    }

    /** Worker disposal after the asset is unpublished or the global ledger has claimed its last lease. */
    suspend fun discard(pcm: PcmAsset) {
        val pages = pcm.pages ?: return
        pages.close()
        for ((id, source) in sources) if (source.pages.get() === pages && sources.remove(id, source)) {
            withContext(NonCancellable) { source.close(releaseStorage = true) }
            return
        }
        pages.releaseStorage()
    }

    /** Pins only the coming render block, so background hints cannot evict data during an export. */
    suspend fun <T> prepared(windows: List<com.choplab.engine.PcmWindow>, render: () -> T): T = offline.withLock {
        val leases = windows.filter { it.asset.pages != null }.groupBy { it.asset.pages!! }.mapValues { (pages, ranges) ->
            ranges.flatMap { (it.first / pages.pageFrames..(it.end - 1) / pages.pageFrames).toList() }.distinct()
                .also { require(it.size <= pages.maximumPages) { "Offline read window exceeds PCM budget" } }
        }
        leases.forEach { (pages, indices) -> indices.forEach(pages::pin) }
        try {
            windows.forEach { prefetch(it.asset, it.first, it.end) }
            render()
        } finally { leases.forEach { (pages, indices) -> indices.forEach(pages::unpin) } }
    }

    suspend fun prefetch(pcm: PcmAsset, first: Int, end: Int) {
        require(first >= 0 && end > first && end <= pcm.frameCount)
        val pages = pcm.pages ?: return
        val fromPage = first / pages.pageFrames
        val lastPage = (end - 1) / pages.pageFrames
        require(lastPage - fromPage + 1 <= pages.maximumPages) { "Read window exceeds page capacity" }
        while (true) {
            currentCoroutineContext().ensureActive()
            val status = pages.status
            if (status == PcmReadStatus.CLOSED || status == PcmReadStatus.FAILED) throw PcmPrefetchFailure(status)
            check(scope.isActive) { "PCM prefetch worker is closed" }
            var complete = true
            for (page in fromPage..lastPage) if (!pages.isLoaded(page)) {
                complete = false
                pages.request(page * pages.pageFrames)
            }
            if (complete) return
            delay(1)
        }
    }

    /** Exact worker read for peaks/offline preparation; never turns cache-miss silence into saved audio. */
    suspend fun read(pcm: PcmAsset, first: Int, end: Int): FloatArray = withContext(Dispatchers.IO) {
        require(first >= 0 && end > first && end <= pcm.frameCount && end - first <= PagedPcm.PAGE_FRAMES)
        val pages = pcm.pages
        if (pages == null) return@withContext FloatArray((end - first) * 2) { pcm.sample(first + it / 2, it % 2) }
        if (pages.status == PcmReadStatus.FAILED || pages.status == PcmReadStatus.CLOSED) throw PcmPrefetchFailure(pages.status)
        val source = sources.values.firstOrNull { it.pages.get() === pages } ?: error("PCM provider was released")
        val context = coroutineContext
        source.read(first, end) { !context.isActive || !scope.isActive }
    }

    override fun close() {
        synchronized(lifecycle) { scope.cancel() }
        runBlocking {
            worker.join()
            for ((id, source) in sources) if (sources.remove(id, source)) {
                source.pages.get()?.close()
                source.close(releaseStorage = true)
            }
        }
    }
}
