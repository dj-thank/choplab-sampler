package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PadTriggerOwnershipTest {
    @Test
    fun newerTriggerInvalidatesAnOlderDeferredRelease() {
        val ownership = PadTriggerOwnership(padCount = 2)
        val pointer = ownership.acquire(0)
        val keyboard = ownership.acquire(0)

        assertFalse(ownership.releaseIfCurrent(0, pointer))
        assertTrue(ownership.releaseIfCurrent(0, keyboard))
        assertFalse(ownership.releaseIfCurrent(0, keyboard))
    }

    @Test
    fun ownershipIsIndependentPerPadAndExplicitReleaseInvalidatesIt() {
        val ownership = PadTriggerOwnership(padCount = 2)
        val firstPad = ownership.acquire(0)
        val secondPad = ownership.acquire(1)

        ownership.invalidate(0)

        assertFalse(ownership.releaseIfCurrent(0, firstPad))
        assertTrue(ownership.releaseIfCurrent(1, secondPad))
        assertFalse(ownership.releaseIfCurrent(-1, PadTriggerOwnership.NONE))
    }
}
