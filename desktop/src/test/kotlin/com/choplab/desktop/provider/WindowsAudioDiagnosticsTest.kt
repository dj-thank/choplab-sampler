package com.choplab.desktop.provider

import com.choplab.desktop.audio.wasapi.DevicePeriod
import com.choplab.desktop.audio.wasapi.EndpointFlow
import com.choplab.desktop.audio.wasapi.EndpointProbe
import com.choplab.desktop.audio.wasapi.WasapiProbeReceipt
import com.choplab.desktop.audio.wasapi.WaveFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WindowsAudioDiagnosticsTest {
    @Test
    fun successfulReceiptReportsRenderAndCaptureFormats() {
        val receipt = WasapiProbeReceipt(
            observedAt = "now",
            windows = true,
            jnaVersion = "test",
            endpoints = listOf(
                endpoint(EndpointFlow.RENDER, 48_000, 2),
                endpoint(EndpointFlow.CAPTURE, 44_100, 1),
            ),
            globalError = null,
        )

        assertEquals("WASAPI OK: 出力 48000Hz/2ch、入力 44100Hz/1ch", receipt.statusMessage())
    }

    @Test
    fun unavailableReceiptKeepsBothFailureBoundariesVisible() {
        val receipt = WasapiProbeReceipt(
            observedAt = "now",
            windows = true,
            jnaVersion = "test",
            endpoints = listOf(
                EndpointProbe.unavailable(EndpointFlow.RENDER, "no render"),
                EndpointProbe.unavailable(EndpointFlow.CAPTURE, "no capture"),
            ),
            globalError = null,
        )

        val message = receipt.statusMessage()
        assertContains(message, "出力[no render]")
        assertContains(message, "入力[no capture]")
    }

    private fun endpoint(flow: EndpointFlow, sampleRate: Int, channels: Int) = EndpointProbe(
        flow = flow,
        available = true,
        selection = "DEFAULT_MULTIMEDIA",
        activeEndpointCount = 1,
        allStateEndpointCount = 1,
        endpointIdSha256 = "hash",
        state = 1,
        mixFormat = WaveFormat(1, channels, sampleRate, sampleRate * channels * 2, channels * 2, 16, 0),
        devicePeriod = DevicePeriod(100_000, 30_000),
        error = null,
    )
}
