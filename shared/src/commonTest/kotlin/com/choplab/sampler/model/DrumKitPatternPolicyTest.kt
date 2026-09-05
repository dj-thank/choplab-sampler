package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DrumKitPatternPolicyTest {
    private val firstDrum = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
    private val starter = setOf(stepKey(firstDrum, 0), stepKey(firstDrum + 4, 4))

    @Test fun firstKitSeedsSelectedVariationAndKeepsOtherLanesAndSong() {
        val melody = setOf(stepKey(0, 7))
        val other = setOf(stepKey(firstDrum + 16, 9), stepKey(1, 2))
        val state = SamplerUiState(activeSteps = melody, patternArrangement = PatternArrangement(
            storedStepsBySlot = listOf(other, melody), selectedSlot = 1,
            songSections = listOf(1, 0, 1, 0), songModeEnabled = true))
        assertTrue(state.drumKitNeedsStarterPattern())
        val after = state.withInitialDrumKitPattern(starter)
        assertEquals(melody + starter, after.activeSteps)
        assertEquals(other, after.patternArrangement.storedStepsBySlot[0])
        assertEquals(state.patternArrangement.songSections, after.patternArrangement.songSections)
        assertTrue(after.patternArrangement.songModeEnabled)
    }

    @Test fun savedDrumGrooveInOtherVariationIsNotResetOrSeededOver() {
        val groove = setOf(stepKey(firstDrum, 3))
        val state = SamplerUiState(patternArrangement = PatternArrangement(storedStepsBySlot = listOf(emptySet(), groove)))
        assertFalse(state.drumKitNeedsStarterPattern())
        assertEquals(state, state.withInitialDrumKitPattern(starter))
    }

    @Test fun existingKitWithIntentionallyEmptyRhythmStaysEmpty() {
        val audio = PcmAudio(name = "test", samples = ShortArray(32), sampleRate = 8000)
        val state = SamplerUiState(pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == firstDrum) PadModel(index, audio, 0, 32) else PadModel(index)
        })
        assertFalse(state.drumKitNeedsStarterPattern())
        assertEquals(state, state.withInitialDrumKitPattern(starter))
    }

    @Test fun activeEditsOverrideStaleStoredSnapshotAndForeignStarterIsRejected() {
        val active = setOf(stepKey(firstDrum, 5))
        val state = SamplerUiState(activeSteps = active)
        assertFalse(state.drumKitNeedsStarterPattern())
        assertEquals(active, state.withInitialDrumKitPattern(starter).activeSteps)
        assertFailsWith<IllegalArgumentException> { state.withInitialDrumKitPattern(setOf(stepKey(0, 0))) }
    }
}
