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
    fun sourcePlaybackRequestsPreserveTheLastAudioThreadAppliedState() {
        assertEquals(
            SourcePlaybackRequestFeedback(
                sourcePlaying = false,
                statusMessage = "再生を準備中 — 音が鳴ってから空PADを叩いてください",
            ),
            sourcePlaybackRequestFeedback(
                appliedPlaying = false,
                request = SourcePlaybackRequest.START,
            ),
        )
        assertEquals(
            true,
            sourcePlaybackRequestFeedback(
                appliedPlaying = true,
                request = SourcePlaybackRequest.STOP,
            ).sourcePlaying,
        )
        assertEquals(
            "サンプリング中 — 「ここだ」で空PADを叩いてください",
            sourcePlaybackRequestFeedback(
                appliedPlaying = true,
                request = SourcePlaybackRequest.START,
            ).statusMessage,
        )
        assertEquals(
            "停止中 — PADでチョップを演奏できます",
            sourcePlaybackRequestFeedback(
                appliedPlaying = false,
                request = SourcePlaybackRequest.STOP,
            ).statusMessage,
        )
        assertEquals(
            false,
            sourcePlaybackRequestFeedback(
                appliedPlaying = false,
                request = SourcePlaybackRequest.RESTART,
            ).sourcePlaying,
        )
        assertEquals(
            true,
            sourcePlaybackRequestFeedback(
                appliedPlaying = true,
                request = SourcePlaybackRequest.SEEK,
            ).sourcePlaying,
        )
    }

    @Test
    fun secondToggleCancelsAStartThatIsStillPending() {
        assertEquals(
            SourcePlaybackToggleAction.START,
            sourcePlaybackToggleAction(appliedPlaying = false, startPending = false),
        )
        assertEquals(
            SourcePlaybackToggleAction.STOP,
            sourcePlaybackToggleAction(appliedPlaying = false, startPending = true),
        )
        assertEquals(
            SourcePlaybackToggleAction.STOP,
            sourcePlaybackToggleAction(appliedPlaying = true, startPending = false),
        )
    }

    @Test
    fun audioThreadPlaybackTransitionsReplacePendingCopyWithAppliedCopy() {
        assertEquals(
            "サンプリング中 — 「ここだ」で空PADを叩いてください",
            sourcePlaybackAppliedStatusMessage(
                previouslyApplied = false,
                nowApplied = true,
                currentMessage = "再生を準備中 — 音が鳴ってから空PADを叩いてください",
            ),
        )
        assertEquals(
            "停止中 — PADでチョップを演奏できます",
            sourcePlaybackAppliedStatusMessage(
                previouslyApplied = true,
                nowApplied = false,
                currentMessage = "停止を準備中 — 音が止まるまでお待ちください",
            ),
        )
        assertEquals(
            "曲の再生が終わりました — PADでチョップを演奏できます",
            sourcePlaybackAppliedStatusMessage(
                previouslyApplied = true,
                nowApplied = false,
                currentMessage = "再生中",
            ),
        )
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
    fun assignedCapturePadPlaysExistingChopInsteadOfOverwritingIt() {
        assertEquals(
            PadPressAction.PLAY_ASSIGNED,
            resolvePadPressAction(
                sourcePlaying = true,
                padAssigned = true,
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
