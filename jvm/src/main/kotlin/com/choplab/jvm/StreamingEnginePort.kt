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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport


enum class SinkEncoding(val bytesPerSample: Int) { FLOAT32(4), PCM16(2) }

/** One owner calls write/close. Returning zero expresses bounded backpressure, not success. */
interface AudioSink : AutoCloseable {
    val encoding: SinkEncoding
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    override fun close()
}

enum class DriverPhase { STARTING, ATTACHED, EDITING_ONLY, CLOSED }
enum class DriverFault { NONE, NO_OUTPUT, WRITE_FAILED, ACK_TIMEOUT, ACK_CANCELLED, EVENT_LOSS }
data class DriverStatus(val phase: DriverPhase, val encoding: SinkEncoding? = null, val fault: DriverFault = DriverFault.NONE)
data class DriverReceipt(
    val orderId: Long, val requestedFrame: Long, val appliedFrame: Long, val appliedLate: Boolean,
    val acknowledged: Boolean, val eventLosses: Long,
)
data class DriverPlayback(val fraction: Float = 0f, val elapsedSeconds: Int = 0, val sequenceRenderFrames: Long = 0)
data class OriginalPlayback(val loaded: Boolean, val playing: Boolean, val sourceFrame: Long, val gain: Float)

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
        var offered = false
    }
    private val requests = ConcurrentLinkedQueue<Pending>()
    private val queued = AtomicInteger()
    private val producer = Mutex()
    private var nextWireOrder = 0L
    private val lastClientOrder = longArrayOf(-1L, -1L)
    private val statusValue = MutableStateFlow(DriverStatus(DriverPhase.STARTING))
    val status: StateFlow<DriverStatus> = statusValue.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var requestedMonitorGain = 1f
    @Volatile private var engineView: EngineView? = null
    @Volatile private var confirmedProgram = EngineProgram.EMPTY
    @Volatile private var sequenceStart = 0L
    @Volatile private var stoppedElapsedFrames = 0L
    @Volatile var lastReceipt: DriverReceipt? = null
        private set
    private val snapshots = ThreadLocal.withInitial { EngineSnapshot() }
    private val owner: Thread

    /** Listening only, after EngineCore; does not enter documents or offline export. */
    fun setMonitorGain(gain: Float) {
        require(gain.isFinite() && gain in 0f..1f)
        requestedMonitorGain = gain
    }

    init {
        require(blockFrames in 64..2048 && acknowledgementMillis in 50..1_500)
        owner = Thread(::runOwner, "ChopLab-NEXT-audio").apply { isDaemon = true; start() }
    }
    override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram =
        compiler.compile(project, patternId, revision)
    override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram =
        compiler.compile(project, target, revision)

    override suspend fun apply(command: EngineCommand): Boolean = applyForClient(command, 0)

    /** A separate logical client may control monitoring, never overwrite the document program. */
    suspend fun applyMonitoring(command: EngineCommand): Boolean {
        require(command is EngineCommand.OriginalSourceCommand || command is EngineCommand.SetSongMonitorGain)
        return applyForClient(command, 1)
    }

    private suspend fun applyForClient(command: EngineCommand, client: Int): Boolean {
        val request = producer.withLock {
            if (closed) return false
            if (command.orderId <= lastClientOrder[client]) return false
            while (engineView == null && !closed) delay(1)
            val current = engineView ?: return false
            val pending = Pending(command, command.relativeTo(current.offset, ++nextWireOrder), current)
            if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); return false }
            requests.add(pending)
            lastClientOrder[client] = command.orderId
            LockSupport.unpark(owner)
            pending
        }
        // Never hold the producer while awaiting audio: Stop must overtake a future deadline.
        return try {
            withTimeoutOrNull(acknowledgementMillis) { request.answer.await() } ?: run {
                request.cancelFault = DriverFault.ACK_TIMEOUT
                request.cancelled = true
                LockSupport.unpark(owner)
                withTimeoutOrNull(500) { request.answer.await() }
                false
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
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused)
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

    private fun runOwner() {
        var activeEngine = EngineCore()
        engineView = EngineView(activeEngine, 0)
        var sink: AudioSink? = try { sinkFactory().also { statusValue.value = DriverStatus(DriverPhase.ATTACHED, it.encoding) } }
            catch (_: Exception) { statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.NO_OUTPUT); null }
        val floats = FloatArray(blockFrames * 2)
        val bytes = ByteArray(blockFrames * 2 * 4)
        val quantizer = PcmQuantizer(0x43484f50, bits = 16, dither = true)
        val event = MutableEngineEvent()
        val inFlight = arrayOfNulls<Pending>(65)
        var previousLosses = 0L
        var monitorGain = 1f
        var monitorTarget = 1f
        var monitorRamp = 0

        fun complete(request: Pending, accepted: Boolean) { request.answer.complete(accepted) }
        fun resetToEditingOnly(fault: DriverFault) {
            try { sink?.close() } catch (_: Exception) { }
            sink = null
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = fault)
            // Unknown/late queued commands cannot fire after cancellation. Rebuild from confirmed
            // Program only, with no voices; the document remains in Studio and edits stay usable.
            val offset = requireNotNull(engineView).offset + activeEngine.frame
            activeEngine = EngineCore(confirmedProgram)
            engineView = EngineView(activeEngine, offset)
            previousLosses = 0
            sequenceStart = 0; stoppedElapsedFrames = 0
            inFlight.indices.forEach { index -> inFlight[index]?.let { complete(it, false) }; inFlight[index] = null }
        }

        try {
            while (!closed) {
                var work = false
                inFlight.firstOrNull { it?.cancelled == true }?.let { resetToEditingOnly(it.cancelFault) }
                while (true) {
                    val request = requests.poll() ?: break
                    queued.decrementAndGet()
                    if (request.cancelled) { complete(request, false); continue }
                    if (request.generation !== engineView) { complete(request, false); continue }
                    val command = request.command
                    if (sink == null && command !is EngineCommand.SwapProgram && command !is EngineCommand.Release &&
                        command !is EngineCommand.Stop && command !is EngineCommand.Panic) { complete(request, false); continue }
                    val slot = inFlight.indexOfFirst { it == null }
                    if (slot < 0 || activeEngine.controls.offer(request.wireCommand) != OfferResult.ACCEPTED) { complete(request, false); continue }
                    request.offered = true
                    inFlight[slot] = request
                    work = true
                }
                if (sink == null && !work && inFlight.all { it == null }) { LockSupport.parkNanos(20_000_000); continue }

                val count = if (sink == null) 1 else blockFrames
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
                    complete(request, accepted)
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
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.WRITE_FAILED)
        } finally {
            try { sink?.close() } catch (_: Exception) { }
            inFlight.forEach { it?.let { pending -> complete(pending, false) } }
            while (true) { val pending = requests.poll() ?: break; queued.decrementAndGet(); complete(pending, false) }
            closed = true
            statusValue.value = DriverStatus(DriverPhase.CLOSED)
        }
    }

    override fun close() {
        closed = true
        LockSupport.unpark(owner)
        if (Thread.currentThread() !== owner) owner.join(2_000)
        check(!owner.isAlive) { "Audio owner did not stop within its deadline" }
    }
}

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
        is EngineCommand.SetSongMonitorGain -> EngineCommand.SetSongMonitorGain(frame, orderId, gain)
        is EngineCommand.SetTempo -> EngineCommand.SetTempo(frame, orderId, tempo)
        is EngineCommand.ScratchStart -> EngineCommand.ScratchStart(frame, orderId, padId, sourceFrame)
        is EngineCommand.ScratchPosition -> EngineCommand.ScratchPosition(frame, orderId, sourceFrame, durationFrames)
        is EngineCommand.ScratchCut -> EngineCommand.ScratchCut(frame, orderId, gain)
        is EngineCommand.ScratchEnd -> EngineCommand.ScratchEnd(frame, orderId)
    }
}
