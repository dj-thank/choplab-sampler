package com.choplab.jvm

import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.engine.PcmAsset
import com.choplab.engine.PcmLease
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable

data class PcmCacheStats(val retainedBytes: Long, val entries: Int, val pendingLoads: Int)

/**
 * Worker-only bounded LRU. Programs retain the same immutable PcmAsset objects, without a second
 * sample copy. One decode runs at a time; same-identity waiters share one result. The last cancelled
 * waiter cancels its load. Render never reads this map or acquires either synchronization primitive.
 */
class PcmAssetCache(
    val maxBytes: Long = EngineFormat.MAX_RESIDENT_BYTES,
    private val maximumPending: Int = ProjectLimits.MAX_ASSETS,
) : Closeable {
    private data class Identity(
        val hash: String, val extension: String, val bytes: Long, val rate: Int,
        val channels: Int, val frames: Long, val needsFloat: Boolean,
    )
    private class Flight {
        val result = CompletableDeferred<PcmLease>()
        lateinit var job: Job
        var waiters = 0
    }
    private val guard = Mutex()
    private val decodeSlot = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<Identity, PcmAsset>()
    private val flights = mutableMapOf<Identity, Flight>()
    private var retained = 0L
    private val legacy = mutableListOf<PcmLease>()
    init { require(maxBytes in 8..EngineFormat.MAX_RESIDENT_BYTES && maximumPending in 1..ProjectLimits.MAX_ASSETS) }

    /** Legacy callers own their returned PCM until cache.close. New worker paths use acquire/use. */
    suspend fun get(asset: Asset, discard: suspend (PcmAsset) -> Unit = {}, decode: suspend () -> PcmAsset): PcmAsset {
        val lease = acquire(asset, discard) { decode().acquire() }
        try { guard.withLock { check(scope.isActive); legacy.add(lease) } }
        catch (failure: Throwable) { lease.close(); throw failure }
        return lease.pcm
    }

    suspend fun acquire(asset: Asset, discard: suspend (PcmAsset) -> Unit = {}, decode: suspend () -> PcmLease): PcmLease {
        val identity = Identity(asset.hash, asset.extension, asset.byteCount, asset.sampleRate, asset.channels, asset.frames, asset.role != AssetRole.ORIGINAL)
        val frames = (asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate
        require(frames in 1..Int.MAX_VALUE.toLong())
        var hit: PcmLease? = null
        val flight = guard.withLock {
            check(scope.isActive) { "PCM cache is closed" }
            entries.remove(identity)?.let {
                if (it.pages?.status !in listOf(com.choplab.engine.PcmReadStatus.FAILED, com.choplab.engine.PcmReadStatus.CLOSED)) hit = it.tryAcquire()
                if (hit == null) retained -= it.residentBytes else entries[identity] = it
            }
            if (hit != null) return@withLock null
            (flights[identity]?.takeIf { !it.result.isCancelled && it.job.isActive } ?: run {
                flights.entries.removeAll { it.value.result.isCompleted || !it.value.job.isActive }
                require(flights.size < maximumPending) { "Too many pending PCM loads" }
                Flight().also { created ->
                    flights[identity] = created
                    created.job = scope.launch(start = CoroutineStart.LAZY) {
                        var unpublished: PcmLease? = null
                        try {
                            val lease = decodeSlot.withPermit {
                                coroutineContext.ensureActive()
                                decode().also {
                                    unpublished = it
                                    coroutineContext.ensureActive()
                                    val pcm = it.pcm
                                    require(pcm.frameCount.toLong() == frames && pcm.residentBytes <= EngineFormat.MAX_RESIDENT_BYTES &&
                                        (pcm.pages != null || pcm.residentBytes == frames * 8)) { "PCM metadata mismatch" }
                                }
                            }
                            guard.withLock {
                                coroutineContext.ensureActive()
                                val pcm = lease.pcm
                                if (pcm.residentBytes <= maxBytes) {
                                    while (retained + pcm.residentBytes > maxBytes) {
                                        val oldest = entries.keys.first()
                                        retained -= requireNotNull(entries.remove(oldest)).residentBytes
                                    }
                                    entries[identity] = pcm; retained += pcm.residentBytes
                                }
                                created.result.complete(lease)
                                // The publication lease bridges completion -> every waiter's own lease.
                                if (created.waiters == 0) lease.close()
                                unpublished = null
                            }
                        } catch (failure: Throwable) {
                            try {
                                withContext(NonCancellable) {
                                    unpublished?.let { lease -> lease.close(); discard(lease.pcm) }
                                }
                            } catch (cleanup: Throwable) {
                                if (cleanup !== failure) failure.addSuppressed(cleanup)
                            }
                            created.result.completeExceptionally(failure)
                            if (failure is Error) throw failure
                        } finally {
                            withContext(NonCancellable) { guard.withLock { if (flights[identity] === created) flights.remove(identity) } }
                        }
                    }
                }
            }).also { it.waiters++; it.job.start() }
        }
        hit?.let { return it }
        val waiting = requireNotNull(flight)
        try { return waiting.result.await().pcm.acquire() }
        finally {
            withContext(NonCancellable) {
                guard.withLock {
                    waiting.waiters--
                    if (waiting.waiters == 0) {
                        if (!waiting.result.isCompleted) waiting.job.cancel()
                        else if (!waiting.result.isCancelled) waiting.result.await().close()
                    }
                }
            }
        }
    }

    suspend fun statistics(): PcmCacheStats = guard.withLock { PcmCacheStats(retained, entries.size, flights.size) }
    suspend fun clear() = guard.withLock { entries.clear(); retained = 0L }
    override fun close() {
        scope.cancel()
        runBlocking {
            scope.coroutineContext[Job]!!.join()
            guard.withLock { legacy.forEach { it.close() }; legacy.clear(); entries.clear(); retained = 0L }
        }
    }
}
