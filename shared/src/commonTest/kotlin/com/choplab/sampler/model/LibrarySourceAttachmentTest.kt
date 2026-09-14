package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySourceAttachmentTest {
    @Test fun shorterSourceDiscardsMarkersFromPreviousAudio() {
        val original = PcmAudio(name="old", samples=ShortArray(256), sampleRate=8000)
        val next = PcmAudio(name="new", samples=ShortArray(100), sampleRate=8000)
        val before = SamplerUiState(currentAudio=original, rangeEndFrame=256,
            sliceMarkers=listOf(0, 200), activeSliceIndex=1)
        val after = attachLibrarySource(before, next)
        assertEquals(emptyList(), after.sliceMarkers)
        assertEquals(null, after.activeSliceIndex)
        assertEquals(100, after.rangeEndFrame)
    }

    @Test fun capacityCountsSharedPadAudioOnceAndRejectsBeforeMutation() {
        val original = PcmAudio(name="old", samples=ShortArray(256), sampleRate=8000)
        val next = PcmAudio(name="new", samples=ShortArray(100), sampleRate=8000)
        val pads = List(SamplerConfig.PAD_COUNT) {
            if(it < 2) PadModel(it, original, 0, 256) else PadModel(it)
        }
        val before = SamplerUiState(currentAudio=original, pads=pads)
        assertEquals(next, attachLibrarySource(before, next, 712).currentAudio)
        kotlin.test.assertFailsWith<IllegalArgumentException> { attachLibrarySource(before, next, 711) }
        assertEquals(original, before.currentAudio)
        assertEquals(pads, before.pads)
    }

    @Test fun anotherLibrarySourceKeepsThePadsRhythmAndHistoryContent() {
        val original=PcmAudio(name="old",samples=ShortArray(256),sampleRate=8000)
        val next=PcmAudio(name="next",samples=ShortArray(512),sampleRate=8000)
        val pads=List(SamplerConfig.PAD_COUNT) { if(it==0) PadModel(0,original,0,256,playMode=PadPlayMode.LOOP) else PadModel(it) }
        val before=SamplerUiState(currentAudio=original,pads=pads,activeSteps=setOf(stepKey(0,0)),bpm=111f,loopingPadIndex=0)
        val after=attachLibrarySource(before,next)
        assertEquals(pads,after.pads);assertEquals(before.activeSteps,after.activeSteps);assertEquals(111f,after.bpm)
        assertEquals(next,after.currentAudio);assertEquals(512,after.rangeEndFrame)
        assertEquals(null,after.loopingPadIndex);assertFalse(after.isLoading)
        assertEquals(1,after.selectedPad)
    }

    @Test fun replaceSourceAudioDropsChopsMarkersAndBeats() {
        val original=PcmAudio(name="old",samples=ShortArray(256),sampleRate=8000)
        val next=PcmAudio(name="next",samples=ShortArray(512),sampleRate=8000)
        val pads=List(SamplerConfig.PAD_COUNT) { if(it<3) PadModel(it,original,it*10,it*10+50) else PadModel(it) }
        val before=SamplerUiState(currentAudio=original,rangeEndFrame=256,
            sliceMarkers=listOf(40,120),activeSliceIndex=1,pads=pads,
            activeSteps=setOf(stepKey(0,0)),loopingPadIndex=0,selectedPad=2)
        val after=replaceSourceAudio(before,next)
        assertEquals(next,after.currentAudio)
        assertEquals(0,after.rangeStartFrame);assertEquals(512,after.rangeEndFrame)
        assertEquals(emptyList(),after.sliceMarkers);assertEquals(null,after.activeSliceIndex)
        assertFalse(after.pads.any(PadModel::isAssigned))
        assertEquals(emptySet(),after.activeSteps);assertEquals(null,after.loopingPadIndex)
        assertEquals(0,after.selectedPad)
    }

    @Test fun plainSelectionFollowsEmptyPadsWhilePlayableSelectionRefuses() {
        val audio=PcmAudio(name="src",samples=ShortArray(256),sampleRate=8000)
        val pads=List(SamplerConfig.PAD_COUNT) { if(it<3) PadModel(it,audio,it*10,it*10+50) else PadModel(it) }
        val before=SamplerUiState(currentAudio=audio,pads=pads,selectedPad=0)
        // Tapping pad 4 in a rail/grid moves the selection even though it is empty,
        // so "press 4" never appears to select pad 1.
        val moved=selectPadStateForTest(before,3)
        assertEquals(3,moved.selectedPad)
        // Operation-target selection still refuses empty pads at action time.
        val refused=selectPlayablePad(before,3)
        assertEquals(0,refused.selectedPad)
        assertTrue("空です" in refused.statusMessage)
    }

    private fun selectPadStateForTest(state: SamplerUiState, index: Int): SamplerUiState {
        val safe = index.coerceIn(0, state.pads.lastIndex)
        return state.copy(selectedPad = safe, selectedBank = safe / SamplerConfig.PADS_PER_BANK)
    }
}
