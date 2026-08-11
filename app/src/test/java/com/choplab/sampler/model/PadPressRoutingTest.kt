package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PadPressRoutingTest {

    @Test
    fun completedSourceRestartsFromBeginning() {
        assertEquals(0, sourcePlaybackStartFrame(requestedFrame = 999, frameCount = 1_000))
        assertEquals(0, sourcePlaybackStartFrame(requestedFrame = 1_000, frameCount = 1_000))
    }

    @Test
    fun pausedSourceResumesFromCurrentFrame() {
        assertEquals(420, sourcePlaybackStartFrame(requestedFrame = 420, frameCount = 1_000))
    }
    @Test
    fun sourcePlaybackDoesNotTurnPerformancePadIntoCapture() {
        assertEquals(
            PadPressAction.PLAY_ASSIGNED,
            resolvePadPressAction(
                sourcePlaying = true,
                padAssigned = true,
                surfaceMode = PadSurfaceMode.PERFORMANCE,
            ),
        )
    }

    @Test
    fun captureSurfaceRoutesPlayingSourceToLiveChop() {
        assertEquals(
            PadPressAction.CAPTURE_CHOP,
            resolvePadPressAction(
                sourcePlaying = true,
                padAssigned = false,
                surfaceMode = PadSurfaceMode.CAPTURE,
            ),
        )
    }

    @Test
    fun emptyPerformancePadOnlyChangesSelection() {
        assertEquals(
            PadPressAction.SELECT_ONLY,
            resolvePadPressAction(
                sourcePlaying = false,
                padAssigned = false,
                surfaceMode = PadSurfaceMode.PERFORMANCE,
            ),
        )
    }
}
