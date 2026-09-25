package com.choplab.desktop.audio

import com.choplab.desktop.isMacOsHost
import java.io.File
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Records a playback-loopback input when the driver exposes one.
 * It deliberately refuses an ordinary microphone. macOS uses the ScreenCaptureKit
 * helper when no loopback device is installed.
 */
class DesktopSystemAudioRecorder(
    private val delegate: DesktopAudioRecorder = DesktopSystemAudioRecorder.defaultSystemAudioRecorder(),
) : DesktopAudioRecorder {

    override val isRecording: Boolean
        get() = delegate.isRecording

    override fun start(file: File): Result<Unit> = delegate.start(file)
    override fun stop(): Result<File> = delegate.stop()
    override fun close() = delegate.close()

    companion object {
        internal fun looksLikeLoopback(name: String, description: String): Boolean {
            val candidate = "$name $description".lowercase(Locale.ROOT)
            return LOOPBACK_MARKERS.any(candidate::contains)
        }

        private fun findLoopbackLine(): DesktopCaptureLine {
            val match = AudioSystem.getMixerInfo().firstNotNullOfOrNull mixerLoop@{ info ->
                if (!looksLikeLoopback(info.name, info.description)) return@mixerLoop null
                val mixer = AudioSystem.getMixer(info)
                candidateFormats().firstNotNullOfOrNull formatLoop@{ format ->
                    val request = DataLine.Info(TargetDataLine::class.java, format)
                    if (!mixer.isLineSupported(request)) return@formatLoop null
                    runCatching {
                        DesktopCaptureLine(mixer.getLine(request) as TargetDataLine, format)
                    }.getOrNull()
                }
            }
            return match ?: error(missingSystemAudioMessage(isMacOsHost()))
        }

        internal fun missingSystemAudioMessage(macOs: Boolean): String = if (macOs) {
            "システムの音声入力が見つかりません。画面収録とシステムオーディオ録音を許可するか、BlackHoleなどのループバック装置を有効にしてください"
        } else {
            "Windowsの再生ループバック入力が見つかりません。サウンド設定で「ステレオ ミキサー」等を有効にしてください"
        }

        internal fun defaultSystemAudioRecorder(
            loopbackAvailable: Boolean = AudioSystem.getMixerInfo().any { looksLikeLoopback(it.name, it.description) },
            helper: File? = locateMacSystemAudioHelper(),
            macOs: Boolean = isMacOsHost(),
        ): DesktopAudioRecorder = when {
            loopbackAvailable -> DesktopTargetLineRecorder(::findLoopbackLine, "ChopLab-System-Audio")
            macOs && helper != null -> MacSystemAudioProcessRecorder(helper)
            else -> DesktopTargetLineRecorder({ error(missingSystemAudioMessage(macOs)) }, "ChopLab-System-Audio")
        }

        internal fun candidateFormats(): List<AudioFormat> = listOf(
            AudioFormat(48_000f, 16, 2, true, false),
            AudioFormat(44_100f, 16, 2, true, false),
            AudioFormat(48_000f, 16, 1, true, false),
            AudioFormat(44_100f, 16, 1, true, false),
        )

        private val LOOPBACK_MARKERS = listOf(
            "stereo mix",
            "what u hear",
            "wave out",
            "loopback",
            "ステレオ ミキサー",
            "ステレオミキサー",
            "再生リダイレクト",
            "blackhole",
            "soundflower",
            "background music",
        )
    }
}
