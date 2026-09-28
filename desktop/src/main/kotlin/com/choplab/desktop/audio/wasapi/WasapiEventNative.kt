package com.choplab.desktop.audio.wasapi

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

internal val IID_AUDIO_RENDER_CLIENT = Guid.IID("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}")
internal val IID_AUDIO_CAPTURE_CLIENT = Guid.IID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")
internal const val WASAPI_EVENT_FLAGS = 0x880C0000.toInt() // EVENTCALLBACK | NOPERSIST | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY
internal const val WASAPI_LOOPBACK_FLAG = 0x00020000

/** Testable vtable boundary. Arguments exclude the interface pointer; WasapiComObject prepends it. */
internal fun interface WasapiComCall {
    fun invoke(index: Int, arguments: Array<out Any?>): Int
}
private class EventComObject(pointer: Pointer) : WasapiComObject(pointer), WasapiComCall {
    override fun invoke(index: Int, arguments: Array<out Any?>): Int = invokeHResult(index, *arguments).toInt()
}

/** IAudioClient vtable entries, sizes and flags are tested independently of the Windows device. */
internal class WasapiClientCalls(private val calls: WasapiComCall) {
    private val frames = IntByReference()

    fun initialize(mode: WasapiStreamMode, format: Pointer) {
        val flags = WASAPI_EVENT_FLAGS or if (mode == WasapiStreamMode.LOOPBACK) WASAPI_LOOPBACK_FLAG else 0
        call("Initialize", 3, 0, flags, 0L, 0L, format, Pointer.NULL)
    }
    fun bufferFrames(): Int {
        call("GetBufferSize", 4, frames)
        return frames.value.also { boundedFrames(it, allowZero = false) }
    }
    fun paddingFrames(): Int {
        call("GetCurrentPadding", 6, frames)
        return frames.value.also { boundedFrames(it, allowZero = true) }
    }
    fun setEvent(event: WinNT.HANDLE) = call("SetEventHandle", 13, event)
    fun start() = call("Start", 10)
    fun stop() = call("Stop", 11)
    fun service(mode: WasapiStreamMode): Pointer {
        val result = PointerByReference()
        val iid = if (mode == WasapiStreamMode.OUTPUT) IID_AUDIO_RENDER_CLIENT else IID_AUDIO_CAPTURE_CLIENT
        call("GetService", 14, Guid.REFIID(iid), result)
        return result.value ?: wasapiReject(WasapiStage.OPEN, WasapiFault.INVALID_BUFFER)
    }
    private fun call(name: String, index: Int, vararg args: Any?) =
        checkHResult("IAudioClient::$name", WinNT.HRESULT(calls.invoke(index, args)))
}

internal class WasapiRenderCalls(private val calls: WasapiComCall) {
    private val buffer = PointerByReference()

    fun write(stereo: FloatArray, frames: Int) {
        require(frames in 1..stereo.size / 2)
        checkHResult("IAudioRenderClient::GetBuffer", WinNT.HRESULT(calls.invoke(3, arrayOf(frames, buffer))))
        var copied = false
        try {
            val target = buffer.value ?: wasapiReject(WasapiStage.OUTPUT, WasapiFault.INVALID_BUFFER)
            target.write(0, stereo, 0, frames * 2)
            copied = true
        } finally {
            // Never publish uninitialized memory when a copy fails. The acquisition is paired exactly once.
            val flags = if (copied) 0 else WASAPI_BUFFER_SILENT
            checkHResult("IAudioRenderClient::ReleaseBuffer", WinNT.HRESULT(calls.invoke(4, arrayOf(frames, flags))))
        }
    }
}

internal class WasapiCaptureCalls(private val calls: WasapiComCall) {
    private val buffer = PointerByReference()
    private val frames = IntByReference()
    private val flags = IntByReference()
    private val devicePosition = LongByReference()
    private val qpc = LongByReference()

