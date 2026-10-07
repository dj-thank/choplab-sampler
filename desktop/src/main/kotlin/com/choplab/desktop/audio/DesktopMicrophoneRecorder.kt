package com.choplab.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Microphone capture through a supported Java Sound input line. */
class DesktopMicrophoneRecorder : DesktopAudioRecorder {
    private val permission = MacMicrophonePermission()
    private val delegate = DesktopTargetLineRecorder(
        lineFactory = {
            if (com.choplab.desktop.isMacOsHost()) when (permission.request()) {
                MacMicrophonePermission.Status.AUTHORIZED -> Unit
                MacMicrophonePermission.Status.DENIED, MacMicrophonePermission.Status.RESTRICTED ->
                    error("マイクの使用が許可されていません。システム設定の「プライバシーとセキュリティ」でマイクを許可してください")
                else -> error("マイクの準備を完了できませんでした。もう一度開始してください")
            }
            val format = DesktopMicrophoneRecorder.microphoneFormats().firstOrNull { candidate ->
                AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, candidate))
            } ?: AudioFormat(48_000f, 16, 1, true, false)
            DesktopCaptureLine(
                AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine,
                format,
            )
        },
        threadName = "ChopLab-Microphone",
    )

    override val isRecording: Boolean
        get() = delegate.isRecording

    override val completionMessage get() = delegate.completionMessage
    override val retainedFile get() = delegate.retainedFile
    override fun cancelOpening() { permission.cancel(); delegate.cancelOpening() }
    override fun start(file: File): Result<Unit> = delegate.start(file)
    override fun stop(): Result<File> = delegate.stop()
    override fun close() = delegate.close()

    internal companion object {
        fun microphoneFormats(): List<AudioFormat> = listOf(
            AudioFormat(48_000f, 16, 1, true, false),
            AudioFormat(44_100f, 16, 1, true, false),
            AudioFormat(48_000f, 16, 2, true, false),
            AudioFormat(44_100f, 16, 2, true, false),
        )
    }
}
