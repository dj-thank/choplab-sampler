package com.choplab.ui

import androidx.compose.runtime.Immutable

/** Session preferences/readout only: click and count-in never enter the project or its Undo history. */
@Immutable data class RecordingGuideState(
    val metronomeEnabled: Boolean = false,
    val countInBars: Int = 0,
    val beatsRemaining: Int = 0,
    val settingsEnabled: Boolean = false,
)

sealed interface RecordingGuideAction {
    data class Metronome(val enabled: Boolean) : RecordingGuideAction
    data class CountInBars(val bars: Int) : RecordingGuideAction { init { require(bars in 0..2) } }
}

/** Platform input ownership. Permission completes before the armed capture/count-in can start. */
interface RecordingCuePort {
    suspend fun startArmedVoice(maxSeconds: Int): VoiceStart
    /** Schedule the input gate against the engine's published cue; false means the output/cue is unavailable. */
    fun cueVoiceAt(engineFrame: Long): Boolean
    fun armingTimedOut(): Boolean
}