    fun read(stereo: FloatArray, packet: WasapiPacket): Int {
        checkHResult("IAudioCaptureClient::GetNextPacketSize", WinNT.HRESULT(calls.invoke(5, arrayOf(frames))))
        if (frames.value == 0) return 0
        boundedFrames(frames.value, allowZero = false)
        frames.value = 0
        val hr = calls.invoke(3, arrayOf(buffer, frames, flags, devicePosition, qpc))
        checkHResult("IAudioCaptureClient::GetBuffer", WinNT.HRESULT(hr))
        // AUDCLNT_S_BUFFER_EMPTY does not initialize the pointer or the timestamps.
        if (hr == 0x08890001 || frames.value == 0) return 0
        val count = frames.value
        try {
            boundedFrames(count, allowZero = false)
            if (count > stereo.size / 2) wasapiReject(WasapiStage.INPUT, WasapiFault.INVALID_BUFFER)
            packet.flags = flags.value
            packet.firstFrame = devicePosition.value
            packet.qpc100ns = qpc.value
            if (flags.value and WASAPI_BUFFER_SILENT != 0) {
                stereo.fill(0f, 0, count * 2)
            } else {
                val source = buffer.value ?: wasapiReject(WasapiStage.INPUT, WasapiFault.INVALID_BUFFER)
                source.read(0, stereo, 0, count * 2)
            }
            return count
        } finally {
            checkHResult("IAudioCaptureClient::ReleaseBuffer", WinNT.HRESULT(calls.invoke(4, arrayOf(count))))
        }
    }
}

/** Native format memory is not PCM. A 40-byte extensible format retains explicit FL/FR channel identity. */
internal fun wasapiClientFormat(): Memory = Memory(40).apply {
    clear()
    setShort(0, WAVE_FORMAT_EXTENSIBLE.toShort())
    setShort(2, 2)
    setInt(4, WASAPI_CLIENT_RATE)
    setInt(8, WASAPI_CLIENT_RATE * WASAPI_FRAME_BYTES)
    setShort(12, WASAPI_FRAME_BYTES.toShort())
    setShort(14, 32)
    setShort(16, 22)
    setShort(18, 32)
    setInt(20, 3) // SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT
    val guid = Guid.GUID(KSDATAFORMAT_SUBTYPE_IEEE_FLOAT)
    guid.write()
    write(24, guid.pointer.getByteArray(0, 16), 0, 16)
}

internal fun boundedFrames(frames: Int, allowZero: Boolean) {
    if (frames !in (if (allowZero) 0 else 1)..WASAPI_MAX_BUFFER_FRAMES) {
        wasapiReject(WasapiStage.OPEN, WasapiFault.INVALID_BUFFER)
    }
}

/**
 * Only the explicit opener touches Windows. No output/microphone/loopback is started by class loading or probing.
 * This uses the ordinary shared render mix for loopback; protected content remains controlled by Windows.
 */
