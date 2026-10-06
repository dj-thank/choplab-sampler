package com.choplab.ui.chop

import com.choplab.core.DocumentState
import com.choplab.core.ai.VocalPreviewOwner
import com.choplab.core.ai.VocalPreviewPhase
import com.choplab.core.ai.VocalPreviewState
import com.choplab.core.chop.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AutoChopAvailability { EDITABLE, BUSY, RECORDING }
data class AutoChopState(val source: Source, val sampleRate: Int, val settings: AutoChopSettings = AutoChopSettings(),
                         val markers: List<Long>? = null, val selectedSlice: Int = 0, val working: Boolean = false,
                         val applying: Boolean = false, val problem: AutoChopProblem? = null, val closed: Boolean = false,
                         val previewPhase: VocalPreviewPhase = VocalPreviewPhase.IDLE) {
    val slices: List<FrameRange> get() = markers?.let { (listOf(source.range.start) + it + source.range.end)
        .zipWithNext { a, b -> FrameRange(a, b) } }.orEmpty()
    val canApply get() = markers != null && markers != source.markers && !working && !applying && problem == null && !closed
}
sealed interface AutoChopAction {
    data class Settings(val value: AutoChopSettings) : AutoChopAction
    data object Prepare : AutoChopAction
    data class SelectSlice(val index: Int) : AutoChopAction
    data object Preview : AutoChopAction
    data object StopPreview : AutoChopAction
    data object Apply : AutoChopAction
    data object Cancel : AutoChopAction
}
interface AutoChopActions {
    val previewState: StateFlow<VocalPreviewState>? get() = null
    /** Each callback rechecks the same revision and recording/work state at the host's serialized boundary. */
    suspend fun preview(asset: Asset, range: FrameRange, revision: Long): AutoChopProblem?
    suspend fun stopPreview(): Boolean
    fun requestStopPreview()
    suspend fun apply(source: Source, markers: FrozenList<Long>, revision: Long): AutoChopProblem?
}

