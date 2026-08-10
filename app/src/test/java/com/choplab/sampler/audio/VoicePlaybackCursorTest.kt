package com.choplab.sampler.audio

import com.choplab.sampler.model.PadPlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackCursorTest {
    @Test
    fun wholeChopLoopWrapsForwardFromExclusiveEndToStart() {
        val cursor = VoicePlaybackCursor(
            startFrame = 10,
            endFrame = 14,
            reverse = false,
            playMode = PadPlayMode.LOOP,
        )

        cursor.advance(3.0)
        assertEquals(13.0, cursor.position, 0.0001)
        cursor.advance(1.0)

        assertEquals(10.0, cursor.position, 0.0001)
        assertFalse(cursor.finished)
    }

    @Test
    fun wholeChopLoopWrapsReverseFromStartToLastIncludedFrame() {
        val cursor = VoicePlaybackCursor(
            startFrame = 10,
            endFrame = 14,
            reverse = true,
            playMode = PadPlayMode.LOOP,
        )

        cursor.advance(3.0)
        assertEquals(10.0, cursor.position, 0.0001)
        cursor.advance(1.0)

        assertEquals(13.0, cursor.position, 0.0001)
        assertFalse(cursor.finished)
    }

    @Test
    fun oneShotStillFinishesAtTheExclusiveEnd() {
        val cursor = VoicePlaybackCursor(
            startFrame = 10,
            endFrame = 14,
            reverse = false,
            playMode = PadPlayMode.ONE_SHOT,
        )

        cursor.advance(4.0)

        assertTrue(cursor.finished)
    }
}
