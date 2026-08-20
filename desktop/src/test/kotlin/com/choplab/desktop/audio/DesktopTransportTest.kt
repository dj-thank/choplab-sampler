package com.choplab.desktop.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTransportTest {
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
