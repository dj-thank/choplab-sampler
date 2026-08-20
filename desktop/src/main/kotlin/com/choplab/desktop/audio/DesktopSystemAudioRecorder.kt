package com.choplab.desktop.audio

import java.io.File
import java.util.Locale
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Records a Windows playback-loopback input exposed by the installed driver.
 * It deliberately refuses an arbitrary capture line so a missing loopback does
 * not silently record the microphone instead.
 */
class DesktopSystemAudioRecorder : DesktopAudioRecorder {
    private val delegate = DesktopTargetLineRecorder(
        lineFactory = ::findLoopbackLine,
        threadName = "ChopLab-Windows-System-Audio",
    )

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
            return match ?: error(
                "Windowsの再生ループバック入力が見つかりません。サウンド設定で「ステレオ ミキサー」等を有効にしてください",
            )
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
        )
    }
}
