package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionDockPolicyTest {
    @Test
    fun quickAndStepsKeepAddAndScratchInTheSameOrder() {
        listOf(false, true).forEach { stepsVisible ->
            val actions = beatProductionDockActions(stepsVisible)
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
}
