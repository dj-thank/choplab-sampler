package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PadPressRoutingTest {

    @Test
    fun pendingCommandsExposeStartingAndStoppingWithoutFakingAppliedPlayback() {
        assertEquals(
            SourceUiPhase.STOPPED,
            sourceUiPhase(appliedPlaying = false, PendingSourceCommand.NONE),
        )
        assertEquals(
            SourceUiPhase.STARTING,
            sourceUiPhase(appliedPlaying = false, PendingSourceCommand.START),
        )
        assertEquals(
            SourceUiPhase.PLAYING,
            sourceUiPhase(appliedPlaying = true, PendingSourceCommand.NONE),
        )
        assertEquals(
            SourceUiPhase.STOPPING,
            sourceUiPhase(appliedPlaying = true, PendingSourceCommand.STOP),
        )
        assertEquals(
            PendingSourceCommand.NONE,
            reconcilePendingSourceCommand(PendingSourceCommand.START, appliedPlaying = true),
        )
        assertEquals(
            PendingSourceCommand.NONE,
            reconcilePendingSourceCommand(PendingSourceCommand.STOP, appliedPlaying = false),
        )
    }

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
        assertEquals(
            "すべての再生音を停止しました",
            sourcePlaybackAppliedStatusMessage(
                previouslyApplied = true,
                nowApplied = false,
                currentMessage = "すべての再生音を停止しています",
            ),
        )
        assertEquals(
            "新しい素材を入れてください",
            sourcePlaybackAppliedStatusMessage(
                previouslyApplied = true,
                nowApplied = false,
                currentMessage = "新しい素材を入れてください",
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
    fun sourceTransitionsBlockPadsInsteadOfPreviewingOrOverwriting() {
        assertEquals(SourceUiPhase.STARTING, sourceUiPhase(false, PendingSourceCommand.START))
        assertEquals(
            PadPressAction.BLOCKED_DURING_SOURCE_TRANSITION,
            resolvePadPressAction(
                sourcePlaying = false,
                padAssigned = false,
                surfaceMode = PadSurfaceMode.CAPTURE,
                pendingSourceCommand = PendingSourceCommand.START,
            ),
        )
        assertEquals(
            PadPressAction.BLOCKED_DURING_SOURCE_TRANSITION,
            resolvePadPressAction(
                sourcePlaying = true,
                padAssigned = true,
                surfaceMode = PadSurfaceMode.PERFORMANCE,
                pendingSourceCommand = PendingSourceCommand.STOP,
            ),
        )
    }

    @Test
    fun assignedCapturePadIsOverwrittenByTheCurrentPlayingSource() {
        assertEquals(
            PadPressAction.CAPTURE_CHOP,
            resolvePadPressAction(
                sourcePlaying = true,
                padAssigned = true,
                surfaceMode = PadSurfaceMode.CAPTURE,
            ),
        )
    }

    @Test
    fun activeRecordingBlocksPadPlaybackInsteadOfContaminatingTheTake() {
        assertEquals(
            PadPressAction.BLOCKED_DURING_RECORDING,
            resolvePadPressAction(
                sourcePlaying = false,
                padAssigned = true,
                surfaceMode = PadSurfaceMode.CAPTURE,
                recordingSession = RecordingSession.Active(
                    RecordingKind.SOURCE_MICROPHONE,
                    RecordingPhase.RECORDING,
                ),
            ),
        )
        assertEquals(
            PadPressAction.BLOCKED_DURING_RECORDING,
            resolvePadPressAction(
                sourcePlaying = false,
                padAssigned = true,
                surfaceMode = PadSurfaceMode.PERFORMANCE,
                recordingSession = RecordingSession.Active(
                    RecordingKind.SOURCE_MICROPHONE,
                    RecordingPhase.RECORDING,
                ),
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

    @Test
    fun performanceRoutingNeverRecordsLoopOrVocalSteps() {
        val audio = PcmAudio(name = "sound", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val loop = PadModel(0, audio, 0, 2, playMode = PadPlayMode.LOOP)
        val vocal = PadModel(1, audio, 0, 2, contentKind = PadContentKind.VOCAL)
        val drum = PadModel(2, audio, 0, 2, contentKind = PadContentKind.DRUM)
        val gate = PadModel(3, audio, 0, 2, playMode = PadPlayMode.GATE)

        assertEquals(
            PerformancePadPressAction.TOGGLE_LOOP,
            resolvePerformancePadPressAction(loop, recordArmed = true, transportPlaying = true),
        )
        assertEquals(
            PerformancePadPressAction.TRIGGER_ONLY,
            resolvePerformancePadPressAction(vocal, recordArmed = true, transportPlaying = true),
        )
        assertEquals(
            PerformancePadPressAction.TRIGGER_AND_RECORD_STEP,
            resolvePerformancePadPressAction(drum, recordArmed = true, transportPlaying = true),
        )
        assertEquals(
            PerformancePadPressAction.TRIGGER_AND_RECORD_STEP,
            resolvePerformancePadPressAction(gate, recordArmed = true, transportPlaying = true),
        )
        assertEquals(
            PerformancePadPressAction.TRIGGER_ONLY,
            resolvePerformancePadPressAction(drum, recordArmed = false, transportPlaying = true),
        )
    }

    @Test
    fun immediateHitAfterRecordStartsLandsOnTheFirstStep() {
        assertEquals(0, liveRecordingStep(-1))
        assertEquals(0, liveRecordingStep(0))
        assertEquals(15, liveRecordingStep(15))
        assertEquals(0, liveRecordingStep(16))
    }
}
