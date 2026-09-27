package com.choplab.core

import com.choplab.engine.PcmAsset

/** Worker/control preparation. The returned PCM still has the asset's full absolute 48 kHz frame coordinates. */
interface PrefetchPcmPort : PcmPort {
    /** Waits for exactly this bounded read window; cancellation must not publish a control action. */
    suspend fun prefetch(pcm: PcmAsset, firstFrame: Int, endFrame: Int)
}
