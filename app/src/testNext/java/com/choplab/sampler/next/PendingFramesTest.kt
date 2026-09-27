package com.choplab.sampler.next

import org.junit.Assert.assertEquals
import org.junit.Test

/** The output delay estimate from AudioTrack's timestamp, whose frame position keeps only 32 bits. */
class PendingFramesTest {
    @Test fun framesInFlightMinusWhatPlayedSinceTheTimestamp() {
        // 1 440 frames not yet heard at the timestamp; 10 ms later 480 more of them have played.
        assertEquals(960L, pendingFrames(framesWritten = 101_440, presentedLow32 = 100_000, sinceNanos = 10_000_000, sampleRate = 48_000))
        assertEquals(0L, pendingFrames(framesWritten = 100_000, presentedLow32 = 100_000, sinceNanos = 50_000_000, sampleRate = 48_000))
    }

    @Test fun staysRightAfterThePresentedPositionWraps() {
        // After about 25 hours the true position passes 2^32 while AudioTrack reports only its low 32 bits.
        val truePosition = (1L shl 32) + 5_000
        val written = truePosition + 1_440
        assertEquals(1_440L, pendingFrames(written, truePosition and 0xFFFF_FFFFL, sinceNanos = 0, sampleRate = 48_000))
        // Just before the wrap the written count is already past it.
        assertEquals(1_440L, pendingFrames((1L shl 32) + 400, (1L shl 32) - 1_040, sinceNanos = 0, sampleRate = 48_000))
    }
}
