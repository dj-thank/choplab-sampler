package com.choplab.core

import com.choplab.core.model.Asset
import com.choplab.engine.LoopOverdub

/** Host-owned PCM reservation. Finish/discard only after the render owner returns the take. */
interface LoopOverdubCapture {
    val take: LoopOverdub
    suspend fun publish(name: String): List<LoopOverdubAsset>
    fun close()
}

/** One dry route, addressed by the immutable take routing; no new project schema. */
data class LoopOverdubAsset(val route: Int, val asset: Asset)
