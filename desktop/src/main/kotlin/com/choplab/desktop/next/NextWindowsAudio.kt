package com.choplab.desktop.next

import com.choplab.desktop.audio.wasapi.*
import com.choplab.jvm.MicInput
import com.choplab.jvm.PcmMemoryBudget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class NextAudioRoute { WASAPI, JAVA_SOUND }

/** Host-private route choice. Opens inputs only when an explicit capture action asks for one. */
internal class NextWindowsAudio(
    private val streams: WasapiStreams = createWasapiStreams(),
    private val javaOutput: () -> AudioSink = JavaSoundSink::open,
    private val javaInput: () -> MicInput? = JavaSoundMicInput::open,
    private val memory: PcmMemoryBudget = PcmMemoryBudget.shared,
) : AutoCloseable {
    private val selected = MutableStateFlow(NextAudioRoute.WASAPI)
    val route = selected.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val gate = Any()
    private var openingInputs = 0
    private var activeInputs = 0
    private var changing = false
    @Volatile private var output: WasapiAudioSink? = null
    @Volatile private var microphone: WasapiMicInput? = null
    @Volatile private var loopback: WasapiMicInput? = null
    private val pendingReleases = java.util.concurrent.ConcurrentHashMap<WasapiStreamMode, WasapiRelease>()
    private val captureFailures = java.util.concurrent.ConcurrentHashMap<WasapiStreamMode, WasapiFault>()
    val loopbackFailure: WasapiFault? get() = captureFailures[WasapiStreamMode.LOOPBACK]

    fun openOutput(): AudioSink {
        check(!closed.get())
        if (route.value == NextAudioRoute.JAVA_SOUND) return javaOutput()
        return runBlocking {
            when (val result = streams.openOutput()) {
                is WasapiOpen.Ready -> result.stream.also { output = it }
                is WasapiOpen.Unavailable -> { pendingReleases[WasapiStreamMode.OUTPUT] = result.release; throw WasapiStreamException(result.failure) }
            }
        }
    }

    fun openMicrophone(): MicInput? = capture {
        if (route.value == NextAudioRoute.JAVA_SOUND) return@capture javaInput()
        runBlocking {
            val reservation = memory.reserve(2048L * 2 * 4)
            try {
                val input = openCapture(WasapiStreamMode.MICROPHONE).also { microphone = it }
                try { MonoInput(input, reservation) } catch (failure: Throwable) { input.requestClose(); throw failure }
            } catch (failure: Throwable) { reservation.close(); throw failure }
        }
    }

    fun openLoopback(): MicInput = checkNotNull(capture {
        runBlocking {
            captureFailures.remove(WasapiStreamMode.LOOPBACK)
            check(route.value == NextAudioRoute.WASAPI)
            openCapture(WasapiStreamMode.LOOPBACK).also { loopback = it }
        }
    })

    private fun capture(open: () -> MicInput?): MicInput? {
        synchronized(gate) { check(!closed.get() && !changing); openingInputs++ }
        return try {
            open()?.let { input ->
                synchronized(gate) { activeInputs++ }
                object : MicInput {
                    private val released = AtomicBoolean(false)
                    override val sampleRate get() = input.sampleRate
                    override val channels get() = input.channels
                    override fun onCaptureThread() = input.onCaptureThread()
                    override fun read(buffer: FloatArray) = input.read(buffer)
                    override fun stop() = input.stop()
                    override fun close() { if (released.compareAndSet(false, true)) try { input.close() }
                        finally { synchronized(gate) { activeInputs-- } } }
                }
            }
        } finally { synchronized(gate) { openingInputs-- } }
    }

    private suspend fun openCapture(mode: WasapiStreamMode): WasapiMicInput = when (val result = streams.openInput(mode)) {
        is WasapiOpen.Ready -> result.stream.also { captureFailures.remove(mode) }
        is WasapiOpen.Unavailable -> { pendingReleases[mode] = result.release; captureFailures[mode] = result.failure.fault; throw WasapiStreamException(result.failure) }
    }

    /** Call only after the driver has released output and all capture actions have ended. No automatic fallback. */
    suspend fun select(next: NextAudioRoute, releaseOutput: suspend () -> Boolean = { true }): Boolean {
        synchronized(gate) {
            if (closed.get() || changing || openingInputs != 0 || activeInputs != 0) return false
            changing = true
        }
        try {
        if (!releaseOutput()) return false
        // Do not truncate a live input, even if a host's stale state says recording has ended.
        if (microphone?.status()?.phase == WasapiStreamPhase.RUNNING || loopback?.status()?.phase == WasapiStreamPhase.RUNNING) return false
        if (output?.status()?.phase == WasapiStreamPhase.RUNNING) return false
        val releases = listOfNotNull(output?.requestClose(), microphone?.requestClose(), loopback?.requestClose()) + pendingReleases.values
        for (release in releases) if (!release.await()) return false
        currentCoroutineContext().ensureActive()
        if (closed.get()) return false
        output = null; microphone = null; loopback = null; pendingReleases.clear()
        selected.value = next
        return true
        } finally { synchronized(gate) { changing = false } }
    }

    override fun close() { if (closed.compareAndSet(false, true)) streams.close() }

    /** Preserve both native channels until the explicit vocal mono downmix, with bounded shared PCM ownership. */
    private class MonoInput(private val input: MicInput, private val reservation: PcmMemoryBudget.Reservation) : MicInput {
        private val samples = FloatArray(4096)
        private val closed = AtomicBoolean(false)
        override val sampleRate get() = input.sampleRate
        override fun onCaptureThread() = input.onCaptureThread()
        override fun read(buffer: FloatArray): Int {
            require(buffer.size >= 2048)
            val count = input.read(samples)
            if (count <= 0) return count
            require(count <= samples.size && count % 2 == 0)
            for (i in 0 until count / 2) buffer[i] = (samples[i * 2] + samples[i * 2 + 1]) * .5f
            return count / 2
        }
        override fun stop() = input.stop()
        override fun close() { if (closed.compareAndSet(false, true)) try { input.close() } finally { reservation.close() } }
    }
}

