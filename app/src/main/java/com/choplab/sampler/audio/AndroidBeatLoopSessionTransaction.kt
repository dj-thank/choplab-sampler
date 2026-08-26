package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.ProductionSession
import com.choplab.sampler.model.ProductionSessionTransition
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stopAllPlaybackState
import com.choplab.sampler.model.vocalCompanionPadIndicesForLoopStart

internal fun startAndroidPadLoopSession(
    engine: SamplerPlaybackEngine,
    pads: List<PadModel>,
    loopPadIndex: Int,
    includeCompanions: Boolean = true,
): Boolean {
    val loopPad = pads.getOrNull(loopPadIndex) ?: return false
    if (!loopPad.isAssigned) return false
    val companions = if (includeCompanions) {
        pads.vocalCompanionPadIndicesForLoopStart(loopPadIndex).map(pads::get)
    } else {
        emptyList()
    }
    return engine.startPadLoopSession(loopPad, companions)
}

internal sealed interface AndroidBeatLoopSessionResult {
    data object Rejected : AndroidBeatLoopSessionResult

    data class Started(
        val transition: ProductionSessionTransition,
        val changedPads: List<PadModel>,
    ) : AndroidBeatLoopSessionResult
}

/** Commits initial Android loop truth only after the complete realtime command is admitted. */
internal class AndroidBeatLoopSessionTransaction(
    private val productionSession: ProductionSession,
    private val engine: SamplerPlaybackEngine,
) {
    fun start(state: SamplerUiState, loopPadIndex: Int): AndroidBeatLoopSessionResult {
        val currentLoopPad = requireNotNull(state.pads.getOrNull(loopPadIndex))
        require(currentLoopPad.isAssigned) { "Beat loop PAD has no audio" }

        val changedPads = mutableListOf<PadModel>()
        val pads = state.pads.map { candidate ->
            val updated = when {
                candidate.globalIndex == loopPadIndex -> candidate.copy(playMode = PadPlayMode.LOOP)
                candidate.playMode == PadPlayMode.LOOP -> candidate.copy(playMode = PadPlayMode.ONE_SHOT)
                else -> candidate
            }
            if (updated != candidate) changedPads += updated
            updated
        }
        val loopPad = pads[loopPadIndex]
        val target = stopAllPlaybackState(state).copy(
            pads = pads,
            loopingPadIndex = loopPadIndex,
            loopPlayheadFrame = if (loopPad.reverse) loopPad.endFrame - 1 else loopPad.startFrame,
            statusMessage = "${('A'.code + loopPad.bankIndex).toChar()}-%02d の音声全体をループ中"
                .format(loopPad.indexInBank + 1),
        )
        val plan = productionSession.planEdit(state, target)
        val admitted = try {
            startAndroidPadLoopSession(engine, pads, loopPadIndex)
        } catch (failure: Throwable) {
            productionSession.cancel(plan)
            throw failure
        }
        if (!admitted) {
            productionSession.cancel(plan)
            return AndroidBeatLoopSessionResult.Rejected
        }
        return AndroidBeatLoopSessionResult.Started(
            transition = productionSession.commit(plan),
            changedPads = changedPads,
        )
    }
}
