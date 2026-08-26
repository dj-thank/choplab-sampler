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
        assertEquals(
            "0:00.000020",
            formatPrecisionTrimValue(1, 48_000, PadTrimPrecision.FRAME),
        )
        assertEquals(
            "0:01.000",
            formatPrecisionTrimValue(48_000, 48_000, PadTrimPrecision.MILLISECOND),
        )
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

    @Test
    fun overviewDescriptionBindsThePadAndVisibleRangesToExactTimes() {
        assertEquals(
            "全体波形。PAD範囲 0:02.000 から 0:08.000。編集表示 0:01.500 から 0:09.000",
            precisionTrimOverviewDescription(
                padRange = SliceRange(2_000, 8_000),
                viewport = WaveformViewport(
                    totalFrames = 10_000,
                    zoom = 4f / 3f,
                    scroll = 0.6f,
                    visibleStart = 1_500,
                    visibleFrames = 7_500,
                ),
                sampleRate = 1_000,
            ),
        )
    }

    @Test
    fun overviewProtectsTheEditableWaveformOnCompactLandscapeHeights() {
        assertEquals(false, precisionTrimOverviewVisible(499))
        assertEquals(true, precisionTrimOverviewVisible(500))
        assertEquals(true, precisionTrimOverviewVisible(800))
    }
}
