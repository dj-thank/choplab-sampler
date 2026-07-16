package com.choplab.sampler.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface PlaybackCaptureState {
    data object Idle : PlaybackCaptureState
    data object Recording : PlaybackCaptureState
    data class Completed(val file: File) : PlaybackCaptureState
    data class Error(val message: String) : PlaybackCaptureState
}

object CaptureEventBus {
    private val mutableState = MutableStateFlow<PlaybackCaptureState>(PlaybackCaptureState.Idle)
    val state: StateFlow<PlaybackCaptureState> = mutableState.asStateFlow()

    fun publish(value: PlaybackCaptureState) {
        mutableState.value = value
    }

    fun reset() {
        mutableState.value = PlaybackCaptureState.Idle
    }
}
