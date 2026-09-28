package com.choplab.desktop.audio.wasapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** No implicit Java Sound or microphone fallback. The host presents the failure and makes that choice explicitly. */
enum class WasapiStreamMode { OUTPUT, MICROPHONE, LOOPBACK }
enum class WasapiStreamPhase { OPENING, RUNNING, STOPPING, CLOSED }
enum class WasapiStage { OPEN, MEMORY, START, WAIT, OUTPUT, INPUT, CLOSE }
enum class WasapiFault {
    NOT_WINDOWS, LOOPBACK_UNSUPPORTED, NO_ENDPOINT, ACCESS_DENIED, UNSUPPORTED_FORMAT,
    DEVICE_LOST, DEVICE_BUSY, SERVICE_UNAVAILABLE, MEMORY_LIMIT, NATIVE_FAILURE,
    INVALID_BUFFER, NON_FINITE_PCM, INPUT_DISCONTINUITY, INPUT_TIMESTAMP_ERROR, INPUT_OVERRUN,
    EVENT_TIMEOUT, OPEN_TIMEOUT, CANCELLED, BUSY, CLOSED, CLOSE_PENDING,
}

/** HRESULT is diagnostic data; no endpoint identifiers, native error messages or recorded samples are retained. */
data class WasapiFailure(val stage: WasapiStage, val fault: WasapiFault, val hresult: Int? = null)
class WasapiStreamException(val failure: WasapiFailure) : IllegalStateException("WASAPI ${failure.stage}: ${failure.fault}")

/**
 * Mandatory admission to the process's PCM ledger, before allocation. In a host this delegates to
 * PcmMemoryBudget.shared.reserve. It runs on the opening worker, never in EngineCore.render or a native callback.
 * A refusal throws; cancellation must propagate. There is deliberately no unlimited/default implementation.
 */
fun interface WasapiPcmMemory {
    suspend fun reserve(bytes: Long): WasapiPcmReservation
}
interface WasapiPcmReservation : AutoCloseable {
    /** Only shrink once the excess allocation is absent. Increasing requires another reservation. */
    fun shrinkTo(bytes: Long)
    override fun close()
}

/** A timed-out/noncooperative native worker retains its slot and memory until its own finally has completed. */
class WasapiRelease internal constructor(private val done: CompletableDeferred<Unit>) {
    val complete: Boolean get() = done.isCompleted
    suspend fun await(timeoutMillis: Long = 2_000): Boolean {
        require(timeoutMillis in 1..30_000)
        return withTimeoutOrNull(timeoutMillis) { done.await(); true } ?: false
    }
    internal companion object {
        fun completed() = WasapiRelease(CompletableDeferred(Unit))
    }
}

sealed interface WasapiOpen<out T> {
    data class Ready<T>(val stream: T) : WasapiOpen<T>
    data class Unavailable(val failure: WasapiFailure, val release: WasapiRelease) : WasapiOpen<Nothing> {
        /** A host may offer its explicit alternate route only after the old native owner is gone. */
        val mayChooseFallback: Boolean get() = release.complete
    }
}

/** Device/QPC positions are an OS clock domain, not System.nanoTime or a measured acoustic input correction. */
data class WasapiCaptureClock(
    val packetFirstFrame: Long,
    val packetQpc100ns: Long,
    val packetFrames: Int,
    val deliveredFrames: Long,
)

data class WasapiStreamStatus(
    val mode: WasapiStreamMode,
    val phase: WasapiStreamPhase,
    val failure: WasapiFailure?,
    val mixFormat: WaveFormat?,
    val bufferFrames: Int,
    val queuedFrames: Int,
    /** Last reported device padding, not an acoustic latency measurement. */
    val devicePaddingFrames: Int,
    val softwareStarvationFrames: Long,
    val captureClock: WasapiCaptureClock?,
    val initialDiscontinuity: Boolean,
) {
    val clientSampleRate: Int get() = WASAPI_CLIENT_RATE
    val clientChannels: Int get() = WASAPI_CLIENT_CHANNELS
    val sharedConversion: Boolean get() = mixFormat?.let {
        it.sampleRate != WASAPI_CLIENT_RATE || it.channels != WASAPI_CLIENT_CHANNELS ||
            it.bitsPerSample != 32 || it.encoding != WaveEncoding.IEEE_FLOAT
    } ?: false
}

internal const val WASAPI_CLIENT_RATE = 48_000
internal const val WASAPI_CLIENT_CHANNELS = 2
internal const val WASAPI_FRAME_BYTES = 8
internal const val WASAPI_MAX_BUFFER_FRAMES = 48_000
internal const val WASAPI_OUTPUT_RING_FRAMES = 4_096
internal const val WASAPI_INPUT_RING_FRAMES = 24_000
internal const val WASAPI_EVENT_WAIT_MILLIS = 25
internal const val WASAPI_BUFFER_DISCONTINUITY = 1
internal const val WASAPI_BUFFER_SILENT = 2
internal const val WASAPI_BUFFER_TIMESTAMP_ERROR = 4

internal fun wasapiFailure(stage: WasapiStage, failure: Throwable): WasapiFailure {
    if (failure is WasapiStreamException) return failure.failure
    val hr = (failure as? WasapiException)?.hresult
    val fault = when (hr) {
        0x80070490u.toInt() -> WasapiFault.NO_ENDPOINT
        0x80070005u.toInt() -> WasapiFault.ACCESS_DENIED
        0x88890008u.toInt() -> WasapiFault.UNSUPPORTED_FORMAT
        0x88890004u.toInt(), 0x88890026u.toInt() -> WasapiFault.DEVICE_LOST
        0x8889000Au.toInt() -> WasapiFault.DEVICE_BUSY
        0x88890010u.toInt() -> WasapiFault.SERVICE_UNAVAILABLE
        else -> WasapiFault.NATIVE_FAILURE
    }
    return WasapiFailure(stage, fault, hr)
}

internal fun wasapiReject(stage: WasapiStage, fault: WasapiFault): Nothing =
    throw WasapiStreamException(WasapiFailure(stage, fault))

/** Every operation, including close and COM release, belongs to the same dedicated STA worker. */
internal fun interface WasapiNativeApi {
    fun open(mode: WasapiStreamMode): WasapiNativeStream
}
internal interface WasapiNativeStream : AutoCloseable {
    val mixFormat: WaveFormat
    val bufferFrames: Int
    fun start()
    fun awaitEvent(timeoutMillis: Int): Boolean
    fun paddingFrames(): Int
    fun render(stereo: FloatArray, frames: Int)
    /** Copies a whole packet, releasing it on this same thread even on failure; zero means no packet. */
    fun capture(stereo: FloatArray, packet: WasapiPacket): Int
    override fun close()
}
internal class WasapiPacket {
    var flags = 0
    var firstFrame = 0L
    var qpc100ns = 0L
}
