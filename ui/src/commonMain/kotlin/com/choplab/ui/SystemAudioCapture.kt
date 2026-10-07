package com.choplab.ui

import com.choplab.core.model.Asset

/** Host-owned system audio input. Permission and helper errors are typed; no platform text enters the editor. */
interface SystemAudioCapture {
    enum class Start { STARTED, DENIED, NO_DISPLAY, UNAVAILABLE, NO_ROOM, CANCELLED, TIMEOUT }
    suspend fun start(maxSeconds: Int): Start
    fun cancelOpening()
    val full: Boolean
    val interrupted: Boolean
    val recordedMillis: Long
    val inputReadout: RecordingInputReadout get() = RecordingInputReadout(recordedMillis = recordedMillis)
    suspend fun acknowledgeTake() {}
    suspend fun stop(name: String): Asset?
    suspend fun discard()
    suspend fun close()
}
