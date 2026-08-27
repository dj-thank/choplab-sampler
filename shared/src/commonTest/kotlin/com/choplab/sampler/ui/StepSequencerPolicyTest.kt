package com.choplab.sampler.ui

import com.choplab.sampler.model.stepKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepSequencerPolicyTest {
    @Test
    fun selectedPadOwnsAllSixteenRowMajorKeysAndDescriptions() {
        val selectedPad = 3
        val cells = stepCellPresentations(
            selectedPad = selectedPad,
            activeSteps = setOf(
                stepKey(selectedPad, 0),
                stepKey(selectedPad, 15),
                stepKey(selectedPad + 1, 1),
            ),
            currentStep = 7,
            enabled = true,
            columns = 4,
        )

        assertEquals(16, cells.size)
        assertEquals((0 until 16).toList(), cells.map(StepCellPresentation::stepIndex))
        assertEquals((1..16).toList(), cells.map(StepCellPresentation::stepNumber))
        assertEquals((0 until 16).map { stepKey(selectedPad, it) }, cells.map(StepCellPresentation::stepKey))
        cells.forEachIndexed { index, cell ->
            assertEquals(index / 4, cell.row)
            assertEquals(index % 4, cell.column)
            assertEquals(index == 0 || index == 15, cell.active)
            assertEquals(index == 7, cell.playhead)
        }
        assertEquals("ステップ 1 オン", cells[0].contentDescription)
        assertEquals("ステップ 8 オフ。現在の再生位置", cells[7].contentDescription)
        assertEquals("ステップ 16 オン", cells[15].contentDescription)
        assertEquals(16, cells.map(StepCellPresentation::contentDescription).distinct().size)
    }

    @Test
    fun disabledCellsNeverReportAnActiveToggleButKeepTheVisualPlayheadTruth() {
        val selectedPad = 6
        val cells = stepCellPresentations(
            selectedPad = selectedPad,
            activeSteps = setOf(stepKey(selectedPad, 4)),
            currentStep = 4,
            enabled = false,
            columns = 8,
        )

        assertTrue(cells.none(StepCellPresentation::active))
        assertTrue(cells.single { it.stepIndex == 4 }.playhead)
        assertTrue(cells.all { it.contentDescription == "ステップ ${it.stepNumber} 配置できません" })
        assertFalse(cells.single { it.stepIndex == 4 }.active)
    }

    @Test
    fun invalidColumnCountIsRejectedBeforeRendering() {
        assertFailsWith<IllegalArgumentException> {
            stepCellPresentations(
                selectedPad = 0,
                activeSteps = emptySet(),
                currentStep = 0,
                enabled = true,
                columns = 3,
            )
        }
    }
}
