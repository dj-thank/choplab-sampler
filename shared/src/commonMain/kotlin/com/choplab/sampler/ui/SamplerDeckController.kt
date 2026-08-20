package com.choplab.sampler.ui

import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.RepeatGrid

/**
 * Deep UI seam shared by Android, Windows and iPhone.
 *
 * The deck owns presentation and exact copy. Platform shells own permissions,
 * documents and real-time audio, while each platform controller exposes the
 * same sampler commands to the deck.
 */
interface SamplerDeckController {
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
    fun triggerPad(index: Int)
    fun releasePad(index: Int)
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
    fun toggleSelectedPadPlayMode()
    fun clearSelectedPad()
    fun fillSelectedPadPattern(grid: RepeatGrid)
    fun clearSelectedPadPattern()
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
    fun addSliceMarker(frame: Int)
    fun selectSliceAt(frame: Int)
    fun setRangeStart(frame: Int)
    fun setRangeEnd(frame: Int)
    fun moveSliceMarker(markerIndex: Int, frame: Int)
}
