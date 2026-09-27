package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport


enum class SinkEncoding(val bytesPerSample: Int) { FLOAT32(4), PCM16(2) }

/**
 * One owner calls write/close. Returning zero expresses bounded backpressure, not success.
 *
 * The reports below are what the platform tells about the device, -1 when it does not say. A diagnostics reader asks
 * for them on its own thread, never the audio owner, so they may run during a write or after close: they must be
 * thread-safe and quick, and may throw once the device is gone.
 */
interface AudioSink : AutoCloseable {
    val encoding: SinkEncoding
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    override fun close()
    /** Frames the device buffer holds. */
    fun bufferFrames(): Int = -1
    /** Times the device ran out of audio since it opened. */
    fun underruns(): Int = -1
    /** Frames written but not yet played. */
    fun pendingFrames(): Long = -1
}

enum class DriverPhase { STARTING, ATTACHED, EDITING_ONLY, CLOSED }
enum class DriverFault { NONE, NO_OUTPUT, WRITE_FAILED, ACK_TIMEOUT, ACK_CANCELLED, EVENT_LOSS }
/**
 * [faults] counts every loss of output since the driver started: a failed device, an engine fault, or no device when
 * output was first wanted or wanted back after a release. A failed attempt to reopen output already lost to a fault
 * is not a new loss. A watcher of the conflated status flow can miss a short reopening between two identical
 * failures; the count still tells it how many losses happened.
 */
data class DriverStatus(val phase: DriverPhase, val encoding: SinkEncoding? = null, val fault: DriverFault = DriverFault.NONE,
                        val faults: Long = 0)
data class DriverReceipt(
    val orderId: Long, val requestedFrame: Long, val appliedFrame: Long, val appliedLate: Boolean,
    val acknowledged: Boolean, val eventLosses: Long,
)
data class DriverPlayback(val fraction: Float = 0f, val elapsedSeconds: Int = 0, val sequenceRenderFrames: Long = 0)
/** What the audio owner last reported: how often it looped, what waits for it, and whether a device is still opening. */
data class DriverDiagnostics(val loops: Long, val queued: Int, val inFlight: Int, val openingDevice: Boolean, val engineFrame: Long)
data class OriginalPlayback(val loaded: Boolean, val playing: Boolean, val sourceFrame: Long, val gain: Float)
/**
 * Output health for a diagnostics readout: formats, times and counts only, never a device name or identifier. Render
 * times cover producing and converting one block with the current device, as a share of that block's duration, over
 * its last [measuredBlocks] blocks. [outputLosses] counts output lost or failing to open ([DriverStatus.faults]).
 * Null where the platform or the current state does not tell.
 */
data class OutputHealth(
    val attached: Boolean,
    val encoding: SinkEncoding?,
    val sampleRate: Int,
    val blockFrames: Int,
    val bufferFrames: Int?,
    val pendingFrames: Long?,
    val underruns: Int?,
    val outputLosses: Long,
    val measuredBlocks: Int,
    val renderP99: Double?,
    val renderMax: Double?,
)

/** Continuous EngineCore output. All sink and EngineCore access belongs to one thread.
 * Control requests allocate on the producer; DSP and PCM conversion reuse fixed buffers.
 */
