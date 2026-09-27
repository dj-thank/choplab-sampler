package com.choplab.desktop.audio.wasapi

import com.choplab.jvm.AudioSink
import com.choplab.jvm.MicInput
import com.choplab.jvm.SinkEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport

/**
 * Explicit shared-mode native entry point. One process-wide owner per mode bounds even a stuck COM open/close.
 * Calling code must obtain the user's microphone/system-mix permission before openInput. There is no background
 * capture, implicit fallback, route retry, device enablement or capture-policy bypass.
 *
 * All open methods are for a worker coroutine. A synchronous platform opener may use runBlocking with its Job;
 * EngineCore.render must never open, close, or reserve memory. Host wiring supplies the shared PCM ledger.
 */
class WasapiStreams internal constructor(
    private val memory: WasapiPcmMemory,
    private val native: WasapiNativeApi,
    private val slots: WasapiSlots,
) : AutoCloseable {
    constructor(memory: WasapiPcmMemory) : this(memory, JnaWasapiEventApi(), processSlots)

    private val closed = AtomicBoolean(false)
    private val owned = AtomicReferenceArray<WasapiOwner?>(WasapiStreamMode.entries.size)

    suspend fun openOutput(timeoutMillis: Long = 5_000): WasapiOpen<WasapiAudioSink> =
        when (val result = open(WasapiStreamMode.OUTPUT, timeoutMillis)) {
            is WasapiOpen.Ready -> WasapiOpen.Ready(WasapiAudioSink(result.stream))
            is WasapiOpen.Unavailable -> result
        }

    suspend fun openInput(mode: WasapiStreamMode, timeoutMillis: Long = 5_000): WasapiOpen<WasapiMicInput> {
        require(mode != WasapiStreamMode.OUTPUT)
        return when (val result = open(mode, timeoutMillis)) {
            is WasapiOpen.Ready -> WasapiOpen.Ready(WasapiMicInput(result.stream))
            is WasapiOpen.Unavailable -> result
        }
    }

    private suspend fun open(mode: WasapiStreamMode, timeoutMillis: Long): WasapiOpen<WasapiOwner> {
        require(timeoutMillis in 1..30_000)
        currentCoroutineContext().ensureActive()
        if (closed.get()) return unavailable(WasapiFault.CLOSED)
        val owner = WasapiOwner(mode, memory, native) { completed ->
            owned.compareAndSet(mode.ordinal, completed, null)
            slots.release(mode, completed)
        }
        if (!slots.claim(mode, owner)) return unavailable(WasapiFault.BUSY, slots.releaseOf(mode))
        owned.set(mode.ordinal, owner)
        if (closed.get()) owner.requestStop()
        owner.startWorker()
        var adopted = false
        try {
            val started = withTimeoutOrNull(timeoutMillis) { owner.ready.await() }
            currentCoroutineContext().ensureActive()
            if (started == null) {
                owner.fail(WasapiFailure(WasapiStage.OPEN, WasapiFault.OPEN_TIMEOUT))
                return WasapiOpen.Unavailable(owner.failure!!, owner.release)
            }
            if (!started || owner.stopping) {
                return WasapiOpen.Unavailable(owner.failure ?: WasapiFailure(WasapiStage.OPEN, WasapiFault.CLOSED), owner.release)
            }
            adopted = true
            return WasapiOpen.Ready(owner)
        } finally {
            if (!adopted) owner.requestStop()
        }
    }

    override fun close() {
        closed.set(true)
        val owners = WasapiStreamMode.entries.mapNotNull { owned.get(it.ordinal) }
        owners.forEach { it.requestStop() }
        val deadline = System.nanoTime() + 2_000_000_000L
        var pending = false
        for (owner in owners) {
            val left = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            if (!owner.awaitClosed(left)) pending = true
        }
        if (pending) wasapiReject(WasapiStage.CLOSE, WasapiFault.CLOSE_PENDING)
    }

    private fun unavailable(fault: WasapiFault, release: WasapiRelease = WasapiRelease.completed()) =
        WasapiOpen.Unavailable(WasapiFailure(WasapiStage.OPEN, fault), release)

    private companion object { val processSlots = WasapiSlots() }
}

internal class WasapiSlots {
    private val owners = AtomicReferenceArray<WasapiOwner?>(WasapiStreamMode.entries.size)
    fun claim(mode: WasapiStreamMode, owner: WasapiOwner): Boolean = owners.compareAndSet(mode.ordinal, null, owner)
    fun release(mode: WasapiStreamMode, owner: WasapiOwner) { owners.compareAndSet(mode.ordinal, owner, null) }
    fun releaseOf(mode: WasapiStreamMode): WasapiRelease = owners.get(mode.ordinal)?.release ?: WasapiRelease.completed()
}

