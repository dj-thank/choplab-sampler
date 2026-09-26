package com.choplab.desktop.provider

import com.choplab.desktop.audio.wasapi.EndpointFlow
import com.choplab.desktop.audio.wasapi.WasapiEndpointProbe
import com.choplab.desktop.audio.wasapi.WasapiProbeReceipt
import com.choplab.desktop.isMacOsHost
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

class WindowsAudioDiagnostics(
    private val onStatus: (String) -> Unit,
    private val probe: WasapiEndpointProbe = WasapiEndpointProbe(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Windows-Audio-Diagnostics").apply { isDaemon = true }
    }

    fun run() {
        if (isMacOsHost()) {
            onStatus("音声デバイスを確認しています")
            executor.submit { onStatus(javaSoundDeviceStatus()) }
            return
        }
        onStatus("Windows音声エンドポイントを診断しています")
        executor.submit {
            runCatching { probe.probe() }
                .onSuccess { onStatus(it.statusMessage()) }
                .onFailure { error ->
                    onStatus("Windows音声診断失敗: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
internal data class NamedAudioDevice(val name: String, val playback: Boolean, val capture: Boolean)

internal fun javaSoundDeviceStatus(
    devices: List<NamedAudioDevice> = AudioSystem.getMixerInfo().map { info ->
        val mixer = AudioSystem.getMixer(info)
        NamedAudioDevice(
            name = info.name.ifBlank { info.description },
            playback = mixer.supports(SourceDataLine::class.java),
            capture = mixer.supports(TargetDataLine::class.java),
        )
    },
): String {
    if (devices.isEmpty()) return "音声デバイスが見つかりません"
    fun names(selected: List<NamedAudioDevice>) =
        selected.joinToString("、") { it.name }.ifBlank { "なし" }
    return "音声デバイス: 出力[${names(devices.filter { it.playback })}] 入力[${names(devices.filter { it.capture })}]"
}

private fun Mixer.supports(lineType: Class<*>): Boolean =
    sourceLineInfo.any { lineType.isAssignableFrom(it.lineClass) } ||
        targetLineInfo.any { lineType.isAssignableFrom(it.lineClass) }

internal fun WasapiProbeReceipt.statusMessage(): String {
    val render = endpoints.firstOrNull { it.flow == EndpointFlow.RENDER }
    val capture = endpoints.firstOrNull { it.flow == EndpointFlow.CAPTURE }
    if (render?.available == true && capture?.available == true) {
        val renderFormat = requireNotNull(render.mixFormat)
        val captureFormat = requireNotNull(capture.mixFormat)
        return "WASAPI OK: 出力 ${renderFormat.sampleRate}Hz/${renderFormat.channels}ch、入力 ${captureFormat.sampleRate}Hz/${captureFormat.channels}ch"
    }
    val renderReason = render?.error ?: "不明"
    val captureReason = capture?.error ?: "不明"
    return "Windows音声endpoint未検出: 出力[$renderReason] 入力[$captureReason]"
}
