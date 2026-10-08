package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

enum class VocalAvailability { EDITABLE, BUSY, RECORDING }
enum class VocalPhase { EDITING, RENDERING, APPLYING, CLOSED }
/** One host-owned SOURCE preview, shared with the speech guide. */
interface VocalTakePort {
    val preview: com.choplab.core.ai.VocalPreviewPort
    suspend fun render(project: Project, draft: VocalCompDraft, name: String): Asset
}

interface VocalTakePorts {
    /** The shared SOURCE state, read again under the admission lock to reject queued old notifications. */
    val previewState: StateFlow<VocalPreviewState>? get() = null
    suspend fun render(project: Project, draft: VocalCompDraft, name: String): Asset?
    /** Host serializes its final recording/busy check with the Studio expectedRevision dispatch. */
    suspend fun apply(intent: Intent, expectedRevision: Long): Boolean
    /** Preview is a session, never a document edit; host restores the ordinary program when it ends. */
    suspend fun previewTake(project: Project, takeId: String): Boolean
    suspend fun previewComp(asset: Asset): Boolean
    /** Idempotent, also cancels preparation so a late preview cannot start after close. */
    fun stopPreview()
}
data class VocalTakeState(
    val project: Project,
    val revision: Long,
    val selectedTake: String? = project.takes.firstOrNull()?.id,
    val draft: VocalCompDraft? = null,
    val replaceClipIds: Set<String> = emptySet(),
    val phase: VocalPhase = VocalPhase.EDITING,
    val availability: VocalAvailability = VocalAvailability.EDITABLE,
    val problem: VocalProblem? = null,
    val previewing: Boolean = false,
    val applied: Boolean = false,
    val punchRange: Pair<Long, Long>? = null,
) {
    val editable: Boolean get() = phase == VocalPhase.EDITING && availability == VocalAvailability.EDITABLE && problem != VocalProblem.STALE
    val replaceableClips: List<Clip> get() {
        val current = draft ?: return emptyList()
        val hashes = current.segments.mapNotNull { line -> project.takes.firstOrNull { it.id == line.takeId }?.assetHash }.toSet() +
            listOfNotNull(project.vocalComps.firstOrNull { it.id == current.replacing }?.renderedAssetHash)
        return project.clips.filter { it.assetHash in hashes }
    }
}
sealed interface VocalAction {
    data class SelectTake(val id: String) : VocalAction
    data class SelectComp(val id: String) : VocalAction
    data object SplicePunch : VocalAction
    data object WholeTake : VocalAction
    data object FromLyrics : VocalAction
    data class Choose(val segmentId: String, val takeId: String) : VocalAction
    data class ReplaceClip(val id: String, val replace: Boolean) : VocalAction
    data object PreviewTake : VocalAction
    data class PreviewComp(val name: String) : VocalAction
    data class Apply(val name: String) : VocalAction
    data object StopPreview : VocalAction
    data object Cancel : VocalAction
    data object Reload : VocalAction
}