/** write only enqueues float32 bytes; COM calls never enter the engine's render/write owner. */
class WasapiAudioSink internal constructor(private val owner: WasapiOwner) : AudioSink {
    override val encoding: SinkEncoding get() = SinkEncoding.FLOAT32
    override fun write(bytes: ByteArray, offset: Int, length: Int): Int = owner.write(bytes, offset, length)
    override fun bufferFrames(): Int = owner.deviceBufferFrames
    override fun pendingFrames(): Long = owner.pendingFrames()
    // The software starvation counter in status is not a measured hardware-underrun counter.
    override fun underruns(): Int = -1
    fun status(): WasapiStreamStatus = owner.status()
    fun requestClose(): WasapiRelease { owner.requestStop(); return owner.release }
    fun closeWithin(timeoutMillis: Long): Boolean { owner.requestStop(); return owner.awaitClosed(timeoutMillis) }
    override fun close() {
        if (!closeWithin(2_000)) wasapiReject(WasapiStage.CLOSE, WasapiFault.CLOSE_PENDING)
    }
}

class WasapiMicInput internal constructor(private val owner: WasapiOwner) : MicInput {
    override val sampleRate: Int get() = WASAPI_CLIENT_RATE
    override val channels: Int get() = WASAPI_CLIENT_CHANNELS
    override fun read(buffer: FloatArray): Int = owner.read(buffer)
    override fun stop() = owner.requestStop()
    fun status(): WasapiStreamStatus = owner.status()
    fun requestClose(): WasapiRelease { owner.requestStop(); return owner.release }
    fun closeWithin(timeoutMillis: Long): Boolean { owner.requestStop(); return owner.awaitClosed(timeoutMillis) }
    override fun close() {
        if (!closeWithin(2_000)) wasapiReject(WasapiStage.CLOSE, WasapiFault.CLOSE_PENDING)
    }
}

