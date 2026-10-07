package com.choplab.desktop.next

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.*

class NextDialogGeometryTest {
    @Test fun initialBoundsAndMinimumStayWithinEveryUsableScreen() {
        for (work in listOf(Rectangle(0, 30, 1440, 900), Rectangle(0, 25, 800, 475),
            Rectangle(-1280, 60, 1280, 620), Rectangle(1920, 40, 640, 320))) {
            for (center in listOf(Point(0, 0), Point(5000, 5000), Point(work.x + 10, work.y + 10))) {
                val layout = nextDialogGeometry(work, Dimension(960, 780), Dimension(760, 640), center)
                assertTrue(work.contains(layout.bounds))
                assertTrue(layout.minimum.width <= layout.bounds.width && layout.minimum.height <= layout.bounds.height)
            }
        }
    }
}
