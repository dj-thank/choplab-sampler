package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRuntimeStateTest {
    @Test
    fun stopBoundaryClearsRunningAndPublishedStepTogether() {
        val state = TransportRuntimeState()

        state.start()
        state.publishStep(7)
        assertTrue(state.running)
        assertEquals(7, state.currentStep)

        state.stop()

        assertFalse(state.running)
        assertEquals(-1, state.currentStep)
    }
}
