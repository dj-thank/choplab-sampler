package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.model.Asset
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToLong

enum class PracticeAvailability { EDITABLE, BUSY, RECORDING }
enum class PracticePhase { EDITING, PREPARING, CANCELLING, PLAYING, STOPPING, CLOSED }
data class PracticePreviewState(val ownsSource: Boolean = false, val playing: Boolean = false, val problem: PracticeProblem? = null)
interface VocalPracticePorts {
    fun release() {}
    val renderer: VocalPracticeRenderer
    /** Reports this feature's claim only; a different preview owner must never be stopped by this adapter. */
    val previewState: StateFlow<PracticePreviewState>
    /** Host checks its serialized single-recording/preparation guard immediately before starting. */
    suspend fun prepareAllowed(expectedRevision: Long): PracticeResult<Unit>
    /** Existing SOURCE preview owner; loop has the prepared asset's exact period, no document edit. */
    suspend fun preview(asset: Asset, loop: Boolean, expectedRevision: Long): PracticeResult<Unit>
    fun requestStopPreview()
    /** Restores the document's original position/pitch/gain once, without automatically playing it. */
    suspend fun stopPreview(): PracticeResult<Unit>
}
data class VocalPracticeState(val startSeconds: String, val endSeconds: String, val speed: Double = 1.0,
    val loop: Boolean = true, val phase: PracticePhase = PracticePhase.EDITING,
    val progress: PracticeProgress? = null, val problem: PracticeProblem? = null, val closing: Boolean = false)

