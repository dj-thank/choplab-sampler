package com.choplab.sampler.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlaybackStateTest {
    @Test
    fun issuedPlayIsNotPublishedUntilAudioThreadAppliesIt() {
        val state = SourcePlaybackState()
        val generation = state.issuePlay()

        assertFalse(state.isPlaying)
        assertTrue(state.applyPlay(generation))
        assertTrue(state.isPlaying)
    }

    @Test
    fun stalePlayCannotBeAppliedAfterANewerCommandWasIssued() {
        val state = SourcePlaybackState()
        val staleGeneration = state.issuePlay()
        val currentGeneration = state.issuePlay()

        assertFalse(state.isPlaying)
        assertFalse(state.applyPlay(staleGeneration))
        assertTrue(state.applyPlay(currentGeneration))
        assertTrue(state.isPlaying)
    }

    @Test
    fun queuedRestartDoesNotHideCompletionOfTheVoiceThatActuallyPlayed() {
        val state = SourcePlaybackState()
        val originalGeneration = state.issuePlay()
        assertTrue(state.applyPlay(originalGeneration))
        val restartedGeneration = state.issuePlay()

        assertTrue(state.complete(originalGeneration))
        assertFalse(state.isPlaying)
        assertTrue(state.applyPlay(restartedGeneration))
        assertTrue(state.isPlaying)
        assertTrue(state.complete(restartedGeneration))
        assertFalse(state.isPlaying)
    }

    @Test
    fun oldVoiceCompletionCannotStopANewerAppliedGeneration() {
        val state = SourcePlaybackState()
        val originalGeneration = state.issuePlay()
        assertTrue(state.applyPlay(originalGeneration))
        val restartedGeneration = state.issuePlay()
        assertTrue(state.applyPlay(restartedGeneration))

        assertFalse(state.complete(originalGeneration))
        assertTrue(state.isPlaying)
        assertTrue(state.complete(restartedGeneration))
        assertFalse(state.isPlaying)
    }

    @Test
    fun issuedStopIsPublishedOnlyAfterTheAudioThreadAppliesIt() {
        val state = SourcePlaybackState()
        val playingGeneration = state.issuePlay()
        assertTrue(state.applyPlay(playingGeneration))

        val stoppedGeneration = state.issueStop()

        assertTrue(state.isPlaying)
        assertTrue(state.applyStop(stoppedGeneration))
        assertFalse(state.isPlaying)
    }

    @Test
    fun stopAllBoundaryPublishesStoppedWhenANewerGenerationWasIssuedConcurrently() {
        val state = SourcePlaybackState()
        val playingGeneration = state.issuePlay()
        assertTrue(state.applyPlay(playingGeneration))

        val stopGeneration = state.issueStop()
        val laterPlayGeneration = state.issuePlay()
        state.applyStopBoundary(stopGeneration)

        assertFalse(state.isPlaying)
        assertTrue(state.applyPlay(laterPlayGeneration))
        assertTrue(state.isPlaying)
    }
}
