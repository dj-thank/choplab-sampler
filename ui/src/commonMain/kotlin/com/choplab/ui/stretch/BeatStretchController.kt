package com.choplab.ui.stretch

import com.choplab.core.DocumentState
import com.choplab.core.ai.VocalPreviewPort
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.ui.vocal.VocalAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

interface BeatStretchHost {
    val preview: VocalPreviewPort
    suspend fun render(project: Project, draft: StretchDraft, progress: (Int, Int) -> Unit): Asset
    suspend fun original(project: Project, draft: StretchDraft): Asset
}
interface BeatStretchPorts {
    val previewing: StateFlow<Boolean>
    /** Read the current SOURCE owner when a projected stop notification may be late. */
    fun isPreviewing(): Boolean = previewing.value
    suspend fun render(project: Project, draft: StretchDraft, progress: (Int, Int) -> Unit): Asset
    suspend fun original(project: Project, draft: StretchDraft): Asset
    suspend fun preview(asset: Asset, revision: Long): Boolean
    suspend fun stopPreview(): Boolean
    fun cancelPreview()
    suspend fun apply(intent: Intent, revision: Long): Boolean
}
enum class StretchPhase { EDITING, PREPARING, APPLYING, CLOSED }
enum class StretchAudition { NONE, ORIGINAL, STRETCHED }
data class StretchState(val project: Project, val revision: Long, val target: StretchTarget, val sourceBpm: String = "",
                        val phase: StretchPhase = StretchPhase.EDITING, val availability: VocalAvailability = VocalAvailability.EDITABLE,
                        val problem: StretchProblem? = null, val progress: Int = 0, val prepared: Boolean = false,
                        val audition: StretchAudition = StretchAudition.NONE, val applied: Boolean = false) {
    val editable get() = phase == StretchPhase.EDITING && availability == VocalAvailability.EDITABLE && problem != StretchProblem.STALE
    val saved get(): BeatStretch? {
        val pad = if (target.kind == StretchKind.PAD) project.pads[target.id.toInt()] else null
        val clip = if (target.kind == StretchKind.CLIP) project.clips.firstOrNull { it.id == target.id } else null
        val hash = pad?.assetHash ?: clip?.assetHash
        val range = pad?.range ?: clip?.range
        return project.beatStretches.firstOrNull { it.target == target &&
            ((hash == it.sourceAssetHash && range == it.sourceRange) || (hash == it.renderedAssetHash && range == it.renderedRange)) }
    }
    val name get() = if (target.kind == StretchKind.PAD) project.pads[target.id.toInt()].name else
        project.clips.firstOrNull { it.id == target.id }?.let { project.asset(it.assetHash).name } ?: ""
    fun milliBpm(): Int? = sourceBpm.toDoubleOrNull()?.takeIf { it.isFinite() && it in 40.0..240.0 }?.let { (it * 1000).roundToInt() }
}
sealed interface StretchAction {
    data class Bpm(val text: String) : StretchAction
    data object Prepare : StretchAction
    data object Original : StretchAction
    data object Stretched : StretchAction
    data object Apply : StretchAction
    data object Cancel : StretchAction
    data object Reload : StretchAction
}

