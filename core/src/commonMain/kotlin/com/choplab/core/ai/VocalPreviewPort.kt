package com.choplab.core.ai

import com.choplab.core.model.Asset
import com.choplab.core.model.FrameRange
import kotlinx.coroutines.flow.StateFlow

enum class VocalPreviewOwner { GUIDE, PRACTICE, PITCH, COACH, CHOP, STRETCH }

enum class VocalPreviewPhase { IDLE, LOADING, PLAYING, RESTORING, FAILED, CLOSED }
data class VocalPreviewState(val phase: VocalPreviewPhase = VocalPreviewPhase.IDLE, val ownsSource: Boolean = false,
                             val assetHash: String? = null, val failure: TtsFailure? = null,
                             val originalFrame: Long = 0, val owner: VocalPreviewOwner? = null)

/** Exclusive, temporary SOURCE monitor ownership. Neither loading nor stopping edits a Project. */
interface VocalPreviewPort {
    val state: StateFlow<VocalPreviewState>
    /** Claims ownership immediately, then loads asynchronously so Stop can always interrupt decoding. */
    suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit>
    /** Other features share the same SOURCE owner. Older adapters explicitly refuse unsupported looping. */
    suspend fun start(asset: Asset, expectedRevision: Long, loop: Boolean, owner: VocalPreviewOwner): TtsResult<Unit> =
        if (!loop && owner == VocalPreviewOwner.GUIDE) start(asset, expectedRevision) else ttsFailure(TtsProblem.UNAVAILABLE)
    /** A native-frame source range, stopped by the audio engine at its exclusive end. */
    suspend fun startRange(asset: Asset, range: FrameRange, expectedRevision: Long, owner: VocalPreviewOwner): TtsResult<Unit> =
        ttsFailure(TtsProblem.UNAVAILABLE)
    suspend fun stop(owner: VocalPreviewOwner): TtsResult<Unit> =
        if (state.value.owner == owner) stop() else TtsResult.Success(Unit)
    fun requestStop(owner: VocalPreviewOwner) { if (state.value.owner == owner) requestStop() }
    /** Legacy TTS controls address GUIDE. Whole-host shutdown uses close(). */
    suspend fun stop(): TtsResult<Unit>
    /** Immediate cancellation fence; restore completes on the host scope. Safe from a dispose callback. */
    fun requestStop()
    suspend fun close() { stop() }
    fun frame(): Long
}
