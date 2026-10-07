package com.choplab.desktop.next

import com.choplab.jvm.FileAssetStore
import com.choplab.jvm.VoiceTakes
import com.choplab.ui.SystemAudioCapture
import java.nio.file.Path

/** ScreenCaptureKit capture shares float WAV storage and quotas with voice takes, preserving stereo. */
internal class NextSystemAudioCapture(assets: FileAssetStore, scratch: Path, private val persistAccepted: suspend () -> Unit = {}) : SystemAudioCapture {
    private val input = MacSystemInput()
    private val takes = VoiceTakes(assets, scratch, captureChannels = 2, durableTakes = true, microphone = input::open)
    override suspend fun start(maxSeconds: Int) = when (takes.start(maxSeconds)) {
        VoiceTakes.Start.STARTED -> SystemAudioCapture.Start.STARTED
        VoiceTakes.Start.NO_ROOM -> SystemAudioCapture.Start.NO_ROOM
        VoiceTakes.Start.NO_INPUT -> when (input.failure) {
            MacSystemInput.Failure.DENIED -> SystemAudioCapture.Start.DENIED
            MacSystemInput.Failure.NO_DISPLAY -> SystemAudioCapture.Start.NO_DISPLAY
            MacSystemInput.Failure.CANCELLED -> SystemAudioCapture.Start.CANCELLED
            MacSystemInput.Failure.TIMEOUT -> SystemAudioCapture.Start.TIMEOUT
            else -> SystemAudioCapture.Start.UNAVAILABLE
        }
    }
    override fun cancelOpening() { input.cancelOpening(); takes.cancelOpening() }
    override val inputReadout get() = takes.inputReadout()
    override suspend fun acknowledgeTake() { persistAccepted(); takes.acknowledge() }
    val inputBusy get() = takes.inputBusy
    override val full get() = takes.full
    override val interrupted get() = takes.interrupted
    override val recordedMillis get() = takes.recordedMillis
    override suspend fun stop(name: String) = takes.stop(name)?.asset
    override suspend fun discard() = takes.discard()
    override suspend fun close() { input.close(); takes.close() }
}
