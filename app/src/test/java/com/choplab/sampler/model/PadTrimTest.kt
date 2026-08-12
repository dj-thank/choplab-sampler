package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
}
