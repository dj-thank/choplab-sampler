package com.choplab.desktop.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun nonFiniteTempoUsesSharedDefaultsInsteadOfCollapsingToOneNanosecond() {
        val expectedLong = DesktopTransportTiming.stepDurationNanos(0, 92f, 50f)
        val expectedShort = DesktopTransportTiming.stepDurationNanos(1, 92f, 50f)

        assertEquals(
            expectedLong,
            DesktopTransportTiming.stepDurationNanos(0, Float.NaN, Float.POSITIVE_INFINITY),
        )
        assertEquals(
            expectedShort,
            DesktopTransportTiming.stepDurationNanos(1, Float.NaN, Float.NEGATIVE_INFINITY),
        )
        assertTrue(expectedLong > 100_000_000L)
        assertEquals(expectedLong, expectedShort)
    }

    @Test
    fun startBarrierPublishesStateBeforeStepZero() {
        val statePublished = AtomicBoolean(false)
        val stepZeroSawPublishedState = AtomicBoolean(false)
        val stepZeroCalls = AtomicInteger(0)
        val firstStep = CountDownLatch(1)
        val transport = DesktopTransport { step ->
            if (step == 0) {
                stepZeroSawPublishedState.set(statePublished.get())
                stepZeroCalls.incrementAndGet()
                firstStep.countDown()
            }
        }

        try {
            transport.start(bpm = 240f, swing = 50f) {
                statePublished.set(true)
            }

            assertTrue(firstStep.await(1L, TimeUnit.SECONDS))
            transport.stop()
            assertTrue(stepZeroSawPublishedState.get())
            assertEquals(1, stepZeroCalls.get())
        } finally {
            transport.close()
        }
    }
}