/** One uncommitted selection plan. Rendering, preview and Cancel never modify the Project. */
class VocalTakeController(
    private val document: StateFlow<DocumentState>,
    private val availability: StateFlow<VocalAvailability>,
    private val ports: VocalTakePorts,
    scope: CoroutineScope,
    private val punchRange: Pair<Long, Long>? = null,
) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(VocalTakeState(document.value.project, document.value.revision, availability = availability.value, punchRange = punchRange))
    val state: StateFlow<VocalTakeState> = mutable.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var work: Job? = null
    private var prepared: Pair<VocalCompDraft, Asset>? = null
    private var trackingPreview = false
    init {
        jobs.launch { document.collect { mutex.withLock {
            if (!closed && document.value.revision != state.value.revision && state.value.phase != VocalPhase.APPLYING) {
                invalidate(); publish(state.value.copy(phase = VocalPhase.EDITING, problem = VocalProblem.STALE))
            }
        } } }
        jobs.launch { availability.collect { current -> mutex.withLock {
            if (!closed) {
                publish(state.value.copy(availability = current))
                if (current != VocalAvailability.EDITABLE && state.value.phase != VocalPhase.APPLYING) {
                    invalidate(); publish(state.value.copy(phase = VocalPhase.EDITING, problem = blocked(current)))
                }
            }
        } } }
        ports.previewState?.let { preview -> jobs.launch { preview.collect { mutex.withLock {
            val current = preview.value
            if (!closed && (trackingPreview || state.value.previewing || current.owner == VocalPreviewOwner.TAKE)) {
                // RESTORING is already silent, but keep following our claim until its terminal result.
                trackingPreview = current.ownsSource && current.owner == VocalPreviewOwner.TAKE
                publish(state.value.copy(previewing = previewActive(current),
                    problem = if (current.phase == VocalPreviewPhase.FAILED && state.value.problem != VocalProblem.STALE &&
                        (current.owner == null || current.owner == VocalPreviewOwner.TAKE)) VocalProblem.PREVIEW_FAILED else state.value.problem))
            }
        } } } }
    }

    private fun previewActive(value: VocalPreviewState) = value.owner == VocalPreviewOwner.TAKE &&
        value.phase in listOf(VocalPreviewPhase.LOADING, VocalPreviewPhase.PLAYING)

    suspend fun dispatch(action: VocalAction): Boolean {
        if (action is VocalAction.Apply) return prepare(action.name, apply = true)
        if (action is VocalAction.PreviewComp) return prepare(action.name, apply = false)
        if (action == VocalAction.PreviewTake) return previewTake()
        return mutex.withLock {
            if (closed) return@withLock false
            val current = state.value
            if (action == VocalAction.StopPreview) {
                // Stop also fences an unfinished comp/take render, not only an already acquired SOURCE lane.
                if (current.phase == VocalPhase.APPLYING) ports.stopPreview() else invalidate()
                publish(current.copy(phase = if (current.phase == VocalPhase.RENDERING) VocalPhase.EDITING else current.phase, previewing = false))
                return@withLock true
            }
            if (action == VocalAction.Cancel) {
                if (current.phase == VocalPhase.APPLYING) return@withLock false
                invalidate(); publish(current.copy(draft = null, replaceClipIds = emptySet(), phase = VocalPhase.EDITING, problem = null, previewing = false)); return@withLock true
            }
            if (action == VocalAction.Reload) {
                if (current.phase != VocalPhase.EDITING) return@withLock false
                invalidate(); reset(); return@withLock true
            }
            if (!guard()) return@withLock false
            try {
                var next = current.copy(problem = null, applied = false)
                when (action) {
                    is VocalAction.SelectTake -> { require(current.project.takes.any { it.id == action.id }); next = next.copy(selectedTake = action.id) }
                    is VocalAction.SelectComp -> {
                        val comp = requireNotNull(current.project.vocalComps.firstOrNull { it.id == action.id })
                        next = next.copy(draft = VocalCompDraft(comp.id, comp.segments, comp.id), replaceClipIds = emptySet())
                    }
                    VocalAction.WholeTake, VocalAction.FromLyrics -> {
                        val take = current.selectedTake ?: throw VocalEditException(VocalProblem.NO_TAKES)
                        val id = fresh("comp")
                        val draft = if (action == VocalAction.WholeTake) VocalCompEdits.fullTake(current.project, take, id)
                            else VocalCompEdits.lines(current.project, take, id)
                        next = next.copy(draft = draft, replaceClipIds = emptySet())
                    }
                    VocalAction.SplicePunch -> {
                        val range = requireNotNull(current.punchRange)
                        val base = requireNotNull(current.draft)
                        val plan = VocalPunchPlan.create(range.first, range.second, base.endFrame, 0, 0, current.project.tempo)
                        next = next.copy(draft = plan.splice(current.project, base, requireNotNull(current.selectedTake)))
                    }
                    is VocalAction.Choose -> {
                        next = next.copy(draft = VocalCompEdits.choose(current.project, requireNotNull(current.draft), action.segmentId, action.takeId))
                        next = next.copy(replaceClipIds = next.replaceClipIds.intersect(next.replaceableClips.map { it.id }.toSet()))
                    }
                    is VocalAction.ReplaceClip -> {
                        require(current.replaceableClips.any { it.id == action.id })
                        next = next.copy(replaceClipIds = if (action.replace) current.replaceClipIds + action.id else current.replaceClipIds - action.id)
                    }
                    else -> return@withLock false
                }
                invalidate(); publish(next.copy(previewing = false)); true
            } catch (failure: VocalEditException) { publish(current.copy(problem = failure.problem)); false }
              catch (_: IllegalArgumentException) { publish(current.copy(problem = VocalProblem.INVALID_INPUT)); false }
        }
    }

    private suspend fun previewTake(): Boolean {
        val pending = mutex.withLock {
            if (!guard()) return false
            val value = state.value
            if (value.selectedTake == null) return false
            invalidate(); publish(value.copy(phase = VocalPhase.RENDERING, problem = null))
            generation to value
        }
        val task = jobs.async { ports.previewTake(pending.second.project, pending.second.selectedTake!!) }
        work = task
        return finish(pending.first, task, preview = true)
    }

    private suspend fun prepare(name: String, apply: Boolean): Boolean {
        val pending = mutex.withLock {
            if (!guard()) return false
            val value = state.value
            if (value.draft == null) return false
            ports.stopPreview(); val token = ++generation
            publish(value.copy(phase = VocalPhase.RENDERING, problem = null, previewing = false, applied = false))
            token to value
        }
        val task = jobs.async {
            val (token, value) = pending
            val draft = value.draft!!
            val asset = prepared?.takeIf { it.first == draft }?.second
                ?: ports.render(value.project, draft, name) ?: throw VocalEditException(VocalProblem.RENDER_FAILED)
            mutex.withLock { checkCurrent(token, value.revision); prepared = draft to asset }
            if (!apply) ports.previewComp(asset) else {
                val track = value.project.tracks.firstOrNull { it.kind == TrackKind.VOCAL } ?: Track(fresh("track"), name, TrackKind.VOCAL)
                val intent = VocalCompEdits.apply(value.project, draft, asset, track, fresh("clip"), value.replaceClipIds)
                mutex.withLock { checkCurrent(token, value.revision); publish(state.value.copy(phase = VocalPhase.APPLYING)) }
                ports.apply(intent, value.revision)
            }
        }
        work = task
        return finish(pending.first, task, preview = !apply)
    }

    private suspend fun finish(token: Long, task: Deferred<Boolean>, preview: Boolean): Boolean {
        var problem: VocalProblem? = null
        val accepted = try { task.await() }
            catch (cancel: CancellationException) {
                task.cancel()
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) { mutex.withLock {
                        if (!closed && token == generation) {
                            invalidate(); publish(state.value.copy(phase = VocalPhase.EDITING, previewing = false))
                        }
                    } }
                    throw cancel
                }
                false
            }
            catch (failure: VocalEditException) { problem = failure.problem; false }
            catch (_: Exception) { problem = VocalProblem.RENDER_FAILED; false }
        return mutex.withLock {
            if (closed || token != generation) return@withLock false
            if (accepted && !preview) { prepared = null; reset(); publish(state.value.copy(applied = true)) }
            else publish(state.value.copy(phase = VocalPhase.EDITING, previewing = accepted && preview && (ports.previewState?.value?.let(::previewActive) ?: true),
                problem = when {
                    accepted && preview && ports.previewState?.value?.phase == VocalPreviewPhase.FAILED -> VocalProblem.PREVIEW_FAILED
                    accepted -> null
                    document.value.revision != state.value.revision -> VocalProblem.STALE
                    availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
                    else -> problem ?: VocalProblem.APPLY_FAILED
                }))
            accepted
        }
    }
    private fun guard(): Boolean {
        val problem = when {
            closed -> VocalProblem.CLOSED
            document.value.revision != state.value.revision -> VocalProblem.STALE
            availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
            state.value.phase != VocalPhase.EDITING -> VocalProblem.BUSY
            else -> null
        }
        if (problem != null) { publish(state.value.copy(problem = problem)); return false }; return true
    }
    private fun checkCurrent(token: Long, revision: Long) {
        if (closed || token != generation || document.value.revision != revision || availability.value != VocalAvailability.EDITABLE)
            throw CancellationException("Vocal preparation no longer current")
    }
    private fun fresh(kind: String): String {
        val project = state.value.project
        val used = (project.takes.map { it.id } + project.clips.map { it.id } + project.tracks.map { it.id } + project.vocalComps.map { it.id }).toSet()
        var number = 1
        while ("vocal-$kind-${state.value.revision}-$number" in used) number++
        return "vocal-$kind-${state.value.revision}-$number"
    }
    private fun blocked(value: VocalAvailability) = if (value == VocalAvailability.RECORDING) VocalProblem.RECORDING else VocalProblem.BUSY
    private fun invalidate() { generation++; work?.cancel(); prepared = null; ports.stopPreview() }
    private fun reset() { val value = document.value; publish(VocalTakeState(value.project, value.revision, availability = availability.value, punchRange = punchRange)) }
    private fun publish(value: VocalTakeState) { if (!closed) mutable.value = value }
    fun close() {
        if (closed) return
        closed = true; generation++; work?.cancel(); ports.stopPreview(); owner.cancel()
        mutable.value = state.value.copy(phase = VocalPhase.CLOSED, draft = null, previewing = false)
    }
}
