package com.choplab.sampler.midi

import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.ui.PadTriggerOwnership
import com.choplab.sampler.ui.SamplerDeckController

/** Reuses playback admission, PAD ownership, undo and persistence instead of bypassing the engine. */
class Ddj200DeckTarget(
    private val controller: SamplerDeckController,
    private val state: () -> SamplerUiState,
) : Ddj200Target {
    override fun bank() = state().selectedBank
    override fun selectBank(bank: Int) { controller.selectBank(bank) }
    override fun triggerPad(index: Int): Long {
        if (state().isLoading) return PadTriggerOwnership.NONE
        controller.selectPad(index)
        return controller.triggerPadWithOwnership(index)
    }
    override fun releasePad(index: Int, ownership: Long) = controller.releasePadIfOwned(index, ownership)
    override fun toggleSource() = controller.toggleSourcePlayback()
    override fun toggleBeat() = controller.toggleTransport()
    override fun stopAll() = controller.stopAllSounds()
    override fun toggleRecordArm() = controller.toggleRecordArm()
    override fun undo() = controller.undoEdit()
    override fun redo() = controller.redoEdit()
    override fun beginScratch(deck: Int): Boolean {
        val current = state()
        if (current.isLoading || current.sourceScratchActive || current.scratchingPadIndex != null) return false
        if (deck == 0) controller.beginSourceScratch() else controller.beginScratch()
        return state().sourceScratchActive || state().scratchingPadIndex != null
    }
    override fun scratch(speed: Float) = controller.updateScratchSpeed(speed)
    override fun endScratch() = controller.endScratch()
    override fun parameter(kind: DdjParameter, pad: Int): Float = when (kind) {
        DdjParameter.SOURCE_PITCH -> (state().masterPitchSemitones + 12f) / 24f
        DdjParameter.BPM -> (state().bpm - 40f) / 200f
        DdjParameter.PAD_GAIN -> (state().pads.getOrNull(pad)?.gain ?: 0f) / 1.5f
        DdjParameter.PAD_TONE -> state().pads.getOrNull(pad)?.tone ?: 1f
    }
    override fun setParameter(kind: DdjParameter, pad: Int, normalized: Float) {
        if (state().isLoading) return
        when (kind) {
            DdjParameter.SOURCE_PITCH -> controller.setMasterPitch(normalized * 24f - 12f)
            DdjParameter.BPM -> controller.setBpm(40f + normalized * 200f)
            DdjParameter.PAD_GAIN, DdjParameter.PAD_TONE -> {
                if (state().pads.getOrNull(pad)?.isAssigned != true) return
                controller.selectPad(pad)
                if (kind == DdjParameter.PAD_GAIN) controller.setSelectedPadGain(normalized * 1.5f)
                else controller.setSelectedPadTone(normalized)
            }
        }
    }
}