internal class WasapiOwner(
    private val mode: WasapiStreamMode,
    private val memory: WasapiPcmMemory,
    private val native: WasapiNativeApi,
    private val onReleased: (WasapiOwner) -> Unit,
) {
    val ready = CompletableDeferred<Boolean>()
    private val finished = CompletableDeferred<Unit>()
    val release = WasapiRelease(finished)
    private val stop = AtomicBoolean(false)
    private val fault = AtomicReference<WasapiFailure?>(null)
    private val reservationJob = Job()
    private val users = AtomicInteger(0)
    private val reader = AtomicReference<Thread?>(null)
    private val writer = AtomicReference<Thread?>(null)
    @Volatile private var ring: WasapiPcmRing? = null
    @Volatile private var phase = WasapiStreamPhase.OPENING
    @Volatile private var format: WaveFormat? = null
    @Volatile var deviceBufferFrames = 0
        private set
    @Volatile private var padding = 0
    @Volatile private var starvation = 0L
    @Volatile private var initialDiscontinuity = false
    // A seqlock publishes four coherent clock fields without allocating in the event worker.
    @Volatile private var clockSequence = 0L
    private var clockFrame = 0L
    private var clockQpc = 0L
    private var clockFrames = 0
    private var captured = 0L
    val failure: WasapiFailure? get() = fault.get()
    val stopping: Boolean get() = stop.get()
    private val worker = Thread(::work, "ChopLab-WASAPI-${mode.name}-STA").apply { isDaemon = true }

    fun startWorker() {
        try { worker.start() } catch (failure: Throwable) {
            // No native work or PCM allocation exists when Thread.start itself fails.
            fault.compareAndSet(null, wasapiFailure(WasapiStage.OPEN, failure))
            stop.set(true)
            reservationJob.cancel()
            phase = WasapiStreamPhase.CLOSED
            ready.complete(false)
            onReleased(this)
            finished.complete(Unit)
        }
    }
    fun requestStop() {
        stop.set(true)
        if (phase != WasapiStreamPhase.CLOSED) phase = WasapiStreamPhase.STOPPING
        reservationJob.cancel()
        // Native waits are bounded to 25ms. No foreign-thread COM/handle destruction or Thread.stop is used.
        LockSupport.unpark(reader.get())
    }
    fun fail(value: WasapiFailure) { fault.compareAndSet(null, value); requestStop() }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        try { worker.join(timeoutMillis) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return finished.isCompleted
    }

    fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val thread = Thread.currentThread()
        writer.compareAndSet(null, thread)
        check(writer.get() === thread) { "WASAPI output has one producer" }
        users.incrementAndGet()
        try {
            throwIfStopped()
            return checkNotNull(ring).offerBytes(bytes, offset, length)
        } catch (failure: WasapiStreamException) {
            if (!stopping) fail(failure.failure)
            throw failure
        } finally { users.decrementAndGet() }
    }

    fun read(target: FloatArray): Int {
        require(target.size >= 2)
        val thread = Thread.currentThread()
        reader.compareAndSet(null, thread)
        check(reader.get() === thread) { "WASAPI input has one consumer" }
        while (true) {
            users.incrementAndGet()
            try {
                failure?.let { throw WasapiStreamException(it) }
                if (stopping) return -1
                val frames = ring?.readInto(target, target.size / 2) ?: 0
                if (frames > 0) return frames * 2
            } finally { users.decrementAndGet() }
            if (Thread.currentThread().isInterrupted) { requestStop(); return -1 }
            LockSupport.parkNanos(this, 1_000_000L)
        }
    }

    fun pendingFrames(): Long = padding.toLong() + (ring?.availableFrames ?: 0)
    fun status(): WasapiStreamStatus = WasapiStreamStatus(
        mode, phase, failure, format, deviceBufferFrames, ring?.availableFrames ?: 0,
        padding, starvation, clockSnapshot(), initialDiscontinuity,
    )

    private fun throwIfStopped() {
        failure?.let { throw WasapiStreamException(it) }
        if (stopping) wasapiReject(WasapiStage.OUTPUT, WasapiFault.CLOSED)
    }

    private fun work() {
        var endpoint: WasapiNativeStream? = null
        var ringMemory: WasapiPcmReservation? = null
        var nativeMemory: WasapiPcmReservation? = null
        var scratchMemory: WasapiPcmReservation? = null
        var stage = WasapiStage.MEMORY
        try {
            runBlocking(reservationJob) {
                val maximumRingFrames = if (mode == WasapiStreamMode.OUTPUT) WASAPI_OUTPUT_RING_FRAMES else WASAPI_MAX_BUFFER_FRAMES
                ringMemory = reserve(maximumRingFrames.toLong() * WASAPI_FRAME_BYTES)
                // Initialize allocates before GetBufferSize can tell its exact size. Admit the hard maximum first.
                nativeMemory = reserve(WASAPI_MAX_BUFFER_FRAMES.toLong() * WASAPI_FRAME_BYTES)
                ensureActive()
                stage = WasapiStage.OPEN
                endpoint = native.open(mode)
                val stream = checkNotNull(endpoint)
                if (stopping) return@runBlocking
                boundedFrames(stream.bufferFrames, allowZero = false)
                deviceBufferFrames = stream.bufferFrames
                format = stream.mixFormat
                nativeMemory.shrinkTo(stream.bufferFrames.toLong() * WASAPI_FRAME_BYTES)
                val ringFrames = if (mode == WasapiStreamMode.OUTPUT) WASAPI_OUTPUT_RING_FRAMES
                    else maxOf(WASAPI_INPUT_RING_FRAMES, stream.bufferFrames)
                ringMemory.shrinkTo(ringFrames.toLong() * WASAPI_FRAME_BYTES)
                stage = WasapiStage.MEMORY
                scratchMemory = reserve(stream.bufferFrames.toLong() * WASAPI_FRAME_BYTES)
                ensureActive()
                ring = WasapiPcmRing(ringFrames)
                stage = WasapiStage.START
                runStream(stream) { stage = it }
            }
        } catch (_: CancellationException) {
            if (!stopping) fault.compareAndSet(null, WasapiFailure(stage, WasapiFault.CANCELLED))
        } catch (failure: Throwable) {
            fault.compareAndSet(null, wasapiFailure(stage, failure))
        } finally {
            requestStop()
            ready.complete(false)
            // A blocked native close retains the PCM reservations AND process slot. No caller frees them early.
            try { endpoint?.close() } catch (failure: Throwable) {
                fault.compareAndSet(null, wasapiFailure(WasapiStage.CLOSE, failure))
            } finally {
                while (users.get() != 0) LockSupport.parkNanos(this, 100_000L)
                ring = null
                padding = 0
                try { scratchMemory?.close() } finally {
                    try { ringMemory?.close() } finally {
                        try { nativeMemory?.close() } finally {
                            phase = WasapiStreamPhase.CLOSED
                            onReleased(this)
                            finished.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    private suspend fun reserve(bytes: Long): WasapiPcmReservation = try {
        memory.reserve(bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        wasapiReject(WasapiStage.MEMORY, WasapiFault.MEMORY_LIMIT)
    }

    private fun runStream(stream: WasapiNativeStream, setStage: (WasapiStage) -> Unit) {
        val scratch = FloatArray(stream.bufferFrames * 2)
        val queue = checkNotNull(ring)
        val packet = WasapiPacket()
        if (mode == WasapiStreamMode.OUTPUT) {
            // Prime before Start, so the first queued engine block does not depend on a lucky startup callback.
            stream.render(scratch, stream.bufferFrames)
            padding = stream.bufferFrames
        }
        if (stopping) return
        stream.start()
        if (stopping) return
        phase = WasapiStreamPhase.RUNNING
        ready.complete(true)
        var lastEvent = System.nanoTime()
        var lastHealth = lastEvent
        var received = 0L
        var expectedFrame = -1L
        var lastQpc = -1L
        while (!stopping) {
            setStage(WasapiStage.WAIT)
            val signalled = stream.awaitEvent(WASAPI_EVENT_WAIT_MILLIS)
            if (stopping) return
            val now = System.nanoTime()
            if (!signalled) {
                // An idle loopback legitimately has no packets; still notice route invalidation while silent.
                if (now - lastHealth >= 250_000_000L) {
                    padding = checkedPadding(stream)
                    lastHealth = now
                }
                if (mode == WasapiStreamMode.OUTPUT && now - lastEvent >= 2_000_000_000L) {
                    wasapiReject(WasapiStage.WAIT, WasapiFault.EVENT_TIMEOUT)
                }
                continue
            }
            lastEvent = now
            if (mode == WasapiStreamMode.OUTPUT) {
                setStage(WasapiStage.OUTPUT)
                val current = checkedPadding(stream)
                padding = current
                val writable = stream.bufferFrames - current
                if (writable > 0) {
                    val frames = queue.readInto(scratch, writable)
                    scratch.fill(0f, frames * 2, writable * 2)
                    stream.render(scratch, writable)
                    padding = current + writable
                    starvation += writable - frames
                }
            } else {
                setStage(WasapiStage.INPUT)
                // Drain all available packets. Stop is checked between packets even if a producer never empties.
                while (!stopping) {
                    val frames = stream.capture(scratch, packet)
                    if (frames == 0) break
                    if (frames !in 1..stream.bufferFrames) wasapiReject(WasapiStage.INPUT, WasapiFault.INVALID_BUFFER)
                    if (packet.flags and WASAPI_BUFFER_TIMESTAMP_ERROR != 0 || packet.firstFrame < 0 || packet.qpc100ns < 0 ||
                        (lastQpc >= 0 && packet.qpc100ns < lastQpc)) {
                        wasapiReject(WasapiStage.INPUT, WasapiFault.INPUT_TIMESTAMP_ERROR)
                    }
                    if (received > 0 && (packet.flags and WASAPI_BUFFER_DISCONTINUITY != 0 || packet.firstFrame != expectedFrame)) {
                        wasapiReject(WasapiStage.INPUT, WasapiFault.INPUT_DISCONTINUITY)
                    }
                    if (received == 0L) initialDiscontinuity = packet.flags and WASAPI_BUFFER_DISCONTINUITY != 0
                    for (sample in 0 until frames * 2) {
                        if (!scratch[sample].isFinite()) wasapiReject(WasapiStage.INPUT, WasapiFault.NON_FINITE_PCM)
                    }
                    if (!queue.offerPacket(scratch, frames)) wasapiReject(WasapiStage.INPUT, WasapiFault.INPUT_OVERRUN)
                    received += frames
                    expectedFrame = Math.addExact(packet.firstFrame, frames.toLong())
                    lastQpc = packet.qpc100ns
                    publishClock(packet, frames, received)
                    LockSupport.unpark(reader.get())
                }
            }
        }
    }

    private fun checkedPadding(stream: WasapiNativeStream): Int = stream.paddingFrames().also {
        if (it !in 0..stream.bufferFrames) wasapiReject(WasapiStage.WAIT, WasapiFault.INVALID_BUFFER)
    }

    private fun publishClock(packet: WasapiPacket, frames: Int, received: Long) {
        clockSequence++
        clockFrame = packet.firstFrame
        clockQpc = packet.qpc100ns
        clockFrames = frames
        captured = received
        clockSequence++
    }
    private fun clockSnapshot(): WasapiCaptureClock? {
        // A diagnostics read must not spin indefinitely if the event worker is descheduled mid-publication.
        repeat(3) {
            val before = clockSequence
            if (before == 0L) return null
            if (before and 1L != 0L) return@repeat
            val first = clockFrame
            val qpc = clockQpc
            val count = clockFrames
            val total = captured
            if (before == clockSequence) return WasapiCaptureClock(first, qpc, count, total)
        }
        return null
    }
}