/** Ephemeral proposal; cancellation fences even an uncooperative worker, and commit uses exactly one Studio edit. */
class AutoChopController(private val documents: StateFlow<DocumentState>, private val availability: StateFlow<AutoChopAvailability>,
                         private val worker: AutoChopPort?, private val actions: AutoChopActions, scope: CoroutineScope) {
    private val captured = documents.value
    private val source = requireNotNull(captured.project.source)
    private val asset = captured.project.asset(source.assetHash)
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val controls = Mutex()
    private val mutable = MutableStateFlow(AutoChopState(source, asset.sampleRate))
    val state = mutable.asStateFlow()
    val attackAvailable get() = worker != null
    private var generation = 0L
    private var pending: Job? = null

    init {
        actions.previewState?.let { preview -> jobs.launch {
            var owned = false
            preview.collect { value -> controls.withLock {
                if (state.value.closed) return@withLock
                if (value.owner == VocalPreviewOwner.CHOP) {
                    owned = true
                    mutable.value = state.value.copy(previewPhase = value.phase,
                        problem = if (value.failure != null) AutoChopProblem.FAILED else state.value.problem)
                } else if (owned) {
                    owned = false
                    mutable.value = state.value.copy(previewPhase = value.phase,
                        problem = if (value.failure != null) AutoChopProblem.FAILED else state.value.problem)
                }
            } }
        } }
        jobs.launch {
            combine(documents, availability) { d, a -> d to a }.collect { (document, available) ->
                controls.withLock {
                    if (state.value.closed || state.value.applying) return@withLock
                    val problem = if (document.revision != captured.revision || document.project.id != captured.project.id)
                        AutoChopProblem.STALE else when (available) {
                        AutoChopAvailability.EDITABLE -> null
                        AutoChopAvailability.BUSY -> AutoChopProblem.BUSY
                        AutoChopAvailability.RECORDING -> AutoChopProblem.RECORDING
                    }
                    if (problem != null) {
                        cancelPending(); actions.requestStopPreview()
                        mutable.value = state.value.copy(markers = null, problem = problem)
                    }
                }
            }
        }
    }
    private fun blocked(): AutoChopProblem? = when {
        state.value.closed -> AutoChopProblem.CLOSED
        documents.value.revision != captured.revision || documents.value.project != captured.project -> AutoChopProblem.STALE
        availability.value == AutoChopAvailability.RECORDING -> AutoChopProblem.RECORDING
        availability.value == AutoChopAvailability.BUSY -> AutoChopProblem.BUSY
        else -> null
    }
    private fun refuse(problem: AutoChopProblem): Boolean { mutable.value = state.value.copy(problem = problem); return false }
    private fun cancelPending() { generation++; pending?.cancel() }

    suspend fun dispatch(action: AutoChopAction): Boolean {
        // A stop must fence decoder preparation before it waits for a commit/preview acknowledgement.
        if (action == AutoChopAction.Cancel || action == AutoChopAction.StopPreview) actions.requestStopPreview()
        return controls.withLock {
            when (action) {
                AutoChopAction.Cancel -> {
                    if (state.value.applying) return@withLock false
                    cancelPending()
                    if (!actions.stopPreview()) return@withLock refuse(AutoChopProblem.FAILED)
                    mutable.value = state.value.copy(closed = true, markers = null)
                    owner.cancel(); true
                }
                AutoChopAction.StopPreview -> actions.stopPreview()
                is AutoChopAction.Settings -> {
                    if (state.value.applying || state.value.closed) return@withLock false
                    cancelPending(); actions.requestStopPreview()
                    mutable.value = state.value.copy(settings = action.value, markers = null, selectedSlice = 0, problem = blocked())
                    true
                }
                is AutoChopAction.SelectSlice -> {
                    if (action.index !in state.value.slices.indices || state.value.applying) return@withLock false
                    actions.requestStopPreview()
                    mutable.value = state.value.copy(selectedSlice = action.index); true
                }
                AutoChopAction.Prepare -> {
                    blocked()?.let { return@withLock refuse(it) }
                    if (pending?.isCompleted == false || state.value.applying) return@withLock refuse(AutoChopProblem.BUSY)
                    if (!actions.stopPreview()) return@withLock refuse(AutoChopProblem.FAILED)
                    val settings = state.value.settings
                    val token = ++generation
                    mutable.value = state.value.copy(working = true, markers = null, selectedSlice = 0, problem = null)
                    pending = jobs.launch {
                        try {
                            val result = if (settings.mode == AutoChopMode.EQUAL) AutoChop.equal(source.range, settings.slices)
                                else worker?.prepare(asset, source.range, settings) ?: AutoChopResult.Refused(AutoChopProblem.UNAVAILABLE)
                            currentCoroutineContext().ensureActive()
                            controls.withLock {
                                if (token != generation || state.value.closed) return@withLock
                                val problem = blocked()
                                if (problem != null) refuse(problem) else when (result) {
                                    is AutoChopResult.Ready -> {
                                        // Validate an adapter result before it can be displayed or applied.
                                        source.copy(markers = result.markers)
                                        mutable.value = state.value.copy(markers = result.markers, problem = null)
                                    }
                                    is AutoChopResult.Refused -> refuse(result.problem)
                                }
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { controls.withLock { if (token == generation) refuse(AutoChopProblem.FAILED) } }
                        finally { withContext(NonCancellable) { controls.withLock { mutable.value = state.value.copy(working = false) } } }
                    }
                    true
                }
                AutoChopAction.Preview -> {
                    blocked()?.let { return@withLock refuse(it) }
                    val slice = state.value.slices.getOrNull(state.value.selectedSlice) ?: return@withLock false
                    val problem = actions.preview(asset, slice, captured.revision)
                    if (problem != null) refuse(problem) else { mutable.value = state.value.copy(problem = null); true }
                }
                AutoChopAction.Apply -> {
                    blocked()?.let { return@withLock refuse(it) }
                    if (!state.value.canApply) return@withLock false
                    if (!actions.stopPreview()) return@withLock refuse(AutoChopProblem.FAILED)
                    mutable.value = state.value.copy(applying = true)
                    val problem = try { actions.apply(source, requireNotNull(state.value.markers).frozen(), captured.revision) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { AutoChopProblem.FAILED }
                    finally { mutable.value = state.value.copy(applying = false) }
                    if (problem == null) { mutable.value = state.value.copy(closed = true); owner.cancel(); true } else refuse(problem)
                }
            }
        }
    }

    fun cancel(problem: AutoChopProblem? = null) {
        actions.requestStopPreview()
        jobs.launch { controls.withLock {
            cancelPending(); mutable.value = state.value.copy(markers = null, problem = problem)
        } }
    }
}
