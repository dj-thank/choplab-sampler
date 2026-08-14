package com.choplab.sampler.audio

import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInterruptionCoordinatorTest {
    @Test
    fun focusDenialBlocksPlaybackStart() {
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = false),
        )

        val decision = coordinator.beginPlayback()

        assertEquals(PlaybackStartDecision.FOCUS_DENIED, decision)
    }

    @Test
    fun appBackgroundStopsAnActivePlaybackSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())

        val plan = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Idle,
        )

        assertEquals(
            PlaybackInterruptionPlan(
                stopPlayback = true,
                requestRecordingStop = false,
                statusMessage = "バックグラウンド移行のため再生を停止しました",
            ),
            plan,
        )
        assertFalse(focus.ownsFocus)
    }

    @Test
    fun appBackgroundRequestsGracefulMicrophoneStopWithoutPlayback() {
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )

        val plan = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_MICROPHONE,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionPlan(
                stopPlayback = false,
                requestRecordingStop = true,
                statusMessage = "バックグラウンド移行のためマイク素材録音を停止します",
            ),
            plan,
        )
    }

    @Test
    fun explicitPlaybackEndReleasesFocusAndAllowsANewSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())

        coordinator.endPlaybackSession()

        assertFalse(focus.ownsFocus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())
        assertEquals(2, focus.requestCount)
    }

    @Test
    fun closeReleasesAnActiveSessionAndClosesTheBoundaryOnce() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)
        coordinator.beginPlayback()

        coordinator.close()
        coordinator.close()

        assertFalse(focus.ownsFocus)
        assertEquals(1, focus.abandonCount)
        assertEquals(1, focus.closeCount)
    }

    @Test
    fun layeredPlaybackReusesOneFocusSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)

        coordinator.beginPlayback()
        coordinator.beginPlayback()

        assertEquals(1, focus.requestCount)
    }

    @Test
    fun playbackRetargetRequiresAnActiveFocusedSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)

        assertFalse(coordinator.canRetargetPlayback())
        coordinator.beginPlayback()
        assertTrue(coordinator.canRetargetPlayback())

        coordinator.interrupt(PlaybackInterruption.AUDIO_FOCUS_LOSS, RecordingSession.Idle)

        assertFalse(coordinator.canRetargetPlayback())
    }

    @Test
    fun repeatedInterruptionDoesNotRepeatPlaybackTeardown() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = PlaybackInterruptionCoordinator(focus)
        coordinator.beginPlayback()

        coordinator.interrupt(PlaybackInterruption.AUDIO_FOCUS_LOSS, RecordingSession.Idle)
        val repeatedPlan = coordinator.interrupt(
            PlaybackInterruption.OUTPUT_BECOMING_NOISY,
            RecordingSession.Idle,
        )

        assertNull(repeatedPlan)
        assertEquals(1, focus.abandonCount)
    }

    @Test
    fun backgroundPreservesSystemAudioCaptureWhileStoppingPlayback() {
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )
        coordinator.beginPlayback()

        val plan = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_SYSTEM_AUDIO,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionPlan(
                stopPlayback = true,
                requestRecordingStop = false,
                statusMessage = "バックグラウンド移行のため再生を停止しました。端末音声録音は継続します",
            ),
            plan,
        )
    }

    @Test
    fun stoppingRecordingDoesNotRequestAnotherStop() {
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )

        val plan = coordinator.interrupt(
            event = PlaybackInterruption.AUDIO_FOCUS_LOSS,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.VOCAL_OVERDUB,
                phase = RecordingPhase.STOPPING,
            ),
        )

        assertNull(plan)
    }

    @Test
    fun noisyOutputStopsPlaybackAndVocalRecordingTogether() {
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )
        coordinator.beginPlayback()

        val plan = coordinator.interrupt(
            event = PlaybackInterruption.OUTPUT_BECOMING_NOISY,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.VOCAL_OVERDUB,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionPlan(
                stopPlayback = true,
                requestRecordingStop = true,
                statusMessage = "音声出力が切り替わったため再生とボーカル録音を停止します",
            ),
            plan,
        )
    }
}

private class ControllablePlaybackFocusAdapter(
    private val grantFocus: Boolean,
) : PlaybackFocusAdapter {
    var ownsFocus = false
        private set
    var requestCount = 0
        private set
    var abandonCount = 0
        private set
    var closeCount = 0
        private set

    override fun requestPlaybackFocus(): Boolean = grantFocus.also { granted ->
        requestCount++
        ownsFocus = granted
    }

    override fun abandonPlaybackFocus() {
        abandonCount++
        ownsFocus = false
    }

    override fun close() {
        closeCount++
    }
}
