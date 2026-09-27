package com.choplab.jvm

import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.engine.PcmAsset
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
        val result = CompletableDeferred<PcmAsset>()
        lateinit var job: Job
        var waiters = 0
    }
    private val guard = Mutex()
    private val decodeSlot = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<Identity, PcmAsset>()
    private val flights = mutableMapOf<Identity, Flight>()
    private var retained = 0L
    init { require(maxBytes in 8..EngineFormat.MAX_RESIDENT_BYTES && maximumPending in 1..ProjectLimits.MAX_ASSETS) }

    suspend fun get(asset: Asset, decode: suspend () -> PcmAsset): PcmAsset {
        val identity = Identity(asset.hash, asset.extension, asset.byteCount, asset.sampleRate, asset.channels, asset.frames, asset.role != AssetRole.ORIGINAL)
        val frames = (asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate
        require(frames * 8 <= EngineFormat.MAX_RESIDENT_BYTES)
        var hit: PcmAsset? = null
        val flight = guard.withLock {
            check(scope.isActive) { "PCM cache is closed" }
            entries.remove(identity)?.let { entries[identity] = it; hit = it }
            if (hit != null) return@withLock null
            (flights[identity]?.takeIf { !it.result.isCancelled && it.job.isActive } ?: run {
                flights.entries.removeAll { it.value.result.isCompleted || !it.value.job.isActive }
                require(flights.size < maximumPending) { "Too many pending PCM loads" }
                Flight().also { created ->
                    flights[identity] = created
                    created.job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val pcm = decodeSlot.withPermit {
                                coroutineContext.ensureActive()
                                decode().also {
                                    coroutineContext.ensureActive()
                                    require(it.frameCount.toLong() == frames && it.residentBytes == frames * 8) { "PCM metadata mismatch" }
                                }
                            }
                            guard.withLock {
                                coroutineContext.ensureActive()
                                if (pcm.residentBytes <= maxBytes) {
                                    while (retained + pcm.residentBytes > maxBytes) {
                                        val oldest = entries.keys.first()
                                        retained -= requireNotNull(entries.remove(oldest)).residentBytes
                                    }
                                    entries[identity] = pcm; retained += pcm.residentBytes
                                }
                                created.result.complete(pcm)
                            }
                        } catch (failure: Throwable) {
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
        try { return waiting.result.await() }
        finally {
            withContext(NonCancellable) {
                guard.withLock {
                    waiting.waiters--
                    if (waiting.waiters == 0 && !waiting.result.isCompleted) waiting.job.cancel()
                }
            }
        }
    }

    suspend fun statistics(): PcmCacheStats = guard.withLock { PcmCacheStats(retained, entries.size, flights.size) }
    suspend fun clear() = guard.withLock { entries.clear(); retained = 0L }
    override fun close() { scope.cancel() }
}
