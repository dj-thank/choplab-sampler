package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PatternArrangement
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stepKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInDrumKitsTest {
    @Test
    fun catalogOffersFiveOriginalKitsForBeginnersAndProSelection() {
        assertEquals(5, BuiltInDrumKits.catalog.size)
        assertEquals(5, BuiltInDrumKits.catalog.map { it.id }.distinct().size)
    }

    @Test
    fun renderingAKitCreatesDeterministicAssignedDrumPads() {
        val kit = BuiltInDrumKits.catalog.first()
        val first = BuiltInDrumKits.createBankPads(kit.id, bankIndex = 1)
        val second = BuiltInDrumKits.createBankPads(kit.id, bankIndex = 1)

        assertEquals(SamplerConfig.DRUM_KIT_PAD_COUNT, first.size)
        assertTrue(first.all { it.isAssigned && it.contentKind == PadContentKind.DRUM })
        assertEquals(16, first.mapNotNull { it.audio?.id }.distinct().size)
        assertArrayEquals(first[0].audio!!.samples, second[0].audio!!.samples)
        assertTrue(first.all { it.audio!!.samples.any { sample -> sample.toInt() != 0 } })
    }

    @Test
    fun starterPatternUsesOnlyPadsFromTheTargetBank() {
        val steps = BuiltInDrumKits.starterPattern(
            kitId = BuiltInDrumKits.catalog.first().id,
            bankIndex = 2,
        )

        val firstPad = 2 * SamplerConfig.PADS_PER_BANK
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it / SamplerConfig.STEP_COUNT in firstPad until firstPad + 16 })
    }

    @Test
    fun starterInstallationMakesANewProductionPlayableWithoutMovingTheUserFromBankA() {
        val installed = BuiltInDrumKits.installStarterKit(SamplerUiState())
        val drumStart = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK

        assertEquals(BuiltInDrumKits.DEFAULT_STARTER_KIT_ID, installed.selectedDrumKitId)
        assertEquals(0, installed.selectedBank)
        assertEquals(0, installed.selectedPad)
        assertTrue(
            installed.pads.subList(drumStart, drumStart + SamplerConfig.DRUM_KIT_PAD_COUNT)
                .all { it.isAssigned && it.contentKind == PadContentKind.DRUM },
        )
        assertTrue(installed.activeSteps.isNotEmpty())
        assertTrue(BuiltInDrumKits.isPristineStarterProduction(installed))
        assertTrue(
            BuiltInDrumKits.hasUntouchedStarterDrums(
                installed.copy(currentAudio = PcmAudio(12L, "source.wav", ShortArray(10), 1_000)),
            ),
        )
        assertTrue(
            BuiltInDrumKits.isPristineStarterProduction(
                installed.copy(selectedBank = SamplerConfig.DRUM_BANK_INDEX, selectedPad = drumStart),
            ),
        )
        assertFalse(
            BuiltInDrumKits.isPristineStarterProduction(
                installed.copy(
                    pads = installed.pads.toMutableList().also {
                        it[drumStart] = it[drumStart].copy(playMode = PadPlayMode.LOOP)
                    },
                ),
            ),
        )
    }

    @Test
    fun starterInstallationNeverChangesExistingProjectContent() {
        val audio = PcmAudio(77L, "user.wav", ShortArray(100), 1_000)
        val existing = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            pads = List(SamplerConfig.PAD_COUNT) { index ->
                if (index == 0) PadModel(index, audio, 0, 50) else PadModel(index)
            },
        )

        assertSame(existing, BuiltInDrumKits.installStarterKit(existing))
        assertFalse(BuiltInDrumKits.isPristineStarterProduction(existing))
    }

    @Test
    fun editedBVariationOrSongOrderIsNeverClassifiedAsPristineStarterWork() {
        val installed = BuiltInDrumKits.installStarterKit(SamplerUiState())
        val withPatternB = installed.copy(
            patternArrangement = PatternArrangement(
                storedStepsBySlot = listOf(emptySet(), setOf(stepKey(0, 0))),
            ),
        )
        val withSongMode = installed.copy(
            patternArrangement = PatternArrangement(songModeEnabled = true),
        )

        assertFalse(BuiltInDrumKits.hasUntouchedStarterDrums(withPatternB))
        assertFalse(BuiltInDrumKits.isPristineStarterProduction(withPatternB))
        assertFalse(BuiltInDrumKits.hasUntouchedStarterDrums(withSongMode))
        assertFalse(BuiltInDrumKits.isPristineStarterProduction(withSongMode))
    }
}
