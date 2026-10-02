package com.choplab.core

import com.choplab.core.chop.*
import com.choplab.core.model.FrameRange
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.test.*

class LiveChopTimingTest {
    private val route = LiveChopRoute(Any(), Any(), 0, 48_000, 2, true, 1024, 256)
    private val output = LiveChopOutput(route, 123_456_789L, 96_000, 48_000, true, 17_000_000)

    @Test fun convertsReportedDelayToNativeFramesAtTheSourcePitchAndKeepsTheReceipt() {
        for (rate in listOf(44_100, 48_000, 96_000)) for (pitch in listOf(-12.0, 0.0, 12.0)) {
            val cut = assertNotNull(liveChopCut(output, LiveChopCorrection(), rate, pitch, FrameRange(0, rate * 2L)))
            assertEquals(rate - (.017 * rate * 2.0.pow(pitch / 12)).roundToLong(), cut.requestedSourceFrame)
            assertEquals(cut.requestedSourceFrame, cut.appliedSourceFrame)
            assertEquals(123_456_789L, cut.eventNanos)
            assertEquals(96_000, cut.engineFrame)
            assertEquals(LiveChopTimingMode.ESTIMATED, cut.mode)
        }
    }

    @Test fun unknownTimingNeedsExplicitManualDelayAndRangeClampingIsVisible() {
        val unknown = output.copy(estimatedDelayNanos = null)
        assertNull(liveChopCut(unknown, LiveChopCorrection(), 48_000, 0.0, FrameRange(47_000, 60_000)))
        val cut = assertNotNull(liveChopCut(unknown, LiveChopCorrection(LiveChopTimingMode.MANUAL, 50), 48_000, 0.0, FrameRange(47_000, 60_000)))
        assertEquals(45_600, cut.requestedSourceFrame)
        assertEquals(47_000, cut.appliedSourceFrame)
        assertEquals(LiveChopTimingMode.MANUAL, cut.mode)
        assertNull(liveChopCut(unknown, LiveChopCorrection(LiveChopTimingMode.MANUAL, 0), 48_000, 0.0, FrameRange(0, 48_000)))
        assertNull(liveChopCut(output.copy(sourcePlaying = false), LiveChopCorrection(), 48_000, 0.0, FrameRange(0, 96_000)))
        assertNull(liveChopCut(output.copy(estimatedDelayNanos = -1), LiveChopCorrection(), 48_000, 0.0, FrameRange(0, 96_000)))
        assertFailsWith<IllegalArgumentException> { LiveChopCorrection(LiveChopTimingMode.MANUAL, 1_001) }
    }
}
