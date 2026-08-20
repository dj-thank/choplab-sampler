package com.choplab.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Windows microphone capture through the default Java Sound input line. */
class DesktopMicrophoneRecorder : DesktopAudioRecorder {
    private val delegate = DesktopTargetLineRecorder(
        lineFactory = {
            val format = AudioFormat(48_000f, 16, 1, true, false)
            DesktopCaptureLine(
                AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine,
                format,
            )
        },
        threadName = "ChopLab-Windows-Microphone",
    )

    override val isRecording: Boolean
        get() = delegate.isRecording

    override fun start(file: File): Result<Unit> = delegate.start(file)
    override fun stop(): Result<File> = delegate.stop()
    override fun close() = delegate.close()
}
