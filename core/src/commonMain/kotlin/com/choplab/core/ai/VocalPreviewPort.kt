package com.choplab.core.ai

import com.choplab.core.model.Asset
import kotlinx.coroutines.flow.StateFlow

enum class VocalPreviewPhase { IDLE, LOADING, PLAYING, RESTORING, FAILED, CLOSED }
data class VocalPreviewState(val phase: VocalPreviewPhase = VocalPreviewPhase.IDLE, val ownsSource: Boolean = false,
                             val assetHash: String? = null, val failure: TtsFailure? = null,
                             val originalFrame: Long = 0)

/** Exclusive, temporary SOURCE monitor ownership. Neither loading nor stopping edits a Project. */
interface VocalPreviewPort {
    val state: StateFlow<VocalPreviewState>
    /** Claims ownership immediately, then loads asynchronously so Stop can always interrupt decoding. */
    suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit>
    suspend fun stop(): TtsResult<Unit>
    /** Immediate cancellation fence; restore completes on the host scope. Safe from a dispose callback. */
    fun requestStop()
    suspend fun close() { stop() }
    fun frame(): Long
}
