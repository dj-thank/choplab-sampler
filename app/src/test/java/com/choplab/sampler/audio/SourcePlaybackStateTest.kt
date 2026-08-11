package com.choplab.sampler.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlaybackStateTest {
    @Test
    fun oldVoiceCompletionCannotStopQueuedNewGeneration() {
        val state = SourcePlaybackState()
        val oldGeneration = state.issuePlay()
        val newGeneration = state.issuePlay()

        assertFalse(state.complete(oldGeneration))
        assertTrue(state.isPlaying)
        assertTrue(state.isCurrent(newGeneration))
    }

    @Test
    fun playThenStopLeavesPublishedStateStopped() {
        val state = SourcePlaybackState()
        val playingGeneration = state.issuePlay()
        val stoppedGeneration = state.issueStop()

        assertFalse(state.isPlaying)
        assertFalse(state.complete(playingGeneration))
        assertTrue(state.isCurrent(stoppedGeneration))
    }

    @Test
    fun livePitchRestartRemainsPlayingUntilTheNewVoiceEnds() {
        val state = SourcePlaybackState()
        val originalGeneration = state.issuePlay()
        val restartedGeneration = state.issuePlay()

        assertFalse(state.complete(originalGeneration))
        assertTrue(state.isPlaying)
        assertTrue(state.complete(restartedGeneration))
        assertFalse(state.isPlaying)
    }
}
