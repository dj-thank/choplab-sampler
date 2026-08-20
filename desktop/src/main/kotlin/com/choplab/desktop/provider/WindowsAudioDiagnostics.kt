package com.choplab.desktop.provider

import com.choplab.desktop.audio.wasapi.EndpointFlow
import com.choplab.desktop.audio.wasapi.WasapiEndpointProbe
import com.choplab.desktop.audio.wasapi.WasapiProbeReceipt
import java.util.concurrent.Executors

class WindowsAudioDiagnostics(
    private val onStatus: (String) -> Unit,
    private val probe: WasapiEndpointProbe = WasapiEndpointProbe(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Windows-Audio-Diagnostics").apply { isDaemon = true }
    }

    fun run() {
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
