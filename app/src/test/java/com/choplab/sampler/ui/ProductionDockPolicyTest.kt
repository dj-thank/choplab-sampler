package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionDockPolicyTest {
    @Test
    fun quickAndStepsKeepAddAndScratchInTheSameOrder() {
        val actions = beatProductionDockActions()
        assertEquals(
            listOf(
                ProductionDockAction.QUICK,
                ProductionDockAction.STEPS,
                ProductionDockAction.ADD,
                ProductionDockAction.SCRATCH,
            ),
            actions,
        )
        assertTrue(ProductionDockAction.ADD in actions)
        assertTrue(ProductionDockAction.SCRATCH in actions)
    }
}