/** Session-only preparation and SOURCE ownership. No editing, Undo, save or export side effects. */
class VocalPracticeController(private val document: StateFlow<DocumentState>,
    private val availability: StateFlow<PracticeAvailability>, private val ports: VocalPracticePorts,
    scope: CoroutineScope, start: Long, end: Long) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutable = MutableStateFlow(VocalPracticeState((start / 48_000.0).toString(), (end / 48_000.0).toString()))
    val state = mutable.asStateFlow()
    private var work: Deferred<Boolean>? = null
    private var generation = 0L
    private var previewAttempted = false
    private var prepared: Triple<Long, VocalPracticeRequest, Asset>? = null
    init {
        jobs.launch { document.map { it.revision }.distinctUntilChanged().drop(1).collect { invalidate(PracticeProblem.STALE) } }
        jobs.launch { availability.collect { if (it != PracticeAvailability.EDITABLE) invalidate(if (it == PracticeAvailability.RECORDING) PracticeProblem.RECORDING else PracticeProblem.BUSY) } }
        jobs.launch { ports.previewState.collect { preview ->
            if (state.value.phase == PracticePhase.PLAYING && preview.problem != null)
                mutable.update { it.copy(phase = if (preview.ownsSource) PracticePhase.STOPPING else PracticePhase.EDITING, problem = preview.problem) }
            else if (!preview.ownsSource && state.value.phase == PracticePhase.PLAYING)
                mutable.update { it.copy(phase = PracticePhase.EDITING) }
            if (!preview.ownsSource && state.value.phase == PracticePhase.STOPPING && work?.isCompleted != false) restore()
        } }
    }
    fun update(change: (VocalPracticeState) -> VocalPracticeState) {
        if (state.value.phase == PracticePhase.EDITING && !state.value.closing) mutable.update { change(it).copy(problem = null) }
    }
    suspend fun preview(): Boolean {
        if (state.value.phase != PracticePhase.EDITING || state.value.closing || work?.isCompleted == false) return false
        val before = state.value
        val request = try {
            fun frame(text: String): Long = requireNotNull(text.toDoubleOrNull()).also { require(it.isFinite() && it in 0.0..1800.0) }.let { (it * 48_000).roundToLong() }
            VocalPracticeRequest(frame(before.startSeconds), frame(before.endSeconds), before.speed)
        } catch (_: IllegalArgumentException) { mutable.update { it.copy(problem = PracticeProblem.INVALID_RANGE) }; return false }
        val captured = document.value
        if (!mutable.compareAndSet(before, before.copy(phase = PracticePhase.PREPARING, problem = null, progress = null))) return false
        val token = ++generation
        val pending = jobs.async(start = CoroutineStart.LAZY) {
            var started = false
            try {
                if (token != generation) return@async false
                when (val guard = ports.prepareAllowed(captured.revision).also { currentCoroutineContext().ensureActive() }) {
                    is PracticeResult.Failure -> { mutable.update { it.copy(problem = guard.problem) }; return@async false }
                    is PracticeResult.Success -> Unit
                }
                currentCoroutineContext().ensureActive()
                val cached = prepared?.takeIf { it.first == captured.revision && it.second == request }?.third
                val asset = cached ?: when (val render = ports.renderer.render(captured.project, captured.revision, request) { progress ->
                    if (token == generation) mutable.update { it.copy(progress = progress) }
                }.also { currentCoroutineContext().ensureActive() }) {
                    is PracticeResult.Success -> render.value.also { prepared = Triple(captured.revision, request, it) }
                    is PracticeResult.Failure -> { mutable.update { it.copy(problem = render.problem) }; return@async false }
                }
                currentCoroutineContext().ensureActive()
                if (token != generation || document.value.revision != captured.revision) {
                    mutable.update { it.copy(problem = PracticeProblem.STALE) }; return@async false
                }
                when (val guard = ports.prepareAllowed(captured.revision).also { currentCoroutineContext().ensureActive() }) {
                    is PracticeResult.Failure -> { mutable.update { it.copy(problem = guard.problem) }; return@async false }
                    is PracticeResult.Success -> Unit
                }
                currentCoroutineContext().ensureActive()
                previewAttempted = true
                when (val preview = ports.preview(asset, before.loop, captured.revision).also { currentCoroutineContext().ensureActive() }) {
                    is PracticeResult.Failure -> mutable.update { it.copy(problem = preview.problem) }
                    is PracticeResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        if (token == generation && document.value.revision == captured.revision && ports.previewState.value.ownsSource) {
                            started = true; mutable.update { it.copy(phase = PracticePhase.PLAYING) }
                        } else mutable.update { it.copy(problem = if (document.value.revision != captured.revision) PracticeProblem.STALE else PracticeProblem.NO_OUTPUT) }
                    }
                }
                started
            } catch (_: CancellationException) { false }
              catch (_: Exception) { mutable.update { it.copy(problem = PracticeProblem.FAILED) }; false }
            finally { if (!started) withContext(NonCancellable) { restore() } }
        }
        work = pending; pending.start()
        return try { pending.await() } catch (cancel: CancellationException) { currentCoroutineContext().ensureActive(); false }
    }
    fun stop() = invalidate(PracticeProblem.CANCELLED)
    suspend fun stopAndJoin() { stop(); work?.join() }
    fun close() { if (state.value.phase != PracticePhase.CLOSED && (!state.value.closing || work?.isCompleted != false)) {
        mutable.update { it.copy(closing = true) }; invalidate(PracticeProblem.CANCELLED)
    } }
    suspend fun closeAndJoin() { close(); work?.join() }
    private fun invalidate(problem: PracticeProblem) {
        if (state.value.phase == PracticePhase.CLOSED) return
        if (state.value.phase in setOf(PracticePhase.CANCELLING, PracticePhase.STOPPING) && work?.isCompleted == false) return
        generation++; prepared = null
        if (previewAttempted) ports.requestStopPreview()
        val pending = work
        val wasRunning = pending?.isCompleted == false
        mutable.update { it.copy(phase = if (wasRunning) PracticePhase.CANCELLING else PracticePhase.STOPPING, problem = problem) }
        pending?.cancel()
        // Enter cleanup before a second Close/Stop can cancel an undispatched coroutine. A cancelled
        // lazy preparation may never enter its own finally block, so joining it alone is insufficient.
        work = jobs.async(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                pending?.join()
                if (!wasRunning || state.value.phase == PracticePhase.CANCELLING) restore()
            }
            false
        }
    }
    private suspend fun restore() {
        val result = try { if (previewAttempted) ports.stopPreview() else PracticeResult.Success(Unit) }
            catch (_: Exception) { PracticeResult.Failure(PracticeProblem.RESTORE_FAILED) }
        val restored = result is PracticeResult.Success && (!previewAttempted || !ports.previewState.value.ownsSource)
        if (restored) previewAttempted = false
        mutable.update { it.copy(phase = if (!restored) PracticePhase.STOPPING else if (it.closing) PracticePhase.CLOSED else PracticePhase.EDITING,
            problem = if (!restored) PracticeProblem.RESTORE_FAILED else it.problem) }
        if (restored && state.value.closing) { ports.release(); work?.invokeOnCompletion { owner.cancel() } }
    }
}
