package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** Worker/control orchestration. The native frame gate itself remains on VoiceRecorder's capture thread. */
class VocalPunchCapture(private val studio: Studio, private val engine: StreamingEnginePort,
                        private val voice: VoiceTakes, private val permission: suspend () -> Boolean = { true },
                        private val inputFailure: () -> PunchProblem = { PunchProblem.NO_INPUT },
                        private val cancelOpening: () -> Unit = voice::cancelOpening) : VocalPunchPort {
    private val mutex = Mutex()
    private val stop = AtomicBoolean()
    private val lost = AtomicBoolean()
    @Volatile private var opener: Deferred<VoiceTakes.Start>? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(VocalPunchProgress())
    override val progress = mutable.asStateFlow()
    override fun requestStop() { stop.set(true); cancelOpening(); opener?.cancel() }
    override fun interrupt() { lost.set(true); requestStop() }

    override suspend fun capture(project: Project, expectedRevision: Long, request: VocalPunchRequest, stopped: () -> Boolean): VocalPunchResult = coroutineScope {
        if (!mutex.tryLock()) return@coroutineScope VocalPunchResult(problem = PunchProblem.BUSY)
        stop.set(false); lost.set(false)
        fun stopping() = stop.get() || stopped()
        var inputOwned = false
        var opening: Deferred<VoiceTakes.Start>? = null
        var problem: PunchProblem? = null
        var captured: VocalCapturedSession? = null
        var alignment = RecordingAlignment.unmeasured()
        val previousTarget = studio.selection.value.playbackTarget
        var ownedTarget: PlaybackTarget.Arrangement? = null
        var ownedCue = -1L
        suspend fun send(action: Action) = studio.dispatch(action).accepted
        try {
            val plan = try { request.plan(project) } catch (_: IllegalArgumentException) { return@coroutineScope VocalPunchResult(problem = PunchProblem.INVALID) }
            // Negative adjustment cannot ask the input to capture before the session's pre-roll starts.
            if (plan.framesBeforeCapture + request.manualFrames48k < 0 || plan.captureStart + request.manualFrames48k < 0)
                return@coroutineScope VocalPunchResult(problem = PunchProblem.INVALID)
            mutable.value = VocalPunchProgress(PunchPhase.OPENING, 0, request.passes)
            if (studio.document.value.revision != expectedRevision) return@coroutineScope VocalPunchResult(problem = PunchProblem.STALE)
            if (!engine.health().attached) return@coroutineScope VocalPunchResult(problem = PunchProblem.NO_OUTPUT)
            val target = PlaybackTarget.Arrangement(minimumFrames = plan.captureEnd + request.manualFrames48k.coerceAtLeast(0))
            if (!send(Action.Silence) || !send(Action.SelectPlaybackTarget(target))) return@coroutineScope VocalPunchResult(problem = PunchProblem.NO_OUTPUT)
            ownedTarget = target
            if (stopping()) return@coroutineScope VocalPunchResult(problem = PunchProblem.CANCELLED)
            val seconds = ((plan.captureFrames * request.passes + 47_999) / 48_000).toInt()
            var denied = false
            val pending = async {
                if (!permission()) { denied = true; VoiceTakes.Start.NO_INPUT }
                else voice.start(seconds, waitForCue = true, VoiceCaptureWindow(plan.captureFrames, plan.armingSeconds), request.passes)
            }
            opening = pending; opener = pending
            if (stopping()) pending.cancel()
            val started = try { pending.await() } catch (cancel: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancel
                null
            } finally { opener = null }
            // Native cancellation may finish the open with NO_INPUT before the Deferred observes it.
            if (stopping() && started != VoiceTakes.Start.STARTED)
                return@coroutineScope VocalPunchResult(problem = PunchProblem.CANCELLED)
            when (started) {
                VoiceTakes.Start.STARTED -> inputOwned = true
                VoiceTakes.Start.NO_ROOM -> return@coroutineScope VocalPunchResult(problem = PunchProblem.NO_ROOM)
                VoiceTakes.Start.NO_INPUT -> return@coroutineScope VocalPunchResult(problem = if (denied) PunchProblem.PERMISSION else inputFailure())
                null -> return@coroutineScope VocalPunchResult(problem = PunchProblem.CANCELLED)
            }
            val output = engine.health()
            val outputGeneration = engine.outputRouteGeneration()
            val inputRevision = voice.inputRouteRevision
            val route = AudioRouteIdentity(++generation, requireNotNull(voice.inputRate), output.sampleRate,
                voice.inputBufferFrames?.takeIf { it in 1..192_000 }, output.bufferFrames?.takeIf { it in 1..192_000 },
                AudioClockDomain.HOST_MONOTONIC, AudioClockDomain.HOST_MONOTONIC)
            alignment = if (request.manualFrames48k == 0) alignment.estimated(route, 0) else alignment.manual(route, request.manualFrames48k)
            fun currentRoute(): Boolean {
                val now = engine.health()
                return !lost.get() && engine.outputRouteGeneration() == outputGeneration && now.attached &&
                    now.sampleRate == output.sampleRate && now.blockFrames == output.blockFrames && now.bufferFrames == output.bufferFrames &&
                    voice.inputRouteRevision == inputRevision && voice.inputRate == route.inputRate && voice.inputBufferFrames == route.inputBufferFrames
            }
            for (pass in 1..request.passes) {
                if (stopping()) break
                if (!currentRoute()) { problem = PunchProblem.INTERRUPTED; break }
                if (studio.document.value.revision != expectedRevision) { problem = PunchProblem.STALE; break }
                mutable.value = VocalPunchProgress(PunchPhase.PRE_ROLL, pass, request.passes, alignment)
                if (!send(Action.Pause) || !send(Action.Seek(plan.playbackStart)) || !send(Action.CountInAndResume(if (pass == 1) request.countInBars else 0))) {
                    problem = PunchProblem.CUE; break
                }
                ownedCue = studio.transport.value.recordingStartFrame
                val cue = ownedCue + plan.framesBeforeCapture + request.manualFrames48k
                var cued = false
                while (!stopping() && !voice.armingTimedOut) {
                    if (!currentRoute()) { problem = PunchProblem.INTERRUPTED; break }
                    if (studio.document.value.revision != expectedRevision) { problem = PunchProblem.STALE; break }
                    if (!cued) engine.estimatedOutputNanos(cue)?.let { cued = voice.cueAt(it) }
                    if (cued && engine.snapshot().frame >= cue) mutable.value = mutable.value.copy(phase = PunchPhase.CAPTURING)
                    if (voice.completedPasses >= pass || voice.interrupted || voice.full) break
                    if (engine.snapshot().recordingStartFrame < 0) { problem = PunchProblem.CUE; break }
                    delay(10)
                }
                if (voice.armingTimedOut) problem = PunchProblem.CUE
                if (voice.interrupted) problem = PunchProblem.INTERRUPTED
                if (voice.full && voice.completedPasses < pass) problem = PunchProblem.NO_ROOM
                if (problem != null) break
            }
            if (!currentRoute() || problem == PunchProblem.INTERRUPTED) {
                alignment = alignment.routeChanged(route.copy(generation = ++generation)); problem = PunchProblem.INTERRUPTED
            }
        } catch (cancel: CancellationException) { problem = PunchProblem.CANCELLED }
          catch (_: PcmMemoryLimit) { problem = PunchProblem.NO_ROOM }
          catch (_: Exception) { problem = PunchProblem.SAVE_FAILED }
        finally {
            withContext(NonCancellable) {
                try {
                    opening?.cancelAndJoin()
                    mutable.value = mutable.value.copy(phase = PunchPhase.SAVING, alignment = alignment)
                    val target = ownedTarget
                    val transport = engine.snapshot()
                    val ownsClock = target != null && studio.selection.value.playbackTarget == target && studio.document.value.project.id == project.id &&
                        (studio.document.value.revision == expectedRevision || (ownedCue >= 0 && transport.recordingStartFrame == ownedCue) ||
                            (ownedCue < 0 && !transport.playing))
                    if (ownsClock) send(Action.Pause)
                    if (inputOwned) captured = try { voice.stopPunch("VOICE") } catch (_: Exception) { problem = PunchProblem.SAVE_FAILED; null }
                    // Only restore this session's clock; a concurrently opened document keeps its own selection.
                    if (ownsClock) send(Action.SelectPlaybackTarget(if (studio.document.value.revision == expectedRevision) previousTarget else requireNotNull(target).copy(minimumFrames = 0)))
                } finally { mutable.value = mutable.value.copy(phase = PunchPhase.IDLE, alignment = alignment); mutex.unlock() }
            }
        }
        VocalPunchResult(captured, alignment, problem ?: if (captured == null) if (stopping()) PunchProblem.CANCELLED else PunchProblem.EMPTY else null)
    }
}
