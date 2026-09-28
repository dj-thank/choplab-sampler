package com.choplab.core

import com.choplab.engine.PcmAsset
import com.choplab.engine.PagedPcm
import com.choplab.core.model.Asset

/** Worker/control preparation. The returned PCM still has the asset's full absolute 48 kHz frame coordinates. */
interface PrefetchPcmPort : PcmPort {
    fun residentBytes(asset: Asset): Long
    /** Waits for exactly this bounded read window; cancellation must not publish a control action. */
    suspend fun prefetch(pcm: PcmAsset, firstFrame: Int, endFrame: Int)
    suspend fun <T> prepared(windows: List<com.choplab.engine.PcmWindow>, render: () -> T): T
}

/** One budget calculation shared by admission and the worker's actual cache construction. */
object PcmResidency {
    const val SMALL_ASSET_BYTES = 16L * 1024 * 1024
    fun frames(asset: Asset): Long = (asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate
    fun resident(asset: Asset): Boolean = frames(asset) * 8 <= SMALL_ASSET_BYTES &&
        asset.frames * asset.channels * 4 <= SMALL_ASSET_BYTES
    fun workerBytes(asset: Asset): Long {
        val nativeWindow = (PagedPcm.PAGE_FRAMES.toLong() * asset.sampleRate + 47_999) / 48_000 + 512
        val kernel = if (asset.sampleRate == 48_000) 0 else 512L * 4097 * 4
        return kernel + nativeWindow * 8 * 3 + 64 * 1024 + PagedPcm.PAGE_FRAMES * 8L
    }
    fun bytes(asset: Asset): Long = if (resident(asset)) frames(asset) * 8 else
        PagedPcm.capacityFor(frames(asset).toInt(), workerBytes = workerBytes(asset))
}
