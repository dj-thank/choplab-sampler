package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
