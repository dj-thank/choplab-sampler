package com.choplab.desktop.audio.wasapi

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

internal class MmDeviceEnumerator(pointer: Pointer) : WasapiComObject(pointer) {
    fun defaultEndpoint(flow: EndpointFlow, role: EndpointRole): MmDevice {
        val output = PointerByReference()
        checkHResult(
            "IMMDeviceEnumerator::GetDefaultAudioEndpoint(${flow.name},${role.name})",
            invokeHResult(4, flow.nativeValue, role.nativeValue, output),
        )
        return MmDevice(requireNotNull(output.value) { "Default ${flow.name} endpoint pointer is null" })
    }

    fun endpoints(flow: EndpointFlow, stateMask: Int): List<MmDevice> {
        val output = PointerByReference()
        checkHResult(
            "IMMDeviceEnumerator::EnumAudioEndpoints(${flow.name})",
            invokeHResult(3, flow.nativeValue, stateMask, output),
        )
        val collection = MmDeviceCollection(requireNotNull(output.value) { "MMDeviceCollection pointer is null" })
        val devices = mutableListOf<MmDevice>()
        return try {
            repeat(collection.count()) { index -> devices += collection.item(index) }
            devices
        } catch (failure: Exception) {
            devices.forEach(MmDevice::Release)
            throw failure
        } finally {
            collection.Release()
        }
    }

    fun endpointCount(flow: EndpointFlow, stateMask: Int): Int {
        val endpoints = endpoints(flow, stateMask)
        return endpoints.size.also { endpoints.forEach(MmDevice::Release) }
    }
}

internal class MmDeviceCollection(pointer: Pointer) : WasapiComObject(pointer) {
    fun count(): Int {
        val output = IntByReference()
        checkHResult("IMMDeviceCollection::GetCount", invokeHResult(3, output))
        return output.value.also { count ->
            require(count in 0..256) { "MMDeviceCollection count is out of bounds: $count" }
        }
    }

    fun item(index: Int): MmDevice {
        require(index in 0 until 256) { "MMDeviceCollection index is out of bounds" }
        val output = PointerByReference()
        checkHResult("IMMDeviceCollection::Item($index)", invokeHResult(4, index, output))
        return MmDevice(requireNotNull(output.value) { "MMDevice pointer is null" })
    }
}

internal class MmDevice(pointer: Pointer) : WasapiComObject(pointer) {
    fun activateAudioClient(): AudioClient {
        val output = PointerByReference()
        checkHResult(
            "IMMDevice::Activate(IAudioClient)",
            invokeHResult(3, Guid.REFIID(IID_AUDIO_CLIENT), CLSCTX_ALL, Pointer.NULL, output),
        )
        return AudioClient(requireNotNull(output.value) { "IAudioClient pointer is null" })
    }

    fun endpointId(): String {
        val output = PointerByReference()
        checkHResult("IMMDevice::GetId", invokeHResult(5, output))
        val value = requireNotNull(output.value) { "Endpoint ID pointer is null" }
        return try {
            value.getWideString(0)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(value)
        }
    }

    fun state(): Int {
        val output = IntByReference()
        checkHResult("IMMDevice::GetState", invokeHResult(6, output))
        return output.value
    }
}

internal class AudioClient(pointer: Pointer) : WasapiComObject(pointer) {
    fun mixFormat(): WaveFormat {
        val output = PointerByReference()
        checkHResult("IAudioClient::GetMixFormat", invokeHResult(8, output))
        val value = requireNotNull(output.value) { "WASAPI mix format pointer is null" }
        return try {
            WaveFormat.read(value)
        } finally {
            Ole32.INSTANCE.CoTaskMemFree(value)
        }
    }

    fun devicePeriod(): DevicePeriod {
        val defaultPeriod = LongByReference()
        val minimumPeriod = LongByReference()
        checkHResult(
            "IAudioClient::GetDevicePeriod",
            invokeHResult(9, defaultPeriod, minimumPeriod),
        )
        return DevicePeriod(defaultPeriod.value, minimumPeriod.value)
    }
}

internal fun createDeviceEnumerator(): MmDeviceEnumerator {
    val output = PointerByReference()
    checkHResult(
        "CoCreateInstance(MMDeviceEnumerator)",
        Ole32.INSTANCE.CoCreateInstance(
            CLSID_MM_DEVICE_ENUMERATOR,
            Pointer.NULL,
            CLSCTX_ALL,
            IID_MM_DEVICE_ENUMERATOR,
            output,
        ),
    )
    return MmDeviceEnumerator(requireNotNull(output.value) { "MMDeviceEnumerator pointer is null" })
}

enum class EndpointFlow(internal val nativeValue: Int) {
    RENDER(E_RENDER),
    CAPTURE(E_CAPTURE),
}

enum class EndpointRole(internal val nativeValue: Int) {
    MULTIMEDIA(E_MULTIMEDIA),
    CONSOLE(E_CONSOLE),
    COMMUNICATIONS(E_COMMUNICATIONS),
}

data class DevicePeriod(
    val defaultReferenceTime100ns: Long,
    val minimumReferenceTime100ns: Long,
) {
    init {
        require(defaultReferenceTime100ns in 1L..100_000_000L) { "Default WASAPI device period is invalid" }
        require(minimumReferenceTime100ns in 1L..defaultReferenceTime100ns) { "Minimum WASAPI device period is invalid" }
    }

    val defaultMicros: Long
        get() = defaultReferenceTime100ns / 10L

    val minimumMicros: Long
        get() = minimumReferenceTime100ns / 10L
}
