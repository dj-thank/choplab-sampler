package com.choplab.sampler.audio

import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternSequenceCursorTest {
    @Test
    fun compilerKeepsABPadEventsInTheirOwnBars() {
        val compiled = compilePatternSequence(
            listOf(
                setOf(stepKey(2, 0), stepKey(3, 8)),
                setOf(stepKey(5, 0), stepKey(7, 12)),
            ),
        )

        assertEquals(2, compiled.size)
        assertArrayEquals(intArrayOf(2), compiled[0][0])
        assertArrayEquals(intArrayOf(3), compiled[0][8])
        assertArrayEquals(intArrayOf(5), compiled[1][0])
        assertArrayEquals(intArrayOf(7), compiled[1][12])
    }

    @Test
    fun allocationFreeCursorAdvancesSectionOnlyAfterStepFifteenAndRestartsAtA() {
        val cursor = PatternSequenceCursor()
        val observed = mutableListOf<Pair<Int, Int>>()

        repeat(SamplerConfig.STEP_COUNT * 2 + 1) {
            observed += cursor.sectionIndex to cursor.stepIndex
            cursor.advance(sectionCount = 2)
        }

        assertEquals(0 to 0, observed.first())
        assertEquals(0 to 15, observed[15])
        assertEquals(1 to 0, observed[16])
        assertEquals(1 to 15, observed[31])
        assertEquals(0 to 0, observed[32])

        cursor.reset()
        assertEquals(0, cursor.sectionIndex)
        assertEquals(0, cursor.stepIndex)
    }
}
