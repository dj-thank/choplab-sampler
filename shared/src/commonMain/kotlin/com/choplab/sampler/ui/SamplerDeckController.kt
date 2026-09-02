package com.choplab.sampler.ui

import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.ProductionCommand
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerConfig

/**
 * Runtime-only PAD trigger ownership shared by touch, keyboard and other controller entry points.
 * A delayed release may close a GATE voice only while its token is still the newest trigger for
 * that PAD. Controllers remain responsible for serializing calls on their UI/control thread.
 */
class PadTriggerOwnership(
    private val padCount: Int = SamplerConfig.PAD_COUNT,
) {
    private val generations = LongArray(padCount)

    fun acquire(padIndex: Int): Long {
        if (padIndex !in 0 until padCount) return NONE
        val next = generations[padIndex] + 1L
        generations[padIndex] = next
        return next
    }

    fun releaseIfCurrent(padIndex: Int, ownership: Long): Boolean {
        if (padIndex !in 0 until padCount || ownership == NONE) return false
        if (generations[padIndex] != ownership) return false
        generations[padIndex] = ownership + 1L
        return true
    }

    fun invalidate(padIndex: Int) {
        acquire(padIndex)
    }

    companion object {
        const val NONE: Long = Long.MIN_VALUE
    }
}

/**
 * Deep UI seam shared by Android, Windows and iPhone.
 *
 * The deck owns presentation and exact copy. Platform shells own permissions,
 * documents and real-time audio, while each platform controller exposes the
 * same sampler commands to the deck. The method-per-action surface is being
 * migrated incrementally through [dispatch] so editing semantics do not fork.
 */
interface SamplerDeckController {
    fun dispatch(command: ProductionCommand)
    fun stopAllSounds()
    fun stopActiveRecording()
    fun resetProject()
    fun stopSourceForWorkspaceChange()
    fun ensurePlayablePadSelected()
    fun prepareDefaultChopDestination()
    fun restartSourcePlayback()
    fun selectPad(index: Int)
    fun selectBank(index: Int)
    fun selectPadPage(index: Int)
    fun selectPlayableBank(index: Int)
    fun selectPlayablePadPage(index: Int)
    fun selectPlayablePad(index: Int)
    fun capturePad(index: Int)
    fun capturePadWithOwnership(index: Int): Long {
        capturePad(index)
        return PadTriggerOwnership.NONE
    }
    fun triggerPad(index: Int)
    fun releasePad(index: Int)
    fun triggerPadWithOwnership(index: Int): Long
    fun releasePadIfOwned(index: Int, ownership: Long)
    fun playSourceFrom(frame: Int)
    fun seekSourcePlayback(frame: Int)
    fun toggleSourcePlayback()
    fun toggleChopPlayback()
    fun setMasterPitch(value: Float)
    fun setSelectedPadPitch(value: Float)
    fun setSelectedPadTone(value: Float)
    fun setSelectedPadGain(value: Float)
    fun setSelectedPadStartFrame(frame: Int)
    fun setSelectedPadEndFrame(frame: Int)
    fun previewPad(index: Int)
    fun restoreSelectedPadTrim(snapshot: PadTrimSnapshot)
    fun setSelectedPadChokeGroup(group: Int)
    fun toggleSelectedPadReverse()
    fun toggleSelectedPadPlayMode() = dispatch(ProductionCommand.ToggleSelectedPadPerformanceMode)
    fun clearSelectedPad()
    fun fillSelectedPadPattern(grid: RepeatGrid)
    fun clearSelectedPadPattern()
    fun createQuickSketch() = dispatch(ProductionCommand.CreateQuickSketch)
    fun selectPatternVariation(slot: Int) = dispatch(ProductionCommand.SelectPatternVariation(slot))
    fun duplicateSelectedPatternToOther() = dispatch(ProductionCommand.DuplicateSelectedPatternToOther)
    fun toggleSongSectionPattern(sectionIndex: Int) =
        dispatch(ProductionCommand.ToggleSongSectionPattern(sectionIndex))
    fun toggleSongMode() = dispatch(ProductionCommand.ToggleSongMode)
    fun toggleStep(step: Int)
    fun clearAllPattern()
    fun toggleBeatLoopControl()
    fun toggleTransport()
    fun toggleRecordArm()
    fun undoEdit()
    fun redoEdit()
    fun endScratch()
    fun beginScratch()
    fun beginSourceScratch()
    fun updateScratchSpeed(speed: Float)
    fun applyBuiltInDrumKit(kitId: String, replaceExisting: Boolean)
    fun setBpm(value: Float)
    fun setSwing(value: Float)
    fun addSliceMarker(frame: Int) = dispatch(ProductionCommand.AddSliceMarker(frame))
    fun selectSliceAt(frame: Int) = dispatch(ProductionCommand.SelectSliceAt(frame))
    fun setRangeStart(frame: Int) = dispatch(ProductionCommand.SetSourceRangeStart(frame))
    fun setRangeEnd(frame: Int) = dispatch(ProductionCommand.SetSourceRangeEnd(frame))
    fun moveSliceMarker(markerIndex: Int, frame: Int) =
        dispatch(ProductionCommand.MoveSliceMarker(markerIndex, frame))
}
