package com.choplab.desktop.audio.wasapi

import com.sun.jna.Native
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WasapiEndpointProbe(
    private val platformCheck: () -> Boolean = ::isWindows,
) {
    fun probe(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): WasapiProbeReceipt {
        require(timeoutMillis in 1L..30_000L) { "WASAPI probe timeout is out of bounds" }
        if (!platformCheck()) {
            return WasapiProbeReceipt(
                observedAt = Instant.now().toString(),
                windows = false,
                jnaVersion = Native.VERSION,
                endpoints = EndpointFlow.entries.map { EndpointProbe.unavailable(it, "Windows is required") },
                globalError = "WASAPI is available only on Windows",
            )
        }

        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ChopLab-WASAPI-Probe-STA").apply { isDaemon = true }
        }
        return try {
            executor.submit<WasapiProbeReceipt> { probeOnSta() }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            failedReceipt("WASAPI endpoint probe timed out after ${timeoutMillis}ms")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failedReceipt("WASAPI endpoint probe was interrupted")
        } catch (execution: ExecutionException) {
            val cause = execution.cause ?: execution
            failedReceipt(cause.message ?: cause.javaClass.simpleName)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeOnSta(): WasapiProbeReceipt = withSta {
        val enumerator = createDeviceEnumerator()
        try {
            WasapiProbeReceipt(
                observedAt = Instant.now().toString(),
                windows = true,
                jnaVersion = Native.VERSION,
                endpoints = EndpointFlow.entries.map { flow -> probeEndpoint(enumerator, flow) },
                globalError = null,
            )
        } finally {
            enumerator.Release()
        }
    }

    private fun probeEndpoint(enumerator: MmDeviceEnumerator, flow: EndpointFlow): EndpointProbe {
        var device: MmDevice? = null
        var client: AudioClient? = null
        var selection: String? = null
        var activeEndpointCount = 0
        var allStateEndpointCount = 0
        return try {
            allStateEndpointCount = enumerator.endpointCount(flow, DEVICE_STATE_ALL)
            for (role in EndpointRole.entries) {
                device = try {
                    enumerator.defaultEndpoint(flow, role)
                } catch (failure: WasapiException) {
                    if (failure.hresult != HRESULT_NOT_FOUND) throw failure
                    null
                }
                if (device != null) {
                    selection = "DEFAULT_${role.name}"
                    break
                }
            }
            if (device == null) {
                val active = enumerator.endpoints(flow, DEVICE_STATE_ACTIVE)
                activeEndpointCount = active.size
                device = active.firstOrNull()
                active.drop(1).forEach(MmDevice::Release)
                selection = "ENUMERATED_ACTIVE_0"
            }
            requireNotNull(device) { "No active ${flow.name} endpoint is available" }
            val endpointIdHash = sha256(device.endpointId())
            val state = device.state()
            client = device.activateAudioClient()
            EndpointProbe(
                flow = flow,
                available = true,
                selection = selection,
                activeEndpointCount = activeEndpointCount,
                allStateEndpointCount = allStateEndpointCount,
                endpointIdSha256 = endpointIdHash,
                state = state,
                mixFormat = client.mixFormat(),
                devicePeriod = client.devicePeriod(),
                error = null,
            )
        } catch (failure: Exception) {
            EndpointProbe.unavailable(
                flow,
                "${failure.message ?: failure.javaClass.simpleName}; active=$activeEndpointCount all=$allStateEndpointCount",
                activeEndpointCount = activeEndpointCount,
                allStateEndpointCount = allStateEndpointCount,
            )
        } finally {
            client?.Release()
            device?.Release()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02X".format(byte) }

    private fun failedReceipt(error: String): WasapiProbeReceipt = WasapiProbeReceipt(
        observedAt = Instant.now().toString(),
        windows = true,
        jnaVersion = Native.VERSION,
        endpoints = EndpointFlow.entries.map { EndpointProbe.unavailable(it, error) },
        globalError = error,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}

data class WasapiProbeReceipt(
    val observedAt: String,
    val windows: Boolean,
    val jnaVersion: String,
    val endpoints: List<EndpointProbe>,
    val globalError: String?,
) {
    val successful: Boolean
        get() = windows && globalError == null && endpoints.all(EndpointProbe::available)

    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": 1,")
        appendLine("  \"observedAt\": \"${observedAt.jsonEscape()}\",")
        appendLine("  \"windows\": $windows,")
        appendLine("  \"jnaVersion\": \"${jnaVersion.jsonEscape()}\",")
        appendLine("  \"successful\": $successful,")
        appendLine("  \"globalError\": ${globalError?.let { "\"${it.jsonEscape()}\"" } ?: "null"},")
        appendLine("  \"endpoints\": [")
        endpoints.forEachIndexed { index, endpoint ->
            append(endpoint.toJson("    "))
            appendLine(if (index == endpoints.lastIndex) "" else ",")
        }
        appendLine("  ]")
        append("}")
    }
}

data class EndpointProbe(
    val flow: EndpointFlow,
    val available: Boolean,
    val selection: String?,
    val activeEndpointCount: Int?,
    val allStateEndpointCount: Int?,
    val endpointIdSha256: String?,
    val state: Int?,
    val mixFormat: WaveFormat?,
    val devicePeriod: DevicePeriod?,
    val error: String?,
) {
    fun toJson(indent: String): String = buildString {
        appendLine("${indent}{")
        appendLine("$indent  \"flow\": \"${flow.name}\",")
        appendLine("$indent  \"available\": $available,")
        appendLine("$indent  \"selection\": ${selection?.let { "\"$it\"" } ?: "null"},")
        appendLine("$indent  \"activeEndpointCount\": ${activeEndpointCount ?: "null"},")
        appendLine("$indent  \"allStateEndpointCount\": ${allStateEndpointCount ?: "null"},")
        appendLine("$indent  \"endpointIdSha256\": ${endpointIdSha256?.let { "\"$it\"" } ?: "null"},")
        appendLine("$indent  \"state\": ${state ?: "null"},")
        appendLine("$indent  \"mixFormat\": ${mixFormat?.toJson() ?: "null"},")
        appendLine("$indent  \"devicePeriodMicros\": ${devicePeriod?.let { "{\"default\":${it.defaultMicros},\"minimum\":${it.minimumMicros}}" } ?: "null"},")
        append("$indent  \"error\": ${error?.let { "\"${it.jsonEscape()}\"" } ?: "null"}")
        appendLine()
        append("$indent}")
    }

    companion object {
        fun unavailable(
            flow: EndpointFlow,
            error: String,
            activeEndpointCount: Int? = null,
            allStateEndpointCount: Int? = null,
        ): EndpointProbe = EndpointProbe(
            flow = flow,
            available = false,
            selection = null,
            activeEndpointCount = activeEndpointCount,
            allStateEndpointCount = allStateEndpointCount,
            endpointIdSha256 = null,
            state = null,
            mixFormat = null,
            devicePeriod = null,
            error = error,
        )
    }
}

private fun WaveFormat.toJson(): String =
    "{\"tag\":$formatTag,\"channels\":$channels,\"sampleRate\":$sampleRate," +
        "\"bitsPerSample\":$bitsPerSample,\"blockAlign\":$blockAlign," +
        "\"encoding\":\"${encoding.name}\",\"channelMask\":${channelMask ?: "null"}}"

private fun String.jsonEscape(): String = buildString(length) {
    for (character in this@jsonEscape) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04X".format(character.code)) else append(character)
        }
    }
}