class BeatStretchController(private val document: StateFlow<DocumentState>, private val availability: StateFlow<VocalAvailability>,
                            private val ports: BeatStretchPorts, scope: CoroutineScope, private val target: StretchTarget) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val closed = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    private val work = MutableStateFlow<Job?>(null)
    @Volatile private var prepared: Pair<StretchDraft, Asset>? = null
    private val mutable = MutableStateFlow(snapshot())
    val state = mutable.asStateFlow()
    init {
        jobs.launch { document.collect { value -> mutex.withLock {
            if (!closed.value && value.revision != state.value.revision && state.value.phase != StretchPhase.APPLYING) {
                invalidate(); mutable.value = state.value.copy(phase = StretchPhase.EDITING, problem = StretchProblem.STALE, prepared = false, audition = StretchAudition.NONE)
            }
        } } }
        jobs.launch { availability.collect { value -> mutex.withLock {
            if (!closed.value) {
                mutable.value = state.value.copy(availability = value)
                if (value != VocalAvailability.EDITABLE && state.value.phase != StretchPhase.APPLYING) {
                    invalidate(); mutable.value = state.value.copy(phase = StretchPhase.EDITING, problem = blocked(value), prepared = false, audition = StretchAudition.NONE)
                }
            }
        } } }
        jobs.launch { ports.previewing.collect { active -> if (!active) mutex.withLock {
            if (!ports.isPreviewing()) mutable.update { it.copy(audition = StretchAudition.NONE) }
        } } }
    }
    suspend fun dispatch(action: StretchAction): Boolean {
        if (action is StretchAction.Bpm || action == StretchAction.Cancel || action == StretchAction.Reload) return mutex.withLock {
            if (closed.value || state.value.phase == StretchPhase.APPLYING) return@withLock false
            if (action == StretchAction.Cancel) {
                invalidate(); mutable.value = state.value.copy(phase = StretchPhase.EDITING, prepared = false, audition = StretchAudition.NONE, problem = null); return@withLock true
            }
            if (action == StretchAction.Reload) {
                invalidate(); mutable.value = snapshot(); return@withLock true
            }
            if (!guard()) return@withLock false
            action as StretchAction.Bpm
            if (action.text.length > 24) return@withLock false
            invalidate(); mutable.value = state.value.copy(sourceBpm = action.text, prepared = false, applied = false, audition = StretchAudition.NONE, problem = null)
            true
        }
        val pending = mutex.withLock {
            if (!guard()) return false
            val value = state.value
            val bpm = value.milliBpm() ?: run { mutable.value = value.copy(problem = StretchProblem.INVALID_INPUT); return false }
            val draft = try { BeatStretchEdits.draft(DocumentState(value.project, value.revision), target, bpm) }
                catch (failure: StretchException) { mutable.value = value.copy(problem = failure.problem); return false }
            if ((action == StretchAction.Apply || action == StretchAction.Stretched) && prepared?.first != draft) {
                mutable.value = value.copy(problem = StretchProblem.INVALID_INPUT); return false
            }
            val token = generation.updateAndGet { it + 1 }
            ports.cancelPreview()
            mutable.value = value.copy(phase = StretchPhase.PREPARING, problem = null, progress = 0, applied = false, audition = StretchAudition.NONE)
            val task = jobs.async(start = CoroutineStart.LAZY) {
                if (!ports.stopPreview()) throw StretchException(StretchProblem.PREVIEW_FAILED)
                current(token, value.revision)
                when (action) {
                    StretchAction.Prepare -> {
                        val asset = ports.render(value.project, draft) { at, total ->
                            if (generation.value == token && !closed.value) mutable.update { it.copy(progress = (at.toLong() * 100 / total).toInt()) }
                        }
                        // Validate the complete proposal before it can be auditioned, without editing the document.
                        BeatStretchEdits.apply(DocumentState(value.project, value.revision), draft, asset)
                        mutex.withLock { current(token, value.revision); prepared = draft to asset; mutable.value = state.value.copy(prepared = true) }
                        StretchAudition.NONE
                    }
                    StretchAction.Apply -> {
                        val intent = BeatStretchEdits.apply(document.value, draft, requireNotNull(prepared).second)
                        mutex.withLock { current(token, value.revision); mutable.value = state.value.copy(phase = StretchPhase.APPLYING) }
                        if (!ports.apply(intent, value.revision)) throw StretchException(StretchProblem.FAILED)
                        StretchAudition.NONE
                    }
                    else -> {
                        val original = action == StretchAction.Original
                        val asset = if (original || draft.sourceMilliBpm == draft.targetMilliBpm) ports.original(value.project, draft) else requireNotNull(prepared).second
                        current(token, value.revision)
                        if (!ports.preview(asset, value.revision)) throw StretchException(StretchProblem.PREVIEW_FAILED)
                        if (original) StretchAudition.ORIGINAL else StretchAudition.STRETCHED
                    }
                }
            }
            work.value = task
            Triple(token, task, value.revision)
        }
        val (token, task, revision) = pending
        var failure: StretchProblem? = null
        val result = try { task.start(); task.await() }
            catch (cancel: CancellationException) {
                task.cancel()
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) { mutex.withLock {
                        if (generation.value == token && !closed.value) { invalidate(); mutable.value = state.value.copy(phase = StretchPhase.EDITING, prepared = false) }
                    } }; throw cancel
                }
                null
            }
            catch (error: StretchException) { failure = error.problem; null }
            catch (_: Exception) { failure = StretchProblem.FAILED; null }
        return mutex.withLock {
            if (closed.value || generation.value != token) return@withLock false
            work.compareAndSet(task, null)
            if (result == null) {
                ports.cancelPreview(); prepared = null
                mutable.value = state.value.copy(phase = StretchPhase.EDITING, prepared = false, audition = StretchAudition.NONE, problem = when {
                    document.value.revision != revision -> StretchProblem.STALE
                    availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
                    else -> failure ?: StretchProblem.FAILED
                }); false
            } else if (state.value.phase == StretchPhase.APPLYING) {
                prepared = null; mutable.value = snapshot().copy(applied = true); true
            } else {
                mutable.value = state.value.copy(phase = StretchPhase.EDITING,
                    audition = result.takeIf { ports.isPreviewing() } ?: StretchAudition.NONE); true
            }
        }
    }
    private fun guard(): Boolean {
        val problem = when {
            closed.value -> return false
            document.value.revision != state.value.revision -> StretchProblem.STALE
            availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
            state.value.phase != StretchPhase.EDITING -> StretchProblem.BUSY
            else -> null
        }
        if (problem != null) mutable.value = state.value.copy(problem = problem)
        return problem == null
    }
    private fun current(token: Long, revision: Long) {
        if (closed.value || generation.value != token || document.value.revision != revision || availability.value != VocalAvailability.EDITABLE)
            throw CancellationException("Stretch became stale")
    }
    private fun blocked(value: VocalAvailability) = if (value == VocalAvailability.RECORDING) StretchProblem.RECORDING else StretchProblem.BUSY
    private fun snapshot(): StretchState {
        val doc = document.value
        val state = StretchState(doc.project, doc.revision, target, availability = availability.value)
        return state.copy(sourceBpm = state.saved?.let { (it.sourceMilliBpm / 1000.0).toString() } ?: "")
    }
    private fun invalidate() { generation.update { it + 1 }; work.getAndUpdate { null }?.cancel(); prepared = null; ports.cancelPreview() }
    fun close() { if (closed.compareAndSet(false, true)) { invalidate(); owner.cancel(); mutable.value = state.value.copy(phase = StretchPhase.CLOSED, prepared = false) } }
}
