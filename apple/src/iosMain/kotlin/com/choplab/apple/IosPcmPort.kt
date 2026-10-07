package com.choplab.apple

import com.choplab.core.PcmPort
import com.choplab.core.model.Asset
import com.choplab.engine.EngineFormat
import com.choplab.engine.OfflineResampler
import com.choplab.engine.PcmAsset
import com.choplab.engine.PcmLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The shared 128 MiB PCM budget was exceeded; nothing was loaded. */
internal class PcmBudgetExceeded : IllegalStateException("Audio memory limit reached")

/**
 * 48 kHz stereo float PCM for playback and export, decoded from verified assets with the engine's own resampler so
 * every host hears the same samples. Resident only: the whole decoded asset stays in memory under the shared
 * 128 MiB budget, least recently used unleased assets are released first, and a leased asset is never released.
 */
internal class IosPcmPort(private val assets: IosAssetStore, private val budgetBytes: Long = EngineFormat.MAX_RESIDENT_BYTES) : PcmPort, AutoCloseable {
    private val lock = HostLock()
    /** Least recently used first. */
    private val cache = LinkedHashMap<String, PcmAsset>()
    private val legacy = mutableListOf<PcmLease>()
    private val decoding = Mutex()
    private var closed = false

    override suspend fun load(asset: Asset): PcmAsset {
        val lease = acquire(asset)
        lock.withLock { if (closed) { lease.close(); error("PCM port is closed") }; legacy += lease }
        return lease.pcm
    }

    override suspend fun acquire(asset: Asset): PcmLease {
        cached(asset)?.let { return it }
        // One decode at a time bounds the transient peak (native samples, stereo copy, resampled result).
        return decoding.withLock {
            cached(asset) ?: run {
                val samples = withContext(Dispatchers.IO) { decode(asset) }
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    check(!closed) { "PCM port is closed" }
                    makeRoom(samples.size * 4L)
                    val pcm = PcmAsset.fromInterleaved(samples)
                    cache[asset.hash] = pcm
                    pcm.acquire()
                }
            }
        }
    }

    /** Bytes of decoded PCM held now, for the diagnostics card. */
    fun residentBytes(): Long = lock.withLock { cache.values.sumOf { it.residentBytes } }

    override fun close() {
        val leases = lock.withLock { closed = true; legacy.toList().also { legacy.clear() } }
        leases.forEach { it.close() }
        lock.withLock {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) if (iterator.next().value.evictIfUnleased()) iterator.remove()
        }
    }

    private fun cached(asset: Asset): PcmLease? = lock.withLock {
        check(!closed) { "PCM port is closed" }
        val pcm = cache.remove(asset.hash) ?: return@withLock null
        val lease = pcm.tryAcquire()
        if (lease != null) cache[asset.hash] = pcm   // most recently used
        lease
    }

    /** Called with [lock] held. */
    private fun makeRoom(bytes: Long) {
        if (bytes > budgetBytes) throw PcmBudgetExceeded()
        var held = cache.values.sumOf { it.residentBytes }
        val iterator = cache.entries.iterator()
        while (held + bytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            val size = entry.value.residentBytes
            if (entry.value.evictIfUnleased()) { iterator.remove(); held -= size }
        }
        if (held + bytes > budgetBytes) throw PcmBudgetExceeded()
    }

    private suspend fun decode(asset: Asset): FloatArray {
        val context = currentCoroutineContext()
        val path = assets.verifiedPath(asset)
        val audio = IosAudioDecoder.decode(path, asset.extension, budgetBytes) { !context.isActiveSafe() }
        require(audio.info.frames == asset.frames && audio.info.channels == asset.channels && audio.info.sampleRate == asset.sampleRate) {
            "Decoded audio differs from its asset"
        }
        context.ensureActive()
        val stereo = if (audio.info.channels == 2) audio.samples else FloatArray(audio.samples.size * 2) { audio.samples[it / 2] }
        val output = (asset.frames * EngineFormat.SAMPLE_RATE + asset.sampleRate - 1) / asset.sampleRate
        if (output * 8 > budgetBytes) throw PcmBudgetExceeded()
        return if (asset.sampleRate == EngineFormat.SAMPLE_RATE) stereo
            else OfflineResampler.resample(stereo, asset.sampleRate) { context.ensureActive() }
    }
}

private fun kotlin.coroutines.CoroutineContext.isActiveSafe(): Boolean = this[kotlinx.coroutines.Job]?.isActive != false
