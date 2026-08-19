package com.choplab.desktop.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopPadModelTest {
    @Test
    fun newDesktopProjectHasSixteenEmptyPads() {
        val model = DesktopPadModel()

        assertEquals(16, model.padCount)
        assertEquals((0 until 16).toSet(), model.emptySlots())
    }

    @Test
    fun aLocalFileCanBeAssignedToAndRecalledFromOnePad() {
        val model = DesktopPadModel()

        model.assign(5, "C:/samples/kick.wav")

        assertEquals("C:/samples/kick.wav", model.fileFor(5))
        assertEquals(setOf(5), model.assignedSlots())
        assertNull(model.fileFor(4))
    }

    @Test
    fun assignmentRejectsSlotsOutsideTheFourByFourSurface() {
        val model = DesktopPadModel()

        assertFailsWith<IndexOutOfBoundsException> {
            model.assign(16, "C:/samples/kick.wav")
        }
    }
}
