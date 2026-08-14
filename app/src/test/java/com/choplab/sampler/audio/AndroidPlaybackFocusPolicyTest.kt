package com.choplab.sampler.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidPlaybackFocusPolicyTest {
    @Test
    fun everyFocusLossStopsPlaybackWhileGainDoesNotResumeIt() {
        listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        ).forEach { focusChange ->
            assertEquals(
                PlaybackInterruption.AUDIO_FOCUS_LOSS,
                playbackInterruptionForAudioFocusChange(focusChange),
            )
        }

        assertNull(playbackInterruptionForAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }
}
