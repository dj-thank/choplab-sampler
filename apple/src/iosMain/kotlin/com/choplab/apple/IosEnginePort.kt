package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.NSThread
import platform.darwin.*
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt

internal enum class DriverPhase { STARTING, ATTACHED, EDITING_ONLY, CLOSED }
internal enum class DriverFault { NONE, NO_OUTPUT, WRITE_FAILED, ACK_TIMEOUT, ACK_CANCELLED, EVENT_LOSS }
internal data class DriverStatus(val phase: DriverPhase, val fault: DriverFault = DriverFault.NONE, val faults: Long = 0)
internal data class OriginalPlayback(val loaded: Boolean, val playing: Boolean, val sourceFrame: Long, val gain: Float)
internal sealed interface OriginalPlaybackProbe {
    data class Ready(val playback: OriginalPlayback) : OriginalPlaybackProbe
    data object Contended : OriginalPlaybackProbe
    data object Unavailable : OriginalPlaybackProbe
}
internal data class HandPlayback(val sourceFrame: Double, val gain: Float)
internal data class OutputHealth(val attached: Boolean, val blockFrames: Int, val bufferFrames: Int?, val pendingFrames: Long?,
                                 val underruns: Int?, val outputLosses: Long, val measuredBlocks: Int, val renderP99: Double?, val renderMax: Double?)

internal fun monotonicNanos(): Long = clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()).toLong()

/**
 * Continuous EngineCore output for iPadOS, ported from the JVM hosts' StreamingEnginePort: one owner thread holds
 * the EngineCore and the route; Studio's commands are offered in order, acknowledged only by the engine's APPLIED/LATE
 * events, and refused rather than lost. Without a route (hidden, interrupted, failed) the owner keeps acknowledging
 * edits silently in EDITING_ONLY; a failure stays latched until [reattach].
 */