/** Ordinary Windows endpoint mix only; the SOURCE capture action owns the stereo take and its private scratch. */
internal class NextWasapiSystemAudioCapture(assets: com.choplab.jvm.FileAssetStore, scratch: java.nio.file.Path,
    private val audio: NextWindowsAudio) : com.choplab.ui.SystemAudioCapture {
    private val takes = com.choplab.jvm.VoiceTakes(assets, scratch, captureChannels = 2, microphone = audio::openLoopback)
    @Volatile private var opening: Job? = null
    override suspend fun start(maxSeconds: Int): com.choplab.ui.SystemAudioCapture.Start {
        opening = currentCoroutineContext()[Job]
        return try { when (takes.start(maxSeconds)) {
            com.choplab.jvm.VoiceTakes.Start.STARTED -> com.choplab.ui.SystemAudioCapture.Start.STARTED
            com.choplab.jvm.VoiceTakes.Start.NO_ROOM -> com.choplab.ui.SystemAudioCapture.Start.NO_ROOM
            com.choplab.jvm.VoiceTakes.Start.NO_INPUT -> when (audio.loopbackFailure) {
                WasapiFault.ACCESS_DENIED -> com.choplab.ui.SystemAudioCapture.Start.DENIED
                WasapiFault.OPEN_TIMEOUT, WasapiFault.EVENT_TIMEOUT -> com.choplab.ui.SystemAudioCapture.Start.TIMEOUT
                WasapiFault.MEMORY_LIMIT -> com.choplab.ui.SystemAudioCapture.Start.NO_ROOM
                else -> com.choplab.ui.SystemAudioCapture.Start.UNAVAILABLE
            }
        } } finally { opening = null }
    }
    override fun cancelOpening() { opening?.cancel() }
    override val full get() = takes.full
    override val interrupted get() = takes.interrupted
    override val recordedMillis get() = takes.recordedMillis
    override suspend fun stop(name: String) = takes.stop(name)?.asset
    override suspend fun discard() = takes.discard()
    override suspend fun close() = takes.close()
}
