package com.choplab.desktop.audio.wasapi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasapiEndpointProbeTest {
    @Test
    fun nonWindowsResultFailsClosedWithoutNativeCalls() {
        val receipt = WasapiEndpointProbe(platformCheck = { false }).probe()

        assertFalse(receipt.successful)
        assertFalse(receipt.windows)
        assertTrue(receipt.endpoints.all { !it.available })
    }

    @Test
    fun jsonReceiptContainsOnlyHashedEndpointIdentity() {
        val receipt = WasapiProbeReceipt(
            observedAt = "2026-08-20T00:00:00Z",
            windows = true,
            jnaVersion = "test",
            endpoints = listOf(
                EndpointProbe(
                    flow = EndpointFlow.RENDER,
                    available = true,
                    selection = "DEFAULT_MULTIMEDIA",
                    activeEndpointCount = null,
                    allStateEndpointCount = 3,
                    endpointIdSha256 = "ABC123",
                    state = 1,
                    mixFormat = WaveFormat(1, 2, 48_000, 192_000, 4, 16, 0),
                    devicePeriod = DevicePeriod(100_000, 30_000),
                    error = null,
                ),
            ),
            globalError = null,
        )

        val json = receipt.toJson()

        assertTrue("\"endpointIdSha256\": \"ABC123\"" in json)
        assertFalse("SWD\\MMDEVAPI" in json)
        assertTrue("\"default\":10000" in json)
    }
}
