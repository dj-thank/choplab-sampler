package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PadGridGeometryTest {
    @Test
    fun fourByFourPadsStaySquareInsideAWideViewport() {
        val geometry = resolveSquarePadGrid(
            width = 400f,
            height = 240f,
            gap = 8f,
            columns = 4,
        )

        assertEquals(54f, geometry.cellSize, 0.001f)
        assertEquals(240f, geometry.contentHeight, 0.001f)
        assertEquals(240f, geometry.contentWidth, 0.001f)
    }

    @Test
    fun eightByTwoPadsUseTheLargestSquareThatFits() {
        val geometry = resolveSquarePadGrid(
            width = 800f,
            height = 200f,
            gap = 4f,
            columns = 8,
        )

        assertEquals(96.5f, geometry.cellSize, 0.001f)
        assertEquals(800f, geometry.contentWidth, 0.001f)
        assertEquals(197f, geometry.contentHeight, 0.001f)
    }
}
