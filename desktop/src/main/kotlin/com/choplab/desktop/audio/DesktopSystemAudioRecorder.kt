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
 * helper whenever it is installed, and a loopback device only without it.
 *
 * The route is chosen when each recording starts, so enabling Stereo Mix or installing the
 * helper after launch works without restarting the app.
 */
class DesktopSystemAudioRecorder internal constructor(
    private val loopbackAvailable: () -> Boolean,
    private val helper: () -> File?,
    private val macOs: Boolean,
    private val loopback: () -> DesktopAudioRecorder,
    private val screenCapture: (File) -> DesktopAudioRecorder,
) : DesktopAudioRecorder {
    constructor() : this(
        loopbackAvailable = { AudioSystem.getMixerInfo().any { looksLikeLoopback(it.name, it.description) } },
        helper = { locateMacSystemAudioHelper() },
        macOs = isMacOsHost(),
        loopback = { DesktopTargetLineRecorder(::findLoopbackLine, "ChopLab-System-Audio") },
        screenCapture = ::MacSystemAudioProcessRecorder,
    )

    private val lifecycleLock = Any()
    // Reused per route, so each recorder's own stop/start guard still covers a slow stop.
    private var loopbackRecorder: DesktopAudioRecorder? = null
    private var captureRecorder: Pair<File, DesktopAudioRecorder>? = null
    @Volatile private var active: DesktopAudioRecorder? = null

    override val isRecording: Boolean
        get() = active?.isRecording == true

    override fun start(file: File): Result<Unit> {
        val recorder = synchronized(lifecycleLock) {
            if (active?.isRecording == true) return Result.failure(IllegalStateException("録音の停止処理中です"))
            val helperFile = helper()
            when (chooseSystemAudioRoute(loopbackAvailable(), helperFile != null, macOs)) {
                SystemAudioRoute.SCREEN_CAPTURE -> {
                    val executable = requireNotNull(helperFile)
                    captureRecorder?.takeIf { it.first == executable }?.second
                        ?: screenCapture(executable).also { captureRecorder = executable to it }
                }
                SystemAudioRoute.LOOPBACK -> loopbackRecorder ?: loopback().also { loopbackRecorder = it }
                SystemAudioRoute.MISSING -> return Result.failure(IllegalStateException(missingSystemAudioMessage(macOs)))
            }.also { active = it }
        }
        return recorder.start(file)
    }

    override fun stop(): Result<File> =
        active?.stop() ?: Result.failure(IllegalStateException("録音された音声がありません"))

    override fun close() {
        val owned = synchronized(lifecycleLock) { listOfNotNull(loopbackRecorder, captureRecorder?.second) }
        owned.forEach { runCatching { it.close() } }
    }

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

        /** On a Mac the helper records what is actually heard; a BlackHole input is silent unless output is routed to it. */
        internal fun chooseSystemAudioRoute(loopbackAvailable: Boolean, helperAvailable: Boolean, macOs: Boolean): SystemAudioRoute = when {
            macOs && helperAvailable -> SystemAudioRoute.SCREEN_CAPTURE
            loopbackAvailable -> SystemAudioRoute.LOOPBACK
            else -> SystemAudioRoute.MISSING
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

internal enum class SystemAudioRoute { LOOPBACK, SCREEN_CAPTURE, MISSING }
