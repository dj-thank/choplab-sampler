package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PadTrimTest {
    @Test
    fun `precision steps include exact frame and practical millisecond nudges`() {
        assertEquals(1, padTrimNudgeFrames(48_000, PadTrimPrecision.FRAME))
        assertEquals(48, padTrimNudgeFrames(48_000, PadTrimPrecision.MILLISECOND))
        assertEquals(480, padTrimNudgeFrames(48_000, PadTrimPrecision.TEN_MILLISECONDS))
        assertEquals(1, padTrimNudgeFrames(500, PadTrimPrecision.MILLISECOND))
    }

    @Test
    fun `entry snapshot restores both trim boundaries without replacing audio`() {
        val audio = PcmAudio(6L, "slice.wav", ShortArray(1_000), 1_000)
        val entryPad = PadModel(0, audio, startFrame = 200, endFrame = 800)
        val snapshot = capturePadTrimSnapshot(entryPad)
        val editedPad = entryPad.copy(startFrame = 333, endFrame = 666)

        val restored = restorePadTrimSnapshot(editedPad, snapshot)

        assertEquals(200, restored.startFrame)
        assertEquals(800, restored.endFrame)
        assertSame(audio, restored.audio)
    }

    @Test
    fun `entry snapshot cannot leak to another pad or source`() {
        val originalAudio = PcmAudio(4L, "first.wav", ShortArray(1_000), 1_000)
        val otherAudio = PcmAudio(5L, "second.wav", ShortArray(1_000), 1_000)
        val snapshot = capturePadTrimSnapshot(
            PadModel(0, originalAudio, startFrame = 100, endFrame = 900),
        )
        val otherPad = PadModel(1, otherAudio, startFrame = 250, endFrame = 750)

        assertEquals(otherPad, restorePadTrimSnapshot(otherPad, snapshot))
    }

    @Test
    fun `trim dials clamp start and end without changing the assigned audio`() {
        val audio = PcmAudio(7L, "slice.wav", ShortArray(1_000), 1_000)
        val pad = PadModel(0, audio, startFrame = 200, endFrame = 800)

        val startMoved = trimPadBoundary(pad, PadTrimBoundary.START, 900)
        val endMoved = trimPadBoundary(startMoved, PadTrimBoundary.END, -900)

        assertEquals(798, startMoved.startFrame)
        assertEquals(800, startMoved.endFrame)
        assertEquals(798, endMoved.startFrame)
        assertEquals(800, endMoved.endFrame)
        assertSame(audio, endMoved.audio)
    }

    @Test
    fun `trim dials move both boundaries within the source`() {
        val audio = PcmAudio(8L, "slice.wav", ShortArray(1_000), 1_000)
        val pad = PadModel(0, audio, startFrame = 200, endFrame = 800)

        val startMoved = trimPadBoundary(pad, PadTrimBoundary.START, -50)
        val endMoved = trimPadBoundary(startMoved, PadTrimBoundary.END, 75)

        assertEquals(150, endMoved.startFrame)
        assertEquals(875, endMoved.endFrame)
    }

    @Test
    fun `precision trim window is at most one second and centered at the pressed frame`() {
        assertEquals(
            SliceRange(216_000, 264_000),
            precisionTrimWindow(
                totalFrames = 480_000,
                sampleRate = 48_000,
                focusFrame = 240_000,
            ),
        )
        assertEquals(
            SliceRange(0, 48_000),
            precisionTrimWindow(480_000, 48_000, focusFrame = 100),
        )
        assertEquals(
            SliceRange(432_000, 480_000),
            precisionTrimWindow(480_000, 48_000, focusFrame = 479_999),
        )
        assertEquals(
            SliceRange(0, 400),
            precisionTrimWindow(400, 48_000, focusFrame = 200),
        )
    }

    @Test
    fun `long press chooses the nearer boundary moves it safely and keeps one second focus`() {
        val audio = PcmAudio(9L, "source.wav", ShortArray(10_000), 1_000)
        val pad = PadModel(0, audio, startFrame = 2_000, endFrame = 8_000)

        val nearEnd = focusPadTrimAtFrame(pad, pressedFrame = 6_500)
        val tieChoosesStart = focusPadTrimAtFrame(pad, pressedFrame = 5_000)

        assertEquals(PadTrimBoundary.END, nearEnd.boundary)
        assertEquals(6_500, nearEnd.pad.endFrame)
        assertEquals(2_000, nearEnd.pad.startFrame)
        assertEquals(SliceRange(6_000, 7_000), nearEnd.window)
        assertSame(audio, nearEnd.pad.audio)

        assertEquals(PadTrimBoundary.START, tieChoosesStart.boundary)
        assertEquals(5_000, tieChoosesStart.pad.startFrame)
        assertEquals(8_000, tieChoosesStart.pad.endFrame)
    }

    @Test
    fun `numeric wheel ticks use selected precision and clamp without overflow`() {
        val audio = PcmAudio(10L, "source.wav", ShortArray(10_000), 1_000)
        val pad = PadModel(0, audio, startFrame = 2_000, endFrame = 8_000)

        val start = stepPadTrimBoundary(pad, PadTrimBoundary.START, ticks = 25, PadTrimPrecision.TEN_MILLISECONDS)
        val end = stepPadTrimBoundary(start, PadTrimBoundary.END, ticks = -100, PadTrimPrecision.MILLISECOND)
        val huge = stepPadTrimBoundary(end, PadTrimBoundary.START, ticks = Int.MAX_VALUE, PadTrimPrecision.TEN_MILLISECONDS)

        assertEquals(2_250, start.startFrame)
        assertEquals(7_900, end.endFrame)
        assertEquals(7_898, huge.startFrame)
        assertTrue(huge.endFrame - huge.startFrame >= 2)
        assertSame(audio, huge.audio)
    }

    @Test
    fun `absolute trim setters clamp extreme frame values without overflowing their deltas`() {
        val audio = PcmAudio(11L, "source.wav", ShortArray(10_000), 1_000)
        val pad = PadModel(0, audio, startFrame = 2_000, endFrame = 8_000)

        val start = setPadTrimBoundary(pad, PadTrimBoundary.START, Int.MAX_VALUE)
        val end = setPadTrimBoundary(start, PadTrimBoundary.END, Int.MIN_VALUE)

        assertEquals(7_998, start.startFrame)
        assertEquals(8_000, start.endFrame)
        assertEquals(7_998, end.startFrame)
        assertEquals(8_000, end.endFrame)
    }
}