open class StreamingEnginePort(
    private val compiler: ProgramCompiler,
    private val sinkFactory: () -> AudioSink,
    private val blockFrames: Int = 256,
    private val acknowledgementMillis: Long = 1_000,
) : EnginePort, AutoCloseable {
    private data class EngineView(val engine: EngineCore, val offset: Long)
    private class Pending(val command: EngineCommand, val wireCommand: EngineCommand, val generation: EngineView) {
        val answer = CompletableDeferred<Boolean>()
        @Volatile var cancelled = false
        @Volatile var cancelFault = DriverFault.ACK_CANCELLED
        /** Discarded unapplied because the owner timed it out or rebuilt its engine, not refused on its merits. */
        @Volatile var dropped = false
        var offered = false
    }
    private enum class Outcome { APPLIED, REFUSED, DROPPED }
    private val requests = ConcurrentLinkedQueue<Pending>()
    private val queued = AtomicInteger()
    private val producer = Mutex()
    private var nextWireOrder = 0L
    private val lastClientOrder = longArrayOf(-1L, -1L)
    private val statusValue = MutableStateFlow(DriverStatus(DriverPhase.STARTING))
    val status: StateFlow<DriverStatus> = statusValue.asStateFlow()
    @Volatile private var closed = false
    /** Host lifecycle: false while the editor is hidden and should hold no output device. */
    @Volatile private var outputWanted = true
    /** Set by a device failure; output stays closed until [reattach], so a broken device is never retried in a loop. */
    private val faultLatched = AtomicBoolean(false)
    @Volatile private var requestedMonitorGain = 1f
    @Volatile private var engineView: EngineView? = null
    @Volatile private var confirmedProgram = EngineProgram.EMPTY
    @Volatile private var sequenceStart = 0L
    @Volatile private var stoppedElapsedFrames = 0L
    @Volatile var lastReceipt: DriverReceipt? = null
        private set
    @Volatile private var ownerLoops = 0L
    @Volatile private var ownerInFlight = 0
    @Volatile private var ownerOpening = false
    /**
     * Recent block times in nanoseconds with the current device, written by the owner only and restarted for each
     * device; [renderedBlocks] publishes them. Ints, so a reader never sees half a value on a 32-bit runtime.
     */
    private val renderNanos = IntArray(RENDER_WINDOW)
    @Volatile private var renderedBlocks = 0L
    /** The device in use, for the diagnostics reader to ask about; the owner alone writes to and closes it. */
    @Volatile private var attachedSink: AudioSink? = null
    private val snapshots = ThreadLocal.withInitial { EngineSnapshot() }
    /** Creates devices off the audio owner: a slow device open never holds up edits. The owner writes and closes. */
    private val opener = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-NEXT-device-open").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    private val owner: Thread

    /** Listening only, after EngineCore; does not enter documents or offline export. */
    fun setMonitorGain(gain: Float) {
        require(gain.isFinite() && gain in 0f..1f)
        requestedMonitorGain = gain
    }

    /**
     * After output was lost (route change, focus loss, device or acknowledgement fault) or released by
     * [releaseOutput], ask the audio owner to open a fresh sink. The document stays in Studio and the rebuilt
     * engine keeps the confirmed Program; voices restart silent. Returns false while starting, attached or
     * closed. The outcome arrives in [status]: ATTACHED, or EDITING_ONLY with NO_OUTPUT while the device is
     * still unavailable.
     */
    fun reattach(): Boolean {
        if (closed) return false
        val releasing = !outputWanted
        outputWanted = true
        if (!releasing && statusValue.value.phase != DriverPhase.EDITING_ONLY) return false
        faultLatched.set(false)
        LockSupport.unpark(owner)
        return true
    }

    /**
     * Host lifecycle: close the output device while the editor is hidden, so nothing keeps rendering silence.
     * Voices stop and edits stay usable in EDITING_ONLY without a fault; [reattach] opens a new device.
     */
    fun releaseOutput(): Boolean {
        if (closed) return false
        outputWanted = false
        LockSupport.unpark(owner)
        return true
    }

    init {
        require(blockFrames in 64..2048 && acknowledgementMillis in 50..1_500)
        // Built by the caller, before the audio owner starts: the first engine prepares its interpolation tables, which
        // takes seconds on a slow or interpreted runtime. An edit sent meanwhile would wait for an engine that did not
        // exist yet and be refused, so the driver exists only once its engine does.
        val first = EngineCore()
        engineView = EngineView(first, 0)
        owner = Thread({ runOwner(first) }, "ChopLab-NEXT-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }
    override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram =
        compiler.compile(project, patternId, revision)
    override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram =
        compiler.compile(project, target, revision)

    /**
     * Output trouble (a stalled, frozen or lost device) can drop a command unapplied while the owner rebuilds its
     * engine. An edit's Program or a stop must not be lost to that: they need no device, so they are offered again,
     * a bounded number of times. Studio awaits each apply, so a retry never overtakes a later command.
     */
    override suspend fun apply(command: EngineCommand): Boolean {
        var attempt = 0
        while (true) {
            val outcome = submit(command, 0, retry = attempt > 0)
            if (outcome != Outcome.DROPPED || !command.needsNoDevice() || ++attempt >= DROPPED_ATTEMPTS || closed) return outcome == Outcome.APPLIED
        }
    }

    /** A separate logical client may control monitoring, never overwrite the document program. */
    suspend fun applyMonitoring(command: EngineCommand): Boolean {
        require(command is EngineCommand.OriginalSourceCommand || command is EngineCommand.SetSongMonitorGain)
        return submit(command, 1, retry = false) == Outcome.APPLIED
    }

    private suspend fun submit(command: EngineCommand, client: Int, retry: Boolean): Outcome {
        val request = producer.withLock {
            if (closed) return Outcome.REFUSED
            val last = lastClientOrder[client]
            // Only a retry of the command that was just dropped may reuse its order.
            if (command.orderId < last || (command.orderId == last && !retry)) return Outcome.REFUSED
            val current = engineView ?: return Outcome.REFUSED
            val pending = Pending(command, command.relativeTo(current.offset, ++nextWireOrder), current)
            if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); return Outcome.REFUSED }
            requests.add(pending)
            lastClientOrder[client] = command.orderId
            LockSupport.unpark(owner)
            pending
        }
        // Never hold the producer while awaiting audio: Stop must overtake a future deadline.
        return try {
            val answer = withTimeoutOrNull(acknowledgementMillis) { request.answer.await() } ?: run {
                request.cancelFault = DriverFault.ACK_TIMEOUT
                request.cancelled = true
                LockSupport.unpark(owner)
                // Once cancelled the owner never applies it; an answer that raced the deadline still counts.
                withTimeoutOrNull(500) { request.answer.await() }
            }
            when {
                answer == true -> Outcome.APPLIED
                answer == null || request.dropped -> Outcome.DROPPED
                else -> Outcome.REFUSED
            }
        } catch (cancel: CancellationException) {
            request.cancelled = true
            LockSupport.unpark(owner)
            throw cancel
        }
    }

    override fun snapshot(): TransportState {
        val snapshot = snapshots.get()
        val current = engineView
        current?.engine?.readout?.copyInto(snapshot)
        return TransportState(snapshot.frame + (current?.offset ?: 0), snapshot.sequencePlaying, snapshot.programRevision,
            snapshot.activeVoices, snapshot.eventOverflows, statusValue.value.phase == DriverPhase.ATTACHED,
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused, scratchFrame = snapshot.scratchFrame)
    }

    fun diagnostics() = DriverDiagnostics(ownerLoops, queued.get(), ownerInFlight, ownerOpening, snapshot().frame)

    /** Output health for a diagnostics readout; allocates, and asks the device, on the caller's thread only. */
    fun health(): OutputHealth {
        val status = statusValue.value
        val device = attachedSink.takeIf { status.phase == DriverPhase.ATTACHED }
        fun <T> ask(question: AudioSink.() -> T): T? = try { device?.question() } catch (_: Exception) { null }
        val window = minOf(renderedBlocks, RENDER_WINDOW.toLong()).toInt()
        val times = renderNanos.copyOf(window).also { it.sort() }
        val blockNanos = blockFrames * 1_000_000_000.0 / EngineFormat.SAMPLE_RATE
        // Nearest rank: the smallest time that at least 99% of the measured blocks did not exceed.
        val p99 = if (window == 0) null else times[(window * 99 + 99) / 100 - 1] / blockNanos
        return OutputHealth(device != null, status.encoding, EngineFormat.SAMPLE_RATE, blockFrames,
            bufferFrames = ask { bufferFrames() }?.takeIf { it >= 0 }, pendingFrames = ask { pendingFrames() }?.takeIf { it >= 0 },
            underruns = ask { underruns() }?.takeIf { it >= 0 }, outputLosses = status.faults, measuredBlocks = window,
            renderP99 = p99, renderMax = if (window == 0) null else times[window - 1] / blockNanos)
    }

    /** Audio-clock readout; callers use it only in a small display subtree. */
    fun playback(): DriverPlayback {
        val snapshot = snapshots.get()
        val current = engineView
        current?.engine?.readout?.copyInto(snapshot)
        val ticks = snapshot.tickNumerator.toDouble() / SequenceClock.UNITS_PER_TICK
        val length = confirmedProgram.pattern?.lengthTicks ?: 1
        val arrangement = confirmedProgram.arrangement
        val fraction = if (arrangement != null) (snapshot.sequenceFrame.toDouble() / arrangement.durationFrames.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
            else ((ticks % length) / length).toFloat().coerceIn(0f, 1f)
        val elapsed = snapshot.sequenceFrame
        return DriverPlayback(fraction, (elapsed / EngineFormat.SAMPLE_RATE).toInt(), elapsed)
    }

    fun originalPlayback(): OriginalPlayback {
        val snapshot = snapshots.get()
        engineView?.engine?.readout?.copyInto(snapshot)
        return OriginalPlayback(snapshot.originalLoaded, snapshot.originalPlaying, snapshot.originalSourceFrame, snapshot.originalMonitorGain)
    }
    /** UI/control-side allocation only; masks are coherently published by render. */
    fun playingPads(): Set<Int> {
        val snapshot = snapshots.get()
        engineView?.engine?.readout?.copyInto(snapshot)
        return (0 until 128).filterTo(mutableSetOf()) { id ->
            val bits = if (id < 64) snapshot.playingPadsLow else snapshot.playingPadsHigh
            bits and (1L shl (id and 63)) != 0L
        }
    }

    private fun runOwner(first: EngineCore) {
        var activeEngine = first
        val self = Thread.currentThread()
        var faults = 0L
        var sink: AudioSink? = null
        // Until a device is adopted the owner keeps acknowledging edits silently, as in EDITING_ONLY.
        var opening: java.util.concurrent.Future<AudioSink>? = null
        fun startOpening() {
            opening = opener.submit(java.util.concurrent.Callable { try { sinkFactory() } finally { LockSupport.unpark(self) } })
        }
        fun adoptOpened(pending: java.util.concurrent.Future<AudioSink>) {
            opening = null
            val opened = try { pending.get() } catch (_: Exception) { null }
            when {
                opened == null -> {
                    val retrying = statusValue.value.let { it.phase == DriverPhase.EDITING_ONLY && it.fault != DriverFault.NONE }
                    faultLatched.set(true)
                    statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.NO_OUTPUT, faults = if (retrying) faults else ++faults)
                }
                !outputWanted || closed || faultLatched.get() -> {
                    // Hidden while it opened, or a fault meanwhile that waits for reattach: hand the device straight back.
                    try { opened.close() } catch (_: Exception) { }
                    if (statusValue.value.phase == DriverPhase.STARTING) statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, faults = faults)
                }
                else -> {
                    sink = opened
                    // Block times describe this device only.
                    renderedBlocks = 0
                    attachedSink = opened
                    statusValue.value = DriverStatus(DriverPhase.ATTACHED, opened.encoding, faults = faults)
                }
            }
        }
        startOpening()
        val floats = FloatArray(blockFrames * 2)
        val bytes = ByteArray(blockFrames * 2 * 4)
        val quantizer = PcmQuantizer(0x43484f50, bits = 16, dither = true)
        val event = MutableEngineEvent()
        val inFlight = arrayOfNulls<Pending>(65)
        var previousLosses = 0L
        var monitorGain = 1f
        var monitorTarget = 1f
        var monitorRamp = 0

        fun complete(request: Pending, accepted: Boolean, dropped: Boolean = false) { request.dropped = dropped; request.answer.complete(accepted) }
        fun resetToEditingOnly(fault: DriverFault) {
            attachedSink = null
            try { sink?.close() } catch (_: Exception) { }
            sink = null
            // Unknown/late queued commands cannot fire after cancellation. Rebuild from confirmed
            // Program only, with no voices; the document remains in Studio and edits stay usable.
            val offset = requireNotNull(engineView).offset + activeEngine.frame
            activeEngine = EngineCore(confirmedProgram)
            engineView = EngineView(activeEngine, offset)
            previousLosses = 0
            sequenceStart = 0; stoppedElapsedFrames = 0
            // A failure needs a fresh reattach request; a lifecycle release (NONE) reopens when wanted again.
            if (fault != DriverFault.NONE) faultLatched.set(true)
            // Published after the rebuild: a caller reacting to EDITING_ONLY must already target the new
            // engine, or its first edit is bound to the discarded one and refused. Published before the
            // refusals below, so a caller told "false" already sees why.
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = fault, faults = if (fault == DriverFault.NONE) faults else ++faults)
            inFlight.indices.forEach { index -> inFlight[index]?.let { complete(it, false, dropped = true) }; inFlight[index] = null }
        }

        try {
            while (!closed) {
                ownerLoops++
                opening?.let { pending -> if (pending.isDone) adoptOpened(pending) }
                if (sink != null && !outputWanted) resetToEditingOnly(DriverFault.NONE)
                if (sink == null && opening == null && outputWanted && !faultLatched.get()) startOpening()
                var work = false
                inFlight.firstOrNull { it?.cancelled == true }?.let { resetToEditingOnly(it.cancelFault) }
                while (true) {
                    val request = requests.poll() ?: break
                    queued.decrementAndGet()
                    if (request.cancelled) { complete(request, false, dropped = true); continue }
                    if (request.generation !== engineView) { complete(request, false, dropped = true); continue }
                    val command = request.command
                    if (sink == null && !command.needsNoDevice()) { complete(request, false); continue }
                    val slot = inFlight.indexOfFirst { it == null }
                    if (slot < 0 || activeEngine.controls.offer(request.wireCommand) != OfferResult.ACCEPTED) { complete(request, false); continue }
                    request.offered = true
                    inFlight[slot] = request
                    work = true
                }
                ownerOpening = opening != null
                ownerInFlight = inFlight.count { it != null }
                if (sink == null && !work && ownerInFlight == 0) { LockSupport.parkNanos(20_000_000); continue }

                val count = if (sink == null) 1 else blockFrames
                val blockStarted = System.nanoTime()
                activeEngine.render(floats, 0, count)
                if (activeEngine.events.overflowCount != previousLosses) {
                    previousLosses = activeEngine.events.overflowCount
                    resetToEditingOnly(DriverFault.EVENT_LOSS)
                    continue
                }
                while (activeEngine.events.poll(event)) {
                    val slot = inFlight.indexOfFirst { it?.wireCommand?.orderId == event.orderId }
                    if (slot < 0) continue
                    val request = requireNotNull(inFlight[slot])
                    // This engine emits LATE instead of APPLIED only after successfully applying.
                    val applied = event.type == EngineEventType.APPLIED || event.type == EngineEventType.LATE
                    val accepted = applied && !request.cancelled
                    val appliedFrame = event.appliedFrame + requireNotNull(engineView).offset
                    lastReceipt = DriverReceipt(request.command.orderId, request.command.effectiveFrame, appliedFrame,
                        appliedFrame > request.command.effectiveFrame, accepted, activeEngine.events.overflowCount)
                    if (accepted) when (val command = request.command) {
                        is EngineCommand.SwapProgram -> confirmedProgram = command.program
                        is EngineCommand.StartSequence -> { sequenceStart = appliedFrame; stoppedElapsedFrames = 0 }
                        is EngineCommand.Stop, is EngineCommand.Panic -> stoppedElapsedFrames = (appliedFrame - sequenceStart).coerceAtLeast(0)
                        else -> Unit
                    }
                    complete(request, accepted, dropped = request.cancelled)
                    inFlight[slot] = null
                }

                val output = sink ?: continue
                val sampleCount = count * 2
                val byteCount = sampleCount * output.encoding.bytesPerSample
                val target = requestedMonitorGain
                if (target != monitorTarget) { monitorTarget = target; monitorRamp = 96 }
                for (frame in 0 until count) {
                    if (monitorRamp > 0) { monitorGain += (monitorTarget - monitorGain) / monitorRamp; monitorRamp-- }
                    else monitorGain = monitorTarget
                    floats[frame * 2] *= monitorGain
                    floats[frame * 2 + 1] *= monitorGain
                }
                if (monitorTarget == 0f && monitorRamp == 0 && floats.takeIsAllZero(sampleCount)) bytes.fill(0, 0, byteCount)
                else if (output.encoding == SinkEncoding.PCM16) quantizer.encode(floats, bytes, sampleCount = sampleCount)
                else for (index in 0 until sampleCount) {
                    val bits = floats[index].toRawBits()
                    val at = index * 4
                    bytes[at] = bits.toByte(); bytes[at + 1] = (bits ushr 8).toByte()
                    bytes[at + 2] = (bits ushr 16).toByte(); bytes[at + 3] = (bits ushr 24).toByte()
                }
                val block = renderedBlocks
                renderNanos[(block % RENDER_WINDOW).toInt()] = (System.nanoTime() - blockStarted).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                renderedBlocks = block + 1
                var offset = 0
                var lastProgress = System.nanoTime()
                try {
                    while (offset < byteCount && !closed) {
                        val written = output.write(bytes, offset, byteCount - offset)
                        require(written in 0..(byteCount - offset) && written % (2 * output.encoding.bytesPerSample) == 0)
                        if (written > 0) { offset += written; lastProgress = System.nanoTime() }
                        else {
                            if (System.nanoTime() - lastProgress > 500_000_000L) throw IllegalStateException("Audio sink stalled")
                            LockSupport.parkNanos(1_000_000)
                        }
                    }
                } catch (_: Exception) { resetToEditingOnly(DriverFault.WRITE_FAILED) }
            }
        } catch (_: Exception) {
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.WRITE_FAILED, faults = ++faults)
        } finally {
            attachedSink = null
            try { sink?.close() } catch (_: Exception) { }
            // A device still opening is closed as soon as it exists.
            opening?.let { pending -> opener.execute { try { pending.get().close() } catch (_: Exception) { } } }
            opener.shutdown()
            inFlight.forEach { it?.let { pending -> complete(pending, false) } }
            while (true) { val pending = requests.poll() ?: break; queued.decrementAndGet(); complete(pending, false) }
            closed = true
            statusValue.value = DriverStatus(DriverPhase.CLOSED, faults = faults)
        }
    }

    override fun close() {
        closed = true
        LockSupport.unpark(owner)
        if (Thread.currentThread() !== owner) {
            owner.join(2_000)
            opener.awaitTermination(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        check(!owner.isAlive) { "Audio owner did not stop within its deadline" }
    }

    private companion object {
        /** A device that keeps dropping commands this often is left to the output's own fault reporting. */
        const val DROPPED_ATTEMPTS = 3
        /** Block times kept for the health readout: about 22 s of 256-frame blocks. */
        const val RENDER_WINDOW = 4096
    }
}

/** Keeps its meaning without an output device: it changes the Program or silences voices. */
private fun EngineCommand.needsNoDevice() = this is EngineCommand.SwapProgram || this is EngineCommand.Release ||
    this is EngineCommand.Stop || this is EngineCommand.Panic

private fun FloatArray.takeIsAllZero(count: Int): Boolean {
    for (i in 0 until count) if (this[i] != 0f) return false
    return true
}

/** After a failed driver is reset, preserve the host's monotonic absolute frame clock.
 * Assign one wire order across Studio and independent source-audition callers.
 * Translation allocates only on the serialized control producer, never render.
 */
private fun EngineCommand.relativeTo(offset: Long, wireOrder: Long): EngineCommand {
    if (offset == 0L && wireOrder == this.orderId) return this
    val frame = (effectiveFrame - offset).coerceAtLeast(0)
    val orderId = wireOrder
    return when (this) {
        is EngineCommand.Trigger -> EngineCommand.Trigger(frame, orderId, padId, velocity)
        is EngineCommand.Release -> EngineCommand.Release(frame, orderId, padId)
        is EngineCommand.Stop -> EngineCommand.Stop(frame, orderId)
        is EngineCommand.StopAll -> EngineCommand.StopAll(frame, orderId)
        is EngineCommand.Panic -> EngineCommand.Panic(frame, orderId)
        is EngineCommand.SwapProgram -> EngineCommand.SwapProgram(frame, orderId, program)
        is EngineCommand.StartSequence -> EngineCommand.StartSequence(frame, orderId)
        is EngineCommand.Seek -> EngineCommand.Seek(frame, orderId, sequenceFrame)
        is EngineCommand.Pause -> EngineCommand.Pause(frame, orderId)
        is EngineCommand.Resume -> EngineCommand.Resume(frame, orderId)
        is EngineCommand.SetOriginalSource -> EngineCommand.SetOriginalSource(frame, orderId, source)
        is EngineCommand.PlayOriginalSource -> EngineCommand.PlayOriginalSource(frame, orderId)
        is EngineCommand.PauseOriginalSource -> EngineCommand.PauseOriginalSource(frame, orderId)
        is EngineCommand.SeekOriginalSource -> EngineCommand.SeekOriginalSource(frame, orderId, sourceFrame)
        is EngineCommand.SetOriginalMonitorGain -> EngineCommand.SetOriginalMonitorGain(frame, orderId, gain)
        is EngineCommand.SetOriginalPitch -> EngineCommand.SetOriginalPitch(frame, orderId, semitones)
        is EngineCommand.SetSongMonitorGain -> EngineCommand.SetSongMonitorGain(frame, orderId, gain)
        is EngineCommand.SetTempo -> EngineCommand.SetTempo(frame, orderId, tempo)
        is EngineCommand.ScratchStart -> EngineCommand.ScratchStart(frame, orderId, padId, sourceFrame)
        is EngineCommand.ScratchPosition -> EngineCommand.ScratchPosition(frame, orderId, sourceFrame, durationFrames)
        is EngineCommand.ScratchCut -> EngineCommand.ScratchCut(frame, orderId, gain)
        is EngineCommand.ScratchEnd -> EngineCommand.ScratchEnd(frame, orderId)
        is EngineCommand.ScratchOriginalStart -> EngineCommand.ScratchOriginalStart(frame, orderId, sourceFrame, startFrame, endFrame)
        is EngineCommand.ScratchOriginalPosition -> EngineCommand.ScratchOriginalPosition(frame, orderId, sourceFrame, durationFrames)
        is EngineCommand.ScratchOriginalCut -> EngineCommand.ScratchOriginalCut(frame, orderId, gain)
        is EngineCommand.ScratchOriginalEnd -> EngineCommand.ScratchOriginalEnd(frame, orderId)
    }
}
