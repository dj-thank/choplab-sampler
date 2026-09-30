package com.choplab.ui.pattern

import com.choplab.core.DocumentState
import com.choplab.core.SelectionState
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.pattern.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

enum class PatternAvailability { EDITABLE, BUSY, RECORDING }
enum class PatternPhase { EDITING, RENDERING, APPLYING, CLOSED }
interface StepPatternPorts {
    /** The host must recheck its busy/recording guard and use Studio's atomic expectedRevision edit. */
    suspend fun apply(intent: Intent, expectedRevision: Long): Boolean
    suspend fun render(pad: Pad, source: Asset, request: PatternVoiceRender): Asset?
    suspend fun selectPad(padId: Int): Boolean
}
data class StepPatternState(
    val project: Project,
    val revision: Long,
    val draft: Pattern,
    val name: String = draft.name,
    val selectedPadId: Int = 0,
    val columns: Int = 16,
    val gridTicks: Int = PatternEdits.STEP_TICKS,
    val page: Int = 0,
    val velocity: Float = 1f,
    val sequence: FrozenList<SongSection> = frozenListOf(),
    val firstBar: Int = 1,
    val repeats: Int = 1,
    val phase: PatternPhase = PatternPhase.EDITING,
    val availability: PatternAvailability = PatternAvailability.EDITABLE,
    val problem: PatternProblem? = null,
    val trimBars: Int? = null,
    val applied: Boolean = false,
) {
    val dirty: Boolean get() = project.patterns.firstOrNull { it.id == draft.id } != draft || name != draft.name
    val steps: Int get() = draft.lengthTicks / gridTicks
    val pages: Int get() = (steps + columns - 1) / columns
    val editable: Boolean get() = phase == PatternPhase.EDITING && availability == PatternAvailability.EDITABLE && problem != PatternProblem.STALE_DOCUMENT
}
sealed interface PatternAction {
    data class Select(val id: String) : PatternAction
    data class New(val name: String, val copy: Boolean = false) : PatternAction
    data class Name(val value: String) : PatternAction
    data class Resize(val bars: Int, val trim: Boolean = false) : PatternAction
    data class Columns(val count: Int) : PatternAction
    data class Grid(val ticks: Int) : PatternAction
    data class Page(val index: Int) : PatternAction
    data class SelectPad(val id: Int) : PatternAction
    data class Velocity(val value: Float) : PatternAction
    data class Toggle(val step: Int, val gridTicks: Int = PatternEdits.STEP_TICKS) : PatternAction
    data class Quantize(val ticks: Int) : PatternAction
    data object ClearPad : PatternAction
    data class Repeats(val count: Int) : PatternAction
    data class FirstBar(val number: Int) : PatternAction
    data object Queue : PatternAction
    data class RemoveQueued(val index: Int) : PatternAction
    data class MoveQueued(val index: Int, val delta: Int) : PatternAction
    data object Dismiss : PatternAction
    data object Discard : PatternAction
    data object Reload : PatternAction
    data object Save : PatternAction
    data class Place(val trackName: String) : PatternAction
    data object Cancel : PatternAction
}

