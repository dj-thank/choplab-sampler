package com.choplab.desktop.provider

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderSessionGenerationTest {
    @Test
    fun newLoginInvalidatesPriorWork() {
        val generation = ProviderSessionGeneration()
        val first = generation.begin()
        assertTrue(generation.isCurrent(first))

        val second = generation.begin()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
        assertFailsWith<IllegalStateException> { generation.requireCurrent(first) }
    }

    @Test
    fun disconnectInvalidatesTheCurrentLease() {
        val generation = ProviderSessionGeneration()
        val login = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(login))
    }
}