internal class IosEnginePort(
    private val compiler: ProgramCompiler,
    private val openOutput: () -> IosAudioOutput?,
    private val blockFrames: Int = 256,
    private val acknowledgementMillis: Long = 1_000,
) : EnginePort, AutoCloseable {
    private class EngineView(val engine: EngineCore, val offset: Long) { val identity = Any() }
    private class AcknowledgedReadout(val generation: EngineView) { val snapshot = EngineSnapshot() }
    private class Pending(val command: EngineCommand, val wireCommand: EngineCommand, val generation: EngineView) {
        val answer = CompletableDeferred<Boolean>()
        val readout = AcknowledgedReadout(generation)
        @Volatile var cancelled = false
        @Volatile var cancelFault = DriverFault.ACK_CANCELLED
        @Volatile var dropped = false
        private val released = AtomicInt(0)
        private val program = (command as? EngineCommand.SwapProgram)?.program
        private val source = (command as? EngineCommand.SetOriginalSource)?.source?.asset?.acquire()
        init { check(program?.retainPcm() != false) { "Queued PCM was evicted" } }
        fun releasePcm() { if (released.compareAndSet(0, 1)) { program?.releasePcm(); source?.close() } }
    }
    private enum class Outcome { APPLIED, REFUSED, DROPPED }

    private val requestLock = HostLock()
    private val requests = ArrayDeque<Pending>()
    private val wake = dispatch_semaphore_create(0)
    private val producer = Mutex()
    private var nextWireOrder = 0L
    private val lastClientOrder = longArrayOf(-1L, -1L)
    private val statusValue = MutableStateFlow(DriverStatus(DriverPhase.STARTING))
    val status: StateFlow<DriverStatus> = statusValue.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var ownerStopped = false
    @Volatile private var outputWanted = true
    private val faultLatched = AtomicInt(0)
    @Volatile private var requestedMonitorGain = 1f
    @Volatile private var engineView: EngineView? = null
    @Volatile private var acknowledgedReadout: AcknowledgedReadout? = null
    @Volatile private var confirmedProgram = EngineProgram.EMPTY
    @Volatile private var attached: IosAudioOutput? = null
    @Volatile private var outputSession: Any = Any()
    @Volatile private var writtenFrame = -1L
    @Volatile private var ownerLoops = 0L
    private val renderNanos = IntArray(RENDER_WINDOW)
    @Volatile private var renderedBlocks = 0L
    private val readLock = HostLock()
    private var lastCompleted = EngineSnapshot()
    private var lastIdentity: Any? = null

    init {
        require(blockFrames in 64..2048 && acknowledgementMillis in 50..1_500)
        // Built before the owner starts: the first engine prepares its interpolation tables, which takes a while.
        val first = EngineCore()
        engineView = EngineView(first, 0)
        NSThread { runOwner(first) }.apply {
            name = "ChopLab-audio"
            qualityOfService = NSQualityOfServiceUserInteractive
            start()
        }
    }

    override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram = compiler.compile(project, patternId, revision)
    override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram = compiler.compile(project, target, revision)

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

    fun setMonitorGain(gain: Float) { require(gain.isFinite() && gain in 0f..1f); requestedMonitorGain = gain }

    /** Opens a fresh route after a fault or [releaseOutput]; false while starting, attached or closed. */
    fun reattach(): Boolean {
        if (closed) return false
        val releasing = !outputWanted
        outputWanted = true
        if (!releasing && statusValue.value.phase != DriverPhase.EDITING_ONLY) return false
        faultLatched.store(0)
        dispatch_semaphore_signal(wake)
        return true
    }

    /** Lifecycle: close the route while the app is in the background; edits stay usable. */
    fun releaseOutput(): Boolean {
        if (closed) return false
        outputWanted = false
        dispatch_semaphore_signal(wake)
        return true
    }

    private suspend fun submit(command: EngineCommand, client: Int, retry: Boolean): Outcome {
        val request = producer.withLock {
            if (closed) return Outcome.REFUSED
            val last = lastClientOrder[client]
            if (command.orderId < last || (command.orderId == last && !retry)) return Outcome.REFUSED
            val current = engineView ?: return Outcome.REFUSED
            val pending = Pending(command, command.relativeTo(current.offset, ++nextWireOrder), current)
            val accepted = requestLock.withLock { if (requests.size >= 64 || closed) false else { requests.addLast(pending); true } }
            if (!accepted) { pending.releasePcm(); return Outcome.REFUSED }
            lastClientOrder[client] = command.orderId
            dispatch_semaphore_signal(wake)
            pending
        }
        return try {
            val answer = withTimeoutOrNull(acknowledgementMillis) { request.answer.await() } ?: run {
                request.cancelFault = DriverFault.ACK_TIMEOUT
                request.cancelled = true
                dispatch_semaphore_signal(wake)
                withTimeoutOrNull(500) { request.answer.await() }
            }
            when {
                answer == true -> Outcome.APPLIED
                answer == null || request.dropped -> Outcome.DROPPED
                else -> Outcome.REFUSED
            }
        } catch (cancel: CancellationException) {
            request.cancelled = true
            dispatch_semaphore_signal(wake)
            throw cancel
        }
    }

    override fun snapshot(): TransportState {
        val snapshot = readout()
        val current = engineView
        return TransportState(snapshot.frame + (current?.offset ?: 0), snapshot.sequencePlaying, snapshot.programRevision,
            snapshot.activeVoices, snapshot.eventOverflows, statusValue.value.phase == DriverPhase.ATTACHED,
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused, scratchFrame = snapshot.scratchFrame,
            metronomeEnabled = snapshot.metronomeEnabled, countInBeatsRemaining = snapshot.countInBeatsRemaining,
            recordingStartFrame = if (snapshot.recordingStartFrame < 0) -1 else snapshot.recordingStartFrame + (current?.offset ?: 0),
            recordingStartSequenceFrame = snapshot.recordingStartSequenceFrame,
            recordingStartedFrame = if (snapshot.recordingStartedFrame < 0) -1 else snapshot.recordingStartedFrame + (current?.offset ?: 0))
    }

    /** A coherent readout of the current engine; a read colliding with the owner's publication keeps the last one. */
    private fun readout(): EngineSnapshot = readLock.withLock {
        val current = engineView
        if (lastIdentity !== current?.identity) { lastIdentity = current?.identity; lastCompleted = EngineSnapshot() }
        val candidate = EngineSnapshot()
        if (current?.engine?.readout?.copyInto(candidate) == true) lastCompleted = candidate
        else acknowledgedReadout?.takeIf { it.generation === current && it.snapshot.frame >= lastCompleted.frame }?.let { lastCompleted = it.snapshot }
        lastCompleted
    }

    fun playingPads(): Set<Int> {
        val snapshot = readout()
        return (0 until 128).filterTo(mutableSetOf()) { id ->
            val bits = if (id < 64) snapshot.playingPadsLow else snapshot.playingPadsHigh
            bits and (1L shl (id and 63)) != 0L
        }
    }
    fun originalPlayback(): OriginalPlayback = readout().let { OriginalPlayback(it.originalLoaded, it.originalPlaying, it.originalSourceFrame, it.originalMonitorGain) }
    fun originalPlaybackProbe(): OriginalPlaybackProbe {
        val current = engineView ?: return OriginalPlaybackProbe.Unavailable
        val session = outputSession
        if (statusValue.value.phase != DriverPhase.ATTACHED) return OriginalPlaybackProbe.Unavailable
        val snapshot = EngineSnapshot()
        val copied = current.engine.readout.copyInto(snapshot)
        if (current !== engineView || session !== outputSession || statusValue.value.phase != DriverPhase.ATTACHED) return OriginalPlaybackProbe.Unavailable
        if (!copied) return OriginalPlaybackProbe.Contended
        return OriginalPlaybackProbe.Ready(OriginalPlayback(snapshot.originalLoaded, snapshot.originalPlaying, snapshot.originalSourceFrame, snapshot.originalMonitorGain))
    }
    fun handPlayback(): HandPlayback = readout().let { HandPlayback(it.handSourceFrame, it.handMonitorGain) }
    /** Identity of the attached route, for SOURCE reuse; null without a route. */
    fun sourceOutputSession(): Any? {
        val session = outputSession
        val device = attached ?: return null
        return if (!closed && statusValue.value.phase == DriverPhase.ATTACHED && session === outputSession && device === attached) session else null
    }

    fun health(): OutputHealth {
        val device = attached.takeIf { statusValue.value.phase == DriverPhase.ATTACHED }
        val window = minOf(renderedBlocks, RENDER_WINDOW.toLong()).toInt()
        val times = renderNanos.copyOf(window).also { it.sort() }
        val blockNanos = blockFrames * 1_000_000_000.0 / EngineFormat.SAMPLE_RATE
        val p99 = if (window == 0) null else times[(window * 99 + 99) / 100 - 1] / blockNanos
        return OutputHealth(device != null, blockFrames, device?.bufferFrames, device?.pendingFrames(), device?.underruns(),
            statusValue.value.faults, window, p99, if (window == 0) null else times[window - 1] / blockNanos)
    }

    /** Control-thread estimate of when an engine frame reaches the speaker; not a route calibration. */
    fun estimatedOutputNanos(engineFrame: Long): Long? {
        val device = attached ?: return null
        if (statusValue.value.phase != DriverPhase.ATTACHED) return null
        val written = writtenFrame
        if (written < 0) return null
        val remaining = engineFrame - written + device.pendingFrames() + MasterLimiter.LOOKAHEAD_FRAMES
        if (remaining !in -48_000L..720_000L) return null
        return monotonicNanos() + remaining * 1_000_000_000L / EngineFormat.SAMPLE_RATE
    }

    private fun runOwner(first: EngineCore) {
        var activeEngine = first
        var faults = 0L
        var output: IosAudioOutput? = null
        val floats = FloatArray(blockFrames * 2)
        val event = MutableEngineEvent()
        val inFlight = arrayOfNulls<Pending>(65)
        var previousLosses = 0L
        var monitorGain = 1f
        var monitorTarget = 1f
        var monitorRamp = 0
        var originalMonitorTarget = 1f
        var songMonitorTarget = 1f
        var handMonitorTarget = 1f

        fun complete(request: Pending, accepted: Boolean, dropped: Boolean = false) {
            request.dropped = dropped; request.releasePcm(); request.answer.complete(accepted)
        }
        fun resetToEditingOnly(fault: DriverFault) {
            writtenFrame = -1
            attached = null
            outputSession = Any()
            output?.close()
            output = null
            val offset = requireNotNull(engineView).offset + activeEngine.frame
            activeEngine.close()
            activeEngine = EngineCore(confirmedProgram, monitorGains = MonitorGains(originalMonitorTarget, songMonitorTarget, handMonitorTarget))
            engineView = EngineView(activeEngine, offset)
            acknowledgedReadout = null
            previousLosses = 0
            if (fault != DriverFault.NONE) faultLatched.store(1)
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault, if (fault == DriverFault.NONE) faults else ++faults)
            inFlight.indices.forEach { index -> inFlight[index]?.let { complete(it, false, dropped = true) }; inFlight[index] = null }
        }
        fun openRoute() {
            val opened = openOutput()
            when {
                opened == null -> {
                    faultLatched.store(1)
                    statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, DriverFault.NO_OUTPUT, ++faults)
                }
                !outputWanted || closed -> { opened.close(); statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, faults = faults) }
                else -> {
                    output = opened
                    renderedBlocks = 0
                    outputSession = Any()
                    attached = opened
                    writtenFrame = requireNotNull(engineView).offset + activeEngine.frame
                    statusValue.value = DriverStatus(DriverPhase.ATTACHED, faults = faults)
                }
            }
        }

        try {
            while (!closed) {
                ownerLoops++
                if (output != null && !outputWanted) resetToEditingOnly(DriverFault.NONE)
                if (output?.faulted == true) resetToEditingOnly(DriverFault.WRITE_FAILED)
                if (output == null && outputWanted && faultLatched.load() == 0) openRoute()
                var work = false
                inFlight.firstOrNull { it?.cancelled == true }?.let { resetToEditingOnly(it.cancelFault) }
                while (true) {
                    val request = requestLock.withLock { requests.removeFirstOrNull() } ?: break
                    if (request.cancelled || request.generation !== engineView) { complete(request, false, dropped = true); continue }
                    val command = request.command
                    if (output == null && !command.needsNoDevice()) { complete(request, false); continue }
                    val slot = inFlight.indexOfFirst { it == null }
                    if (slot < 0 || activeEngine.controls.offer(request.wireCommand) != OfferResult.ACCEPTED) { complete(request, false); continue }
                    inFlight[slot] = request
                    work = true
                }
                val inFlightCount = inFlight.count { it != null }
                val route = output
                if (route == null) {
                    if (!work && inFlightCount == 0) { dispatch_semaphore_wait(wake, dispatch_time(DISPATCH_TIME_NOW, 20_000_000)); continue }
                } else {
                    route.awaitSpace(3_000_000)
                    if (!route.hasSpace()) continue
                }

                val count = if (route == null) 1 else blockFrames
                val started = monotonicNanos()
                activeEngine.render(floats, 0, count)
                if (activeEngine.events.overflowCount != previousLosses) { previousLosses = activeEngine.events.overflowCount; resetToEditingOnly(DriverFault.EVENT_LOSS); continue }
                while (activeEngine.events.poll(event)) {
                    val slot = inFlight.indexOfFirst { it?.wireCommand?.orderId == event.orderId }
                    if (slot < 0) continue
                    val request = requireNotNull(inFlight[slot])
                    val applied = event.type == EngineEventType.APPLIED || event.type == EngineEventType.LATE
                    val accepted = applied && !request.cancelled
                    if (accepted) when (val command = request.command) {
                        is EngineCommand.SwapProgram -> {
                            check(command.program.retainPcm())
                            val previous = confirmedProgram
                            confirmedProgram = command.program
                            previous.releasePcm()
                        }
                        is EngineCommand.SetOriginalMonitorGain -> originalMonitorTarget = command.gain
                        is EngineCommand.SetSongMonitorGain -> songMonitorTarget = command.gain
                        is EngineCommand.SetHandMonitorGain -> handMonitorTarget = command.gain
                        else -> Unit
                    }
                    if (accepted) {
                        check(activeEngine.readout.copyInto(request.readout.snapshot))
                        acknowledgedReadout = request.readout
                    }
                    complete(request, accepted, dropped = request.cancelled)
                    inFlight[slot] = null
                }
                if (route == null) continue
                val target = requestedMonitorGain
                if (target != monitorTarget) { monitorTarget = target; monitorRamp = 96 }
                for (frame in 0 until count) {
                    if (monitorRamp > 0) { monitorGain += (monitorTarget - monitorGain) / monitorRamp; monitorRamp-- } else monitorGain = monitorTarget
                    floats[frame * 2] *= monitorGain
                    floats[frame * 2 + 1] *= monitorGain
                }
                val block = renderedBlocks
                renderNanos[(block % RENDER_WINDOW).toInt()] = (monotonicNanos() - started).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                renderedBlocks = block + 1
                if (!route.write(floats, count)) { resetToEditingOnly(DriverFault.WRITE_FAILED); continue }
                writtenFrame = requireNotNull(engineView).offset + activeEngine.frame
            }
        } catch (_: Throwable) {
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, DriverFault.WRITE_FAILED, ++faults)
        } finally {
            closed = true
            attached = null
            output?.close()
            inFlight.forEach { it?.let { pending -> complete(pending, false) } }
            while (true) { val pending = requestLock.withLock { requests.removeFirstOrNull() } ?: break; complete(pending, false) }
            activeEngine.close()
            confirmedProgram.releasePcm()
            confirmedProgram = EngineProgram.EMPTY
            statusValue.value = DriverStatus(DriverPhase.CLOSED, faults = faults)
            ownerStopped = true
        }
    }

    override fun close() {
        closed = true
        dispatch_semaphore_signal(wake)
        val deadline = monotonicNanos() + 2_000_000_000L
        while (!ownerStopped && monotonicNanos() < deadline) NSThread.sleepForTimeInterval(0.005)
        check(ownerStopped) { "Audio owner did not stop within its deadline" }
    }

    private companion object {
        const val DROPPED_ATTEMPTS = 3
        const val RENDER_WINDOW = 4096
    }
}

