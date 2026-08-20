package com.choplab.sampler.ui

import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PadTrimPrecision
import com.choplab.sampler.model.SliceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecisionTrimControlsTest {
    @Test
    fun numericWheelFormatsExactMinuteSecondAndMillisecondValues() {
        assertEquals("0:00.000", formatPrecisionTrimTime(0, 1_000))
        assertEquals("1:01.234", formatPrecisionTrimTime(61_234, 1_000))
        assertEquals("0:01.000", formatPrecisionTrimTime(48_000, 48_000))
    }

    @Test
    fun dialProgressUsesTheFocusedOneSecondWindow() {
        val window = SliceRange(4_000, 5_000)

        assertEquals(0f, trimDialProgress(3_000, window), 0f)
        assertEquals(0.5f, trimDialProgress(4_500, window), 0f)
        assertEquals(1f, trimDialProgress(6_000, window), 0f)
    }

    @Test
    fun boundaryAndPrecisionCopyIsStable() {
        assertEquals("ここから / START", trimBoundaryLabel(PadTrimBoundary.START))
        assertEquals("ここまで / END", trimBoundaryLabel(PadTrimBoundary.END))
        assertEquals("1 FRAME", trimPrecisionLabel(PadTrimPrecision.FRAME))
        assertEquals("1 ms", trimPrecisionLabel(PadTrimPrecision.MILLISECOND))
        assertEquals("10 ms", trimPrecisionLabel(PadTrimPrecision.TEN_MILLISECONDS))
    }
}