internal class JnaWasapiEventApi : WasapiNativeApi {
    override fun open(mode: WasapiStreamMode): WasapiNativeStream {
        if (!isWindows()) wasapiReject(WasapiStage.OPEN, WasapiFault.NOT_WINDOWS)
        if (mode == WasapiStreamMode.LOOPBACK && !supportsEventLoopback()) {
            wasapiReject(WasapiStage.OPEN, WasapiFault.LOOPBACK_UNSUPPORTED)
        }
        checkHResult("CoInitializeEx(STA)", Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED))
        var enumerator: MmDeviceEnumerator? = null
        var device: MmDevice? = null
        var client: AudioClient? = null
        var service: EventComObject? = null
        var event: WinNT.HANDLE? = null
        var transferred = false
        try {
            enumerator = createDeviceEnumerator()
            val flow = if (mode == WasapiStreamMode.MICROPHONE) EndpointFlow.CAPTURE else EndpointFlow.RENDER
            // Selecting another active endpoint silently could capture a different microphone.
            device = enumerator.defaultEndpoint(flow, EndpointRole.MULTIMEDIA)
            if (device.state() and DEVICE_STATE_ACTIVE == 0) wasapiReject(WasapiStage.OPEN, WasapiFault.NO_ENDPOINT)
            client = device.activateAudioClient()
            val mix = client.mixFormat()
            if (mix.encoding == WaveEncoding.UNKNOWN) wasapiReject(WasapiStage.OPEN, WasapiFault.UNSUPPORTED_FORMAT)
            val calls = WasapiClientCalls(EventComObject(client.pointer))
            // Create the event before Initialize, so every successful event initialization can set it immediately.
            event = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
                ?: throw windowsFailure("CreateEvent")
            wasapiClientFormat().use { calls.initialize(mode, it) }
            calls.setEvent(event)
            val count = calls.bufferFrames()
            service = EventComObject(calls.service(mode))
            val result = NativeEventStream(mode, mix, count, client, calls, service, event)
            transferred = true
            return result
        } finally {
            // These selection references are not used during streaming.
            try { device?.Release() } finally {
                try { enumerator?.Release() } finally {
                    if (!transferred) {
                        try { service?.Release() } finally {
                            try { client?.Release() } finally {
                                try { event?.let { Kernel32.INSTANCE.CloseHandle(it) } }
                                finally { Ole32.INSTANCE.CoUninitialize() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun supportsEventLoopback(): Boolean {
        // The OS build is needed for the documented Windows 10 1703 event-loopback boundary. GetVersionEx can
        // report the application's manifest compatibility version instead of the installed OS version.
        val function = NativeLibrary.getInstance("ntdll").getFunction("RtlGetVersion", Function.ALT_CONVENTION)
        Memory(284).use { version ->
            version.clear()
            version.setInt(0, 284) // RTL_OSVERSIONINFOEXW, including its 8-byte extended tail.
            if (function.invokeInt(arrayOf(version)) != 0) wasapiReject(WasapiStage.OPEN, WasapiFault.NATIVE_FAILURE)
            return eventLoopbackSupported(version.getInt(4), version.getInt(12))
        }
    }
}

internal fun eventLoopbackSupported(major: Int, build: Int): Boolean = major > 10 || (major == 10 && build >= 15_063)

private class NativeEventStream(
    mode: WasapiStreamMode,
    override val mixFormat: WaveFormat,
    override val bufferFrames: Int,
    private val client: AudioClient,
    private val calls: WasapiClientCalls,
    private val service: EventComObject,
    private val event: WinNT.HANDLE,
) : WasapiNativeStream {
    private val owner = Thread.currentThread()
    private val render = if (mode == WasapiStreamMode.OUTPUT) WasapiRenderCalls(service) else null
    private val capture = if (mode != WasapiStreamMode.OUTPUT) WasapiCaptureCalls(service) else null
    private var started = false
    private var closed = false

    override fun start() { owned(); calls.start(); started = true }
    override fun awaitEvent(timeoutMillis: Int): Boolean {
        owned()
        require(timeoutMillis in 1..WASAPI_EVENT_WAIT_MILLIS)
        return when (Kernel32.INSTANCE.WaitForSingleObject(event, timeoutMillis)) {
            0 -> true
            258 -> false // WAIT_TIMEOUT; silence on a loopback route is not a capture failure.
            else -> throw windowsFailure("WaitForSingleObject")
        }
    }
    override fun paddingFrames(): Int { owned(); return calls.paddingFrames() }
    override fun render(stereo: FloatArray, frames: Int) { owned(); checkNotNull(render).write(stereo, frames) }
    override fun capture(stereo: FloatArray, packet: WasapiPacket): Int {
        owned()
        return checkNotNull(capture).read(stereo, packet)
    }
    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        try { if (started) calls.stop() } finally {
            try { service.Release() } finally {
                try { client.Release() } finally {
                    try {
                        if (!Kernel32.INSTANCE.CloseHandle(event)) throw windowsFailure("CloseHandle")
                    } finally { Ole32.INSTANCE.CoUninitialize() }
                }
            }
        }
    }
    private fun owned() { check(Thread.currentThread() === owner && !closed) }
}

private fun windowsFailure(operation: String): WasapiException =
    WasapiException(operation, 0x80070000.toInt() or (Kernel32.INSTANCE.GetLastError() and 0xffff))