private fun IosAudioOutput.hasSpace(): Boolean = freeBuffers() > 0

/** Keeps its meaning without an output route: it changes the Program or silences voices. */
private fun EngineCommand.needsNoDevice() = this is EngineCommand.SwapProgram || this is EngineCommand.Release ||
    this is EngineCommand.Stop || this is EngineCommand.Panic

/** Preserves the host's monotonic absolute frame clock across engine rebuilds; one wire order for all clients. */
private fun EngineCommand.relativeTo(offset: Long, wireOrder: Long): EngineCommand {
    if (offset == 0L && wireOrder == this.orderId) return this
    val frame = (effectiveFrame - offset).coerceAtLeast(0)
    val orderId = wireOrder
    return when (this) {
        is EngineCommand.Trigger -> EngineCommand.Trigger(frame, orderId, padId, velocity)
        is EngineCommand.StartLoopOverdub -> EngineCommand.StartLoopOverdub(frame, orderId, take)
        is EngineCommand.StartNoteRepeat -> EngineCommand.StartNoteRepeat(frame, orderId, padId, ticks, durationFrames)
        is EngineCommand.Release -> EngineCommand.Release(frame, orderId, padId)
        is EngineCommand.Stop -> EngineCommand.Stop(frame, orderId)
        is EngineCommand.StopAll -> EngineCommand.StopAll(frame, orderId)
        is EngineCommand.Panic -> EngineCommand.Panic(frame, orderId)
        is EngineCommand.SwapProgram -> EngineCommand.SwapProgram(frame, orderId, program)
        is EngineCommand.StartSequence -> EngineCommand.StartSequence(frame, orderId)
        is EngineCommand.Seek -> EngineCommand.Seek(frame, orderId, sequenceFrame)
        is EngineCommand.Pause -> EngineCommand.Pause(frame, orderId)
        is EngineCommand.Resume -> EngineCommand.Resume(frame, orderId)
        is EngineCommand.SetMetronome -> EngineCommand.SetMetronome(frame, orderId, enabled)
        is EngineCommand.CountInAndResume -> EngineCommand.CountInAndResume(frame, orderId, bars)
        is EngineCommand.SetOriginalSource -> EngineCommand.SetOriginalSource(frame, orderId, source)
        is EngineCommand.PlayOriginalSource -> EngineCommand.PlayOriginalSource(frame, orderId)
        is EngineCommand.PauseOriginalSource -> EngineCommand.PauseOriginalSource(frame, orderId)
        is EngineCommand.SeekOriginalSource -> EngineCommand.SeekOriginalSource(frame, orderId, sourceFrame)
        is EngineCommand.SetOriginalMonitorGain -> EngineCommand.SetOriginalMonitorGain(frame, orderId, gain)
        is EngineCommand.SetHandMonitorGain -> EngineCommand.SetHandMonitorGain(frame, orderId, gain)
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
