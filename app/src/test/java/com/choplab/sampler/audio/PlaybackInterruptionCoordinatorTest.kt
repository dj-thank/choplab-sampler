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
    fun interruptionSilencesPlaybackBeforeReleasingFocus() {
        val events = mutableListOf<String>()
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = EventRecordingPlaybackFocusAdapter(events),
            playbackSilencer = PlaybackSilencer { events += "silence" },
        )
        coordinator.beginPlayback()
        events.clear()

        coordinator.interrupt(
            event = PlaybackInterruption.AUDIO_FOCUS_LOSS,
            recordingSession = RecordingSession.Idle,
        )

        assertEquals(listOf("silence", "abandon-focus"), events)
    }

    @Test
    fun closeSilencesActivePlaybackBeforeReleasingResources() {
        val events = mutableListOf<String>()
        val coordinator = PlaybackInterruptionCoordinator(
            focusAdapter = EventRecordingPlaybackFocusAdapter(events),
            playbackSilencer = PlaybackSilencer { events += "silence" },
        )
        coordinator.beginPlayback()
        events.clear()

        coordinator.close()
        coordinator.close()

        assertEquals(listOf("silence", "abandon-focus", "close-focus"), events)
    }

    @Test
    fun interruptionOutcomeReportsThatPlaybackWasStopped() {
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )
        coordinator.beginPlayback()

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Idle,
        )

        assertTrue(requireNotNull(outcome).playbackStopped)
    }

    @Test
    fun focusDenialBlocksPlaybackStart() {
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = false),
        )

        val decision = coordinator.beginPlayback()

        assertEquals(PlaybackStartDecision.FOCUS_DENIED, decision)
    }

    @Test
    fun appBackgroundStopsAnActivePlaybackSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = coordinator(focus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Idle,
        )

        assertEquals(
            PlaybackInterruptionOutcome(
                playbackStopped = true,
                requestRecordingStop = false,
                statusMessage = "バックグラウンド移行のため再生を停止しました",
            ),
            outcome,
        )
        assertFalse(focus.ownsFocus)
    }

    @Test
    fun appBackgroundRequestsGracefulMicrophoneStopWithoutPlayback() {
        var silenceCount = 0
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
            playbackSilencer = PlaybackSilencer { silenceCount++ },
        )

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_MICROPHONE,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionOutcome(
                playbackStopped = false,
                requestRecordingStop = true,
                statusMessage = "バックグラウンド移行のためマイク素材録音を停止します",
            ),
            outcome,
        )
        assertEquals(0, silenceCount)
    }

    @Test
    fun explicitPlaybackEndReleasesFocusAndAllowsANewSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = coordinator(focus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())

        coordinator.endPlaybackSession()

        assertFalse(focus.ownsFocus)
        assertEquals(PlaybackStartDecision.READY, coordinator.beginPlayback())
        assertEquals(2, focus.requestCount)
    }

    @Test
    fun closeReleasesAnActiveSessionAndClosesTheBoundaryOnce() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = coordinator(focus)
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
        val coordinator = coordinator(focus)

        coordinator.beginPlayback()
        coordinator.beginPlayback()

        assertEquals(1, focus.requestCount)
    }

    @Test
    fun playbackRetargetRequiresAnActiveFocusedSession() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        val coordinator = coordinator(focus)

        assertFalse(coordinator.canRetargetPlayback())
        coordinator.beginPlayback()
        assertTrue(coordinator.canRetargetPlayback())

        coordinator.interrupt(PlaybackInterruption.AUDIO_FOCUS_LOSS, RecordingSession.Idle)

        assertFalse(coordinator.canRetargetPlayback())
    }

    @Test
    fun repeatedInterruptionDoesNotRepeatPlaybackTeardown() {
        val focus = ControllablePlaybackFocusAdapter(grantFocus = true)
        var silenceCount = 0
        val coordinator = coordinator(
            focusAdapter = focus,
            playbackSilencer = PlaybackSilencer { silenceCount++ },
        )
        coordinator.beginPlayback()

        coordinator.interrupt(PlaybackInterruption.AUDIO_FOCUS_LOSS, RecordingSession.Idle)
        val repeatedOutcome = coordinator.interrupt(
            PlaybackInterruption.OUTPUT_BECOMING_NOISY,
            RecordingSession.Idle,
        )

        assertNull(repeatedOutcome)
        assertEquals(1, silenceCount)
        assertEquals(1, focus.abandonCount)
    }

    @Test
    fun backgroundPreservesSystemAudioCaptureWhileStoppingPlayback() {
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )
        coordinator.beginPlayback()

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.APP_BACKGROUND,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_SYSTEM_AUDIO,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionOutcome(
                playbackStopped = true,
                requestRecordingStop = false,
                statusMessage = "バックグラウンド移行のため再生を停止しました。端末音声録音は継続します",
            ),
            outcome,
        )
    }

    @Test
    fun stoppingRecordingDoesNotRequestAnotherStop() {
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.AUDIO_FOCUS_LOSS,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.VOCAL_OVERDUB,
                phase = RecordingPhase.STOPPING,
            ),
        )

        assertNull(outcome)
    }

    @Test
    fun noisyOutputStopsPlaybackAndVocalRecordingTogether() {
        val coordinator = coordinator(
            focusAdapter = ControllablePlaybackFocusAdapter(grantFocus = true),
        )
        coordinator.beginPlayback()

        val outcome = coordinator.interrupt(
            event = PlaybackInterruption.OUTPUT_BECOMING_NOISY,
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.VOCAL_OVERDUB,
                phase = RecordingPhase.RECORDING,
            ),
        )

        assertEquals(
            PlaybackInterruptionOutcome(
                playbackStopped = true,
                requestRecordingStop = true,
                statusMessage = "音声出力が切り替わったため再生とボーカル録音を停止します",
            ),
            outcome,
        )
    }

    private fun coordinator(
        focusAdapter: PlaybackFocusAdapter,
        playbackSilencer: PlaybackSilencer = PlaybackSilencer {},
    ): PlaybackInterruptionCoordinator = PlaybackInterruptionCoordinator(
        focusAdapter = focusAdapter,
        playbackSilencer = playbackSilencer,
    )
}

private class EventRecordingPlaybackFocusAdapter(
    private val events: MutableList<String>,
) : PlaybackFocusAdapter {
    override fun requestPlaybackFocus(): Boolean = true

    override fun abandonPlaybackFocus() {
        events += "abandon-focus"
    }

    override fun close() {
        events += "close-focus"
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
