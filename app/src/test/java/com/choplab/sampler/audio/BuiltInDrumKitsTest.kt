package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.SamplerConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

        assertEquals(SamplerConfig.PADS_PER_BANK, first.size)
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
}
