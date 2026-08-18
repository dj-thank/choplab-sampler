package com.choplab.sampler.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Owns Android audio focus and output-route notifications for one sampler session.
 * Callbacks are dispatched on the main thread and never auto-resume playback.
 */
class AndroidPlaybackFocusAdapter(
    context: Context,
    private val onInterruption: (PlaybackInterruption) -> Unit,
) : PlaybackFocusAdapter {
    private val applicationContext = context.applicationContext
    private val audioManager = requireNotNull(
        applicationContext.getSystemService(AudioManager::class.java),
    ) { "AudioManager is unavailable on this device" }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        playbackInterruptionForAudioFocusChange(focusChange)?.let(onInterruption)
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener(focusChangeListener, Handler(Looper.getMainLooper()))
        .setWillPauseWhenDucked(true)
        .build()
    private val noisyOutputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onInterruption(PlaybackInterruption.OUTPUT_BECOMING_NOISY)
            }
        }
    }
    private var receiverRegistered = false
    private var closed = false

    init {
        ContextCompat.registerReceiver(
            applicationContext,
            noisyOutputReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun requestPlaybackFocus(): Boolean {
        if (closed) return false
        return audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonPlaybackFocus() {
        if (closed) return
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun close() {
        if (closed) return

        if (receiverRegistered) {
            applicationContext.unregisterReceiver(noisyOutputReceiver)
            receiverRegistered = false
        }
        audioManager.abandonAudioFocusRequest(focusRequest)
        closed = true
    }
}

internal fun playbackInterruptionForAudioFocusChange(
    focusChange: Int,
): PlaybackInterruption? = when (focusChange) {
    AudioManager.AUDIOFOCUS_GAIN -> null
    else -> PlaybackInterruption.AUDIO_FOCUS_LOSS
}
