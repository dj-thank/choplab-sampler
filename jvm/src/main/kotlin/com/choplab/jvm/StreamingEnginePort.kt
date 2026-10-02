package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.chop.LiveChopOutput
import com.choplab.core.chop.LiveChopProbe
import com.choplab.core.chop.LiveChopRoute
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
    /** Monotonic session epoch. Change it when a reported presentation clock resets or is lost/recovered.
     * Throw while timing is invalid (closed or replaced route). No persistent device identifiers. */
    fun timingEpoch(): Long = 0
    fun timingSampleRate(): Int = EngineFormat.SAMPLE_RATE
    fun timingChannels(): Int = 2
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
/** A failed bounded read is not evidence that SOURCE stopped. All values are read on the caller's thread. */
sealed interface OriginalPlaybackProbe {
    data class Ready(val playback: OriginalPlayback) : OriginalPlaybackProbe
    /** The same attached engine is publishing; try again on the next control tick. */
    data object Contended : OriginalPlaybackProbe
    /** No stable attached engine/output session; lifecycle and fault observers decide how to restore ownership. */
    data object Unavailable : OriginalPlaybackProbe
}
/** HAND position is independent of SOURCE; -1 means no hand currently owns the region. */
data class HandPlayback(val sourceFrame: Double, val gain: Float)
data class PcmPlayback(val status: PcmReadStatus, val underrunFrames: Long, val droppedRequests: Long)
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
    private data class EngineView(val engine: EngineCore, val offset: Long) { val identity = Any() }
    /** Reader-local buffers never keep the replaced engine or its PCM alive. */
    private class TransportReadout {
        var identity: Any? = null
        var completed = EngineSnapshot()
        var candidate = EngineSnapshot()
    }
    /** Allocated by the producer, filled once by the owner before APPLIED wakes its caller. */
    private class AcknowledgedReadout(val generation: EngineView) { val snapshot = EngineSnapshot() }
    private class Pending(val command: EngineCommand, val wireCommand: EngineCommand, val generation: EngineView) {
        val answer = CompletableDeferred<Boolean>()
        val readout = AcknowledgedReadout(generation)
        @Volatile var cancelled = false
        @Volatile var cancelFault = DriverFault.ACK_CANCELLED
        /** Discarded unapplied because the owner timed it out or rebuilt its engine, not refused on its merits. */
        @Volatile var dropped = false
        var offered = false
        private val released = AtomicBoolean()
        private val program = (command as? EngineCommand.SwapProgram)?.program
        private val source = (command as? EngineCommand.SetOriginalSource)?.source?.asset?.acquire()
        init { check(program?.retainPcm() != false) { "Queued PCM was evicted" } }
        fun releasePcm() { if (released.compareAndSet(false, true)) { program?.releasePcm(); source?.close() } }
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
    @Volatile private var acknowledgedReadout: AcknowledgedReadout? = null
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
    @Volatile private var routeGeneration = 0L
    /** Changes on every output adoption/detach, including same-format normal lifecycle replacement. */
    fun outputRouteGeneration(): Long = routeGeneration
    @Volatile private var outputSession: Any = Any()
    @Volatile private var writtenFrame = -1L
    @Volatile private var completedCueBeforeReset = -1L
    private val snapshots = ThreadLocal.withInitial { EngineSnapshot() }
    private val transportSnapshots = ThreadLocal.withInitial { TransportReadout() }
    /** Creates devices off the audio owner: a slow device open never holds up edits. The owner writes and closes. */
    private val opener = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-NEXT-device-open").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    private val owner: Thread
    private val outputMemory: PcmMemoryBudget.Reservation

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
        outputMemory = runBlocking { PcmMemoryBudget.shared.reserve(blockFrames * 16L + MixerDsp.PCM_BYTES) }
        val first = try { EngineCore() } catch (failure: Throwable) { outputMemory.close(); throw failure }
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
            if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); pending.releasePcm(); return Outcome.REFUSED }
            requests.add(pending)
            // Owner shutdown may have drained the queue between our first closed check and publication.
            if (closed && requests.remove(pending)) {
                queued.decrementAndGet(); pending.releasePcm(); return Outcome.REFUSED
            }
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
        val local = transportSnapshots.get()
        val current = engineView
        if (local.identity !== current?.identity) {
            local.identity = current?.identity
            local.completed = EngineSnapshot()
        }
        // A bounded live read may collide with the next render publication. Its target can then be stale or
        // partially copied, including a missing count-in cue immediately after APPLIED. Use the completed
        // acknowledgement from this engine instead; never retry/spin on the audio owner or borrow a lost route's cue.
        val snapshot = if (current?.engine?.readout?.copyInto(local.candidate) == true) {
            val previous = local.completed
            local.completed = local.candidate
            local.candidate = previous
            local.completed
        } else {
            val acknowledged = acknowledgedReadout?.takeIf { it.generation === current }?.snapshot
            // The clock may have progressed far beyond the last command: keep a newer coherent read.
            if (acknowledged != null && acknowledged.frame >= local.completed.frame) acknowledged else local.completed
        }
        return TransportState(snapshot.frame + (current?.offset ?: 0), snapshot.sequencePlaying, snapshot.programRevision,
            snapshot.activeVoices, snapshot.eventOverflows, statusValue.value.phase == DriverPhase.ATTACHED,
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused, scratchFrame = snapshot.scratchFrame,
            metronomeEnabled = snapshot.metronomeEnabled, countInBeatsRemaining = snapshot.countInBeatsRemaining,
            recordingStartFrame = if (snapshot.recordingStartFrame < 0) -1 else snapshot.recordingStartFrame + (current?.offset ?: 0),
            recordingStartSequenceFrame = snapshot.recordingStartSequenceFrame,
            recordingStartedFrame = if (snapshot.recordingStartedFrame < 0) completedCueBeforeReset
                else snapshot.recordingStartedFrame + (current?.offset ?: 0))
    }

    /**
     * Control-thread estimate of when an engine input frame reaches this output, including limiter/queued frames.
     * This is a capture trimming reference, not route calibration: input latency and clock drift remain unmeasured.
     * No device query, clock read, allocation or callback is added to EngineCore.render.
     */
    fun estimatedOutputNanos(engineFrame: Long): Long? {
        require(engineFrame >= 0)
        repeat(3) {
            val generation = engineView
            val device = attachedSink ?: return null
            if (statusValue.value.phase != DriverPhase.ATTACHED) return null
            val written = writtenFrame
            if (written < 0) return null
            val now = System.nanoTime()
            val pending = try { device.pendingFrames().coerceIn(0L, 48_000L) } catch (_: Exception) { return null }
            if (generation === engineView && device === attachedSink && written == writtenFrame) {
                val remaining = engineFrame - written + pending + MasterLimiter.LOOKAHEAD_FRAMES
                if (remaining !in -48_000L..720_000L) return null
                return now + remaining * 1_000_000_000L / EngineFormat.SAMPLE_RATE
            }
        }
        return null
    }

    /** Caller-owned storage; levels and immutable bus IDs come from one coherent rendered block. */
    fun copyMixerReadout(target: MixerSnapshot): Boolean {
        val view = engineView ?: return false
        if (!view.engine.mixerReadout.copyInto(target) || view !== engineView) return false
        target.frame += view.offset
        return true
    }

    fun liveChopOutput(): LiveChopOutput? = (liveChopProbe() as? LiveChopProbe.Ready)?.output

    /** One press-time reading; no device calls, allocation, or locks are added to render. */
    fun liveChopProbe(): LiveChopProbe {
        val snapshot = snapshots.get()
        var observedRoute: LiveChopRoute? = null
        repeat(3) {
            val route = liveChopRoute() ?: return LiveChopProbe.Unavailable
            if (observedRoute != null && observedRoute != route) return LiveChopProbe.Unavailable
            observedRoute = route
            val current = engineView ?: return LiveChopProbe.Unavailable
            val device = attachedSink ?: return LiveChopProbe.Unavailable
            if (current.identity !== route.engineClock || outputSession !== route.outputSession) return@repeat
            val written = writtenFrame
            if (written < 0 || !current.engine.readout.copyInto(snapshot)) return@repeat
            try {
                val now = System.nanoTime()
                val pending = device.pendingFrames()
                val frame = current.offset + snapshot.frame
                val remaining = frame - written + pending + MasterLimiter.LOOKAHEAD_FRAMES
                if (current === engineView && device === attachedSink && written == writtenFrame && route == liveChopRoute()) {
                    return LiveChopProbe.Ready(LiveChopOutput(route, now, frame, snapshot.originalSourceFrame,
                        snapshot.originalPlaying, if (pending >= 0 && remaining in 0L..48_000L)
                            remaining * 1_000_000_000L / EngineFormat.SAMPLE_RATE else null))
                }
            } catch (_: Exception) { return LiveChopProbe.Unavailable }
        }
        // A moving readout is not evidence that the device, its format or clock changed.
        // Keep no position or delay from an incoherent attempt; verify only the route once more.
        return observedRoute?.takeIf { it == liveChopRoute() }?.let { LiveChopProbe.Contended(it) } ?: LiveChopProbe.Unavailable
    }

    private fun liveChopRoute(): LiveChopRoute? {
        val current = engineView ?: return null
        val device = attachedSink ?: return null
        val session = outputSession
        if (statusValue.value.phase != DriverPhase.ATTACHED) return null
        return try {
            val epoch = device.timingEpoch()
            val rate = device.timingSampleRate()
            val channels = device.timingChannels()
            val encoding = device.encoding
            val buffer = device.bufferFrames().takeIf { it > 0 }
            if (rate != EngineFormat.SAMPLE_RATE || channels != 2) return null
            if (current !== engineView || device !== attachedSink || session !== outputSession ||
                epoch != device.timingEpoch() || buffer != device.bufferFrames().takeIf { it > 0 } ||
                rate != device.timingSampleRate() || channels != device.timingChannels() || encoding != device.encoding ||
                statusValue.value.phase != DriverPhase.ATTACHED) null
            else LiveChopRoute(session, current.identity, epoch, rate, channels, encoding == SinkEncoding.FLOAT32, buffer, blockFrames)
        } catch (_: Exception) {
            null
        }
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
    /** Coherent SOURCE completion evidence only; no fallback to a fresh, stale or partially copied snapshot. */
    fun originalPlaybackProbe(): OriginalPlaybackProbe {
        val current = engineView ?: return OriginalPlaybackProbe.Unavailable
        val session = outputSession
        if (statusValue.value.phase != DriverPhase.ATTACHED) return OriginalPlaybackProbe.Unavailable
        val snapshot = snapshots.get()
        val copied = current.engine.readout.copyInto(snapshot)
        if (current !== engineView || session !== outputSession || statusValue.value.phase != DriverPhase.ATTACHED)
            return OriginalPlaybackProbe.Unavailable
        if (!copied) return OriginalPlaybackProbe.Contended
        return OriginalPlaybackProbe.Ready(OriginalPlayback(snapshot.originalLoaded, snapshot.originalPlaying,
            snapshot.originalSourceFrame, snapshot.originalMonitorGain))
    }
    fun pcmPlayback(): PcmPlayback {
        val snapshot = snapshots.get()
        engineView?.engine?.readout?.copyInto(snapshot)
        return PcmPlayback(snapshot.pcmReadStatus, snapshot.pcmUnderrunFrames, snapshot.pcmDroppedRequests)
    }
    fun handPlayback(): HandPlayback {
        val snapshot = snapshots.get()
        engineView?.engine?.readout?.copyInto(snapshot)
        return HandPlayback(snapshot.handSourceFrame, snapshot.handMonitorGain)
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
                    routeGeneration++
                    outputSession = Any()
                    attachedSink = opened
                    writtenFrame = requireNotNull(engineView).offset + activeEngine.frame
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
        // The owner retains acknowledged targets, not intermediate ramp values. Refused,
        // cancelled or still-queued commands cannot become preferences after a reset.
        var originalMonitorTarget = 1f
        var songMonitorTarget = 1f
        var handMonitorTarget = 1f

        fun complete(request: Pending, accepted: Boolean, dropped: Boolean = false) {
            request.dropped = dropped; request.releasePcm(); request.answer.complete(accepted)
        }
        fun resetToEditingOnly(fault: DriverFault) {
            writtenFrame = -1
            if (activeEngine.recordingStartedFrame >= 0) completedCueBeforeReset =
                requireNotNull(engineView).offset + activeEngine.recordingStartedFrame
            routeGeneration++
            attachedSink = null
            try { sink?.close() } catch (_: Exception) { }
            sink = null
            // Unknown/late queued commands cannot fire after cancellation. Rebuild from confirmed
            // Program only, with no voices; the document remains in Studio and edits stay usable.
            val offset = requireNotNull(engineView).offset + activeEngine.frame
            activeEngine.close()
            activeEngine = EngineCore(confirmedProgram, monitorGains =
                MonitorGains(originalMonitorTarget, songMonitorTarget, handMonitorTarget))
            engineView = EngineView(activeEngine, offset)
            acknowledgedReadout = null
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
                        is EngineCommand.SwapProgram -> {
                            check(command.program.retainPcm())
                            val previous = confirmedProgram
                            confirmedProgram = command.program
                            previous.releasePcm()
                        }
                        is EngineCommand.StartSequence -> { sequenceStart = appliedFrame; stoppedElapsedFrames = 0 }
                        is EngineCommand.Stop, is EngineCommand.Panic -> stoppedElapsedFrames = (appliedFrame - sequenceStart).coerceAtLeast(0)
                        is EngineCommand.SetOriginalMonitorGain -> originalMonitorTarget = command.gain
                        is EngineCommand.SetSongMonitorGain -> songMonitorTarget = command.gain
                        is EngineCommand.SetHandMonitorGain -> handMonitorTarget = command.gain
                        else -> Unit
                    }
                    if (accepted) {
                        // Only this owner publishes readout, and render has returned: this copy cannot race.
                        check(activeEngine.readout.copyInto(request.readout.snapshot))
                        acknowledgedReadout = request.readout
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
                        if (written > 0) {
                            offset += written
                            // The device already counts a partial write in pendingFrames. Count it here too,
                            // otherwise a press between partial writes subtracts those frames twice.
                            writtenFrame = requireNotNull(engineView).offset + activeEngine.frame -
                                (byteCount - offset) / (2 * output.encoding.bytesPerSample)
                            lastProgress = System.nanoTime()
                        }
                        else {
                            if (System.nanoTime() - lastProgress > 500_000_000L) throw IllegalStateException("Audio sink stalled")
                            LockSupport.parkNanos(1_000_000)
                        }
                    }
                    if (offset == byteCount) writtenFrame = requireNotNull(engineView).offset + activeEngine.frame
                } catch (_: Exception) { resetToEditingOnly(DriverFault.WRITE_FAILED) }
            }
        } catch (_: Exception) {
            statusValue.value = DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.WRITE_FAILED, faults = ++faults)
        } finally {
            closed = true
            routeGeneration++
            attachedSink = null
            try { sink?.close() } catch (_: Exception) { }
            // A device still opening is closed as soon as it exists.
            opening?.let { pending -> opener.execute { try { pending.get().close() } catch (_: Exception) { } } }
            opener.shutdown()
            inFlight.forEach { it?.let { pending -> complete(pending, false) } }
            while (true) { val pending = requests.poll() ?: break; queued.decrementAndGet(); complete(pending, false) }
            activeEngine.close()
            outputMemory.close()
            confirmedProgram.releasePcm()
            confirmedProgram = EngineProgram.EMPTY
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