/** One draft at a time; Save and Place each submit exactly one existing Intent. View changes are never edits. */
class StepPatternController(
    private val document: StateFlow<DocumentState>,
    selection: StateFlow<SelectionState>,
    private val availability: StateFlow<PatternAvailability>,
    private val ports: StepPatternPorts,
    scope: CoroutineScope,
) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val lock = Mutex()
    private val initial = document.value
    private val mutable = MutableStateFlow(StepPatternState(initial.project, initial.revision,
        initial.project.patterns.firstOrNull { it.id == selection.value.patternId } ?: initial.project.patterns.first(),
        selectedPadId = selection.value.padId, availability = availability.value))
    val state: StateFlow<StepPatternState> = mutable.asStateFlow()
    @Volatile private var serial = 0L
    @Volatile private var work: Job? = null
    @Volatile private var closed = false
    init {
        jobs.launch { selection.collect { selected -> lock.withLock { publish(state.value.copy(selectedPadId = selected.padId)) } } }
        jobs.launch { availability.collect { value -> lock.withLock {
            publish(state.value.copy(availability = value))
            if (value != PatternAvailability.EDITABLE && state.value.phase == PatternPhase.RENDERING) {
                serial++; work?.cancel(); publish(state.value.copy(phase = PatternPhase.EDITING, problem = blocked(value)))
            }
        } } }
        jobs.launch { document.collect { current -> lock.withLock {
            if (!closed && current.revision != state.value.revision && state.value.phase != PatternPhase.APPLYING) {
                serial++; work?.cancel(); publish(state.value.copy(phase = PatternPhase.EDITING, problem = PatternProblem.STALE_DOCUMENT))
            }
        } } }
    }

    suspend fun dispatch(action: PatternAction): Boolean {
        if (action == PatternAction.Save) return save()
        if (action is PatternAction.Place) return place(action.trackName)
        return lock.withLock {
            if (closed) return@withLock false
            val current = state.value
            if (action == PatternAction.Cancel) {
                if (current.phase != PatternPhase.RENDERING) return@withLock false
                serial++; work?.cancel(); publish(current.copy(phase = PatternPhase.EDITING, problem = null)); return@withLock true
            }
            if (action == PatternAction.Reload) {
                if (current.phase != PatternPhase.EDITING) return@withLock false
                reset(document.value, current.draft.id, clearQueue = true); return@withLock true
            }
            if (!guard()) return@withLock false
            try {
                var next = current.copy(problem = null, trimBars = null, applied = false)
                when (action) {
                    is PatternAction.Select -> {
                        if (current.dirty) throw PatternEditException(PatternProblem.UNSAVED_PATTERN)
                        val pattern = current.project.patterns.firstOrNull { it.id == action.id } ?: throw PatternEditException(PatternProblem.INVALID_INPUT)
                        next = next.copy(draft = pattern, name = pattern.name, page = 0)
                    }
                    is PatternAction.New -> {
                        if (current.dirty) throw PatternEditException(PatternProblem.UNSAVED_PATTERN)
                        val pattern = PatternEdits.create(current.project, action.name, current.draft.takeIf { action.copy })
                        next = next.copy(draft = pattern, name = pattern.name, page = 0)
                    }
                    is PatternAction.Name -> { require(action.value.length <= 80); next = next.copy(name = action.value) }
                    is PatternAction.Resize -> {
                        try { next = next.copy(draft = PatternEdits.resize(current.draft, action.bars, action.trim), page = 0) }
                        catch (problem: PatternEditException) {
                            if (problem.problem != PatternProblem.TRIM_REQUIRED) throw problem
                            publish(current.copy(problem = problem.problem, trimBars = action.bars)); return@withLock false
                        }
                    }
                    is PatternAction.Columns -> { require(action.count in listOf(16, 32, 64)); next = next.copy(columns = action.count, page = 0) }
                    is PatternAction.Grid -> { require(action.ticks in PatternEdits.INPUT_GRID_TICKS); next = next.copy(gridTicks = action.ticks, page = 0) }
                    is PatternAction.Page -> { require(action.index in 0 until current.pages); next = next.copy(page = action.index) }
                    is PatternAction.SelectPad -> {
                        require(action.id in 0..127)
                        if (!ports.selectPad(action.id)) return@withLock false
                        // Selection flow is authoritative. Step edits never choose another PAD implicitly.
                        return@withLock true
                    }
                    is PatternAction.Velocity -> { require(action.value.isFinite() && action.value in 0.01f..1f); next = next.copy(velocity = action.value) }
                    is PatternAction.Toggle -> {
                        // A click from a replaced grid must not silently target a different musical position.
                        require(action.gridTicks == current.gridTicks)
                        next = next.copy(draft = PatternEdits.toggle(current.project, current.draft, current.selectedPadId,
                            action.step, current.velocity, action.gridTicks))
                    }
                    is PatternAction.Quantize -> next = next.copy(draft = PatternEdits.quantize(current.draft, current.selectedPadId, action.ticks))
                    PatternAction.ClearPad -> next = next.copy(draft = PatternEdits.clear(current.draft, current.selectedPadId))
                    is PatternAction.Repeats -> { require(action.count in 1..128); next = next.copy(repeats = action.count) }
                    is PatternAction.FirstBar -> { require(action.number in 1..26_041); next = next.copy(firstBar = action.number) }
                    PatternAction.Queue -> {
                        if (current.dirty) throw PatternEditException(PatternProblem.UNSAVED_PATTERN)
                        if (current.sequence.size >= 128) throw PatternEditException(PatternProblem.SONG_FULL)
                        next = next.copy(sequence = (current.sequence + SongSection(current.draft.id, current.repeats)).frozen())
                    }
                    is PatternAction.RemoveQueued -> { require(action.index in current.sequence.indices); next = next.copy(sequence = current.sequence.filterIndexed { i, _ -> i != action.index }.frozen()) }
                    is PatternAction.MoveQueued -> {
                        val to = action.index + action.delta
                        require(action.delta in listOf(-1, 1) && action.index in current.sequence.indices && to in current.sequence.indices)
                        val list = current.sequence.toMutableList(); val moved = list.removeAt(action.index); list.add(to, moved)
                        next = next.copy(sequence = list.frozen())
                    }
                    PatternAction.Discard -> { reset(document.value, current.draft.id); return@withLock true }
                    PatternAction.Dismiss -> Unit
                    else -> return@withLock false
                }
                publish(next); true
            } catch (problem: PatternEditException) { publish(current.copy(problem = problem.problem)); false }
              catch (_: IllegalArgumentException) { publish(current.copy(problem = PatternProblem.INVALID_INPUT)); false }
        }
    }

    private suspend fun save(): Boolean {
        val pending = lock.withLock {
            if (!guard() || !state.value.dirty || state.value.trimBars != null) return false
            val value = state.value
            val intent = try { PatternEdits.put(value.project, value.draft.copy(name = value.name)) }
                catch (problem: PatternEditException) { publish(value.copy(problem = problem.problem)); return false }
                catch (_: IllegalArgumentException) { publish(value.copy(problem = PatternProblem.INVALID_INPUT)); return false }
            val token = ++serial
            publish(value.copy(phase = PatternPhase.APPLYING, problem = null))
            Triple(token, value.revision, intent)
        }
        val task = jobs.async { ports.apply(pending.third, pending.second) }
        work = task
        return finish(pending.first, task, clearQueue = false)
    }

    private suspend fun place(trackName: String): Boolean {
        val pending = lock.withLock {
            if (!guard()) return false
            val value = state.value
            if (value.dirty) { publish(value.copy(problem = PatternProblem.UNSAVED_PATTERN)); return false }
            val plan = try { PatternPlacement.plan(value.project, value.sequence, (value.firstBar - 1L) * PatternEdits.BAR_TICKS) }
                catch (problem: PatternEditException) { publish(value.copy(problem = problem.problem)); return false }
            val token = ++serial
            publish(value.copy(phase = PatternPhase.RENDERING, problem = null, applied = false))
            Triple(token, value, plan)
        }
        val task = jobs.async(Dispatchers.Default) {
            val (token, value, plan) = pending
            val rendered = LinkedHashMap<PatternVoiceRender, Asset>()
            for (request in plan.renders) {
                ensureCurrent(token, value.revision)
                val pad = value.project.pads[request.padId]
                val asset = ports.render(pad, value.project.asset(requireNotNull(pad.assetHash)), request)
                    ?: throw PatternEditException(PatternProblem.RENDER_FAILED)
                rendered[request] = asset
            }
            ensureCurrent(token, value.revision)
            var id = 0
            val used = (value.project.clips.map { it.id } + value.project.tracks.map { it.id }).toMutableSet()
            fun fresh(kind: String): String { var next: String; do { next = "step-$kind-${value.revision}-${++id}" } while (!used.add(next)); return next }
            val intent = PatternPlacement.intent(value.project, plan, rendered, ::fresh, trackName)
            lock.withLock {
                ensureCurrent(token, value.revision)
                publish(state.value.copy(phase = PatternPhase.APPLYING))
            }
            ports.apply(intent, value.revision)
        }
        work = task
        return finish(pending.first, task, clearQueue = true)
    }

    private suspend fun finish(token: Long, task: Deferred<Boolean>, clearQueue: Boolean): Boolean {
        var problem: PatternProblem? = null
        val accepted = try { task.await() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: PatternEditException) { problem = failure.problem; false }
            catch (_: Exception) { problem = PatternProblem.RENDER_FAILED; false }
        return lock.withLock {
            if (closed || serial != token) return@withLock false
            if (accepted) { reset(document.value, state.value.draft.id, clearQueue); publish(state.value.copy(applied = true)) }
            else publish(state.value.copy(phase = PatternPhase.EDITING, problem = problem ?: PatternProblem.APPLY_FAILED))
            accepted
        }
    }
    private fun guard(): Boolean {
        val value = state.value
        val problem = when {
            closed -> PatternProblem.CLOSED
            value.phase != PatternPhase.EDITING -> PatternProblem.BUSY
            document.value.revision != value.revision -> PatternProblem.STALE_DOCUMENT
            availability.value != PatternAvailability.EDITABLE -> blocked(availability.value)
            else -> null
        }
        if (problem != null) { publish(value.copy(problem = problem)); return false }
        return true
    }
    private suspend fun ensureCurrent(token: Long, revision: Long) {
        currentCoroutineContext().ensureActive()
        if (closed || token != serial) throw CancellationException()
        if (document.value.revision != revision) throw PatternEditException(PatternProblem.STALE_DOCUMENT)
        if (availability.value != PatternAvailability.EDITABLE) throw PatternEditException(blocked(availability.value))
    }
    private fun reset(snapshot: DocumentState, patternId: String, clearQueue: Boolean = false) {
        val pattern = snapshot.project.patterns.firstOrNull { it.id == patternId } ?: snapshot.project.patterns.first()
        publish(state.value.copy(project = snapshot.project, revision = snapshot.revision, draft = pattern, name = pattern.name,
            page = 0, sequence = if (clearQueue) frozenListOf() else state.value.sequence, phase = PatternPhase.EDITING, problem = null, trimBars = null, applied = false))
    }
    private fun publish(value: StepPatternState) { mutable.update { if (closed) it.copy(phase = PatternPhase.CLOSED) else value } }
    fun close() { closed = true; work?.cancel(); owner.cancel(); mutable.update { it.copy(phase = PatternPhase.CLOSED) } }
    private fun blocked(value: PatternAvailability) = if (value == PatternAvailability.RECORDING) PatternProblem.RECORDING else PatternProblem.BUSY
}
