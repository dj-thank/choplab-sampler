package com.choplab.desktop.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTransportTest {
    @Test
    fun cursorAdvancesBarOnlyAfterStepFifteenAndRestartsCleanly() {
        val cursor = DesktopTransportCursor()
        val observed = mutableListOf<Pair<Int, Int>>()

        repeat(33) {
            observed += cursor.barIndex to cursor.stepIndex
            cursor.advance()
        }

        assertEquals(0 to 0, observed[0])
        assertEquals(0 to 15, observed[15])
        assertEquals(1 to 0, observed[16])
        assertEquals(1 to 15, observed[31])
        assertEquals(2 to 0, observed[32])

        cursor.reset()
        assertEquals(0, cursor.barIndex)
        assertEquals(0, cursor.stepIndex)
    }

    @Test
    fun straightPairKeepsOneEighthNoteDuration() {
        val first = DesktopTransportTiming.stepDurationNanos(0, 120f, 50f)
        val second = DesktopTransportTiming.stepDurationNanos(1, 120f, 50f)

        assertEquals(first, second)
        assertEquals(250_000_000L, first + second)
    }

    @Test
    fun swingMakesEvenStepLongerWithoutChangingPairDuration() {
        val straightPair = DesktopTransportTiming.stepDurationNanos(0, 100f, 50f) +
            DesktopTransportTiming.stepDurationNanos(1, 100f, 50f)
        val long = DesktopTransportTiming.stepDurationNanos(0, 100f, 66f)
        val short = DesktopTransportTiming.stepDurationNanos(1, 100f, 66f)

        assertTrue(long > short)
        assertEquals(straightPair, long + short)
    }
}
