package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong

interface VocalCoachHost {
    val analyzer: VocalCoachAnalyzer
    val renderer: VocalPracticeRenderer
    val preview: VocalPreviewPort
}
interface VocalCoachActions {
    suspend fun allowed(revision: Long): CoachProblem?
    suspend fun preview(asset: Asset, revision: Long): Boolean
    suspend fun practice(line: VocalCoachLine, revision: Long): Boolean
    /** Opens the existing punch recorder with this range; the user explicitly starts recording there. */
    suspend fun respond(line: VocalCoachLine, revision: Long): Boolean
}
enum class CoachPhase { EDITING, ANALYZING, PREPARING_GUIDE, LISTENING, READY_RESPONSE, CLOSED }
enum class CoachRangeProblem { NUMBER, BOUNDS, ORDER, TOO_LONG }
data class CoachRangeValidation(val startFrame: Long?, val endFrame: Long?,
    val startProblem: CoachRangeProblem?, val endProblem: CoachRangeProblem?)
data class VocalCoachState(val project: Project, val revision: Long, val takeId: String?, val referenceId: String? = null,
    val startSeconds: String = "0", val endSeconds: String = "1", val mode: CoachMode = CoachMode.SINGING,
    val input: CoachVoiceInput = CoachVoiceInput.UNCONFIRMED, val phase: CoachPhase = CoachPhase.EDITING,
    val report: VocalCoachReport? = null, val selectedLine: Int = 0, val heardLine: Int? = null, val problem: CoachProblem? = null) {
    val takes: List<Take> get() = project.takes.filter { take -> project.tracks.any { it.id == take.trackId && it.kind == TrackKind.VOCAL } }
    val line: VocalCoachLine? get() = report?.lines?.getOrNull(selectedLine)
    val editable: Boolean get() = phase == CoachPhase.EDITING || phase == CoachPhase.READY_RESPONSE
    fun validateRange(): CoachRangeValidation {
        fun parse(text: String): Pair<Long?, CoachRangeProblem?> {
            val seconds = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: return null to CoachRangeProblem.NUMBER
            if (seconds !in 0.0..(ProjectLimits.MAX_TIMELINE_FRAMES / 48_000.0)) return null to CoachRangeProblem.BOUNDS
            return (seconds * 48_000).roundToLong() to null
        }
        val (start, startProblem) = parse(startSeconds)
        val (end, parsedEndProblem) = parse(endSeconds)
        val endProblem = parsedEndProblem ?: when {
            start == null || end == null -> null
            end <= start -> CoachRangeProblem.ORDER
            end - start > VocalPracticeRequest.MAX_FRAMES -> CoachRangeProblem.TOO_LONG
            else -> null
        }
        return CoachRangeValidation(start, end, startProblem, endProblem)
    }
    fun request(): VocalCoachRequest? {
        val range = validateRange()
        if (range.startProblem != null || range.endProblem != null) return null
        return runCatching { VocalCoachRequest(requireNotNull(takeId), referenceId,
            requireNotNull(range.startFrame), requireNotNull(range.endFrame), mode, input) }.getOrNull()
    }
}
sealed interface CoachAction {
    data class Take(val id: String) : CoachAction
    data class Reference(val id: String?) : CoachAction
    data class Mode(val value: CoachMode) : CoachAction
    data class Input(val value: CoachVoiceInput) : CoachAction
    data class Range(val start: String, val end: String) : CoachAction
    data class SelectLine(val index: Int) : CoachAction
    data object Analyze : CoachAction
    data object Practice : CoachAction
    data object ListenGuide : CoachAction
    data object Respond : CoachAction
    data object Stop : CoachAction
    data object Reload : CoachAction
}

/** Session-only evidence. Durable take history is read from Project; analysis/listening never consumes Undo. */
class VocalCoachController(private val document: StateFlow<DocumentState>, private val availability: StateFlow<VocalAvailability>,
    private val host: VocalCoachHost, private val actions: VocalCoachActions, scope: CoroutineScope) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val generation = MutableStateFlow(0L)
    private var work: Job? = null
    private val mutable = MutableStateFlow(snapshot())
    val state = mutable.asStateFlow()
    init {
        jobs.launch { document.collect { mutex.withLock {
            // Reload may already have adopted a newer document while this notification was queued.
            // Check the current document and stop under the same lock as Reload/analysis admission.
            if (document.value.revision != state.value.revision) stopLocked(CoachProblem.STALE)
        } } }
        jobs.launch { availability.collect { if (it != VocalAvailability.EDITABLE) stop(blocked(it)) } }
        jobs.launch { host.preview.state.collect { preview -> mutex.withLock {
            if (state.value.phase == CoachPhase.LISTENING && preview.owner != VocalPreviewOwner.COACH && !preview.ownsSource) {
                mutable.update { it.copy(phase = if (preview.phase == VocalPreviewPhase.IDLE) CoachPhase.READY_RESPONSE else CoachPhase.EDITING,
                    heardLine = if (preview.phase == VocalPreviewPhase.IDLE) it.selectedLine else null,
                    problem = if (preview.phase == VocalPreviewPhase.IDLE) null else CoachProblem.PREVIEW_FAILED) }
            } else if (state.value.phase == CoachPhase.LISTENING && preview.phase == VocalPreviewPhase.FAILED)
                mutable.update { it.copy(phase = CoachPhase.EDITING, heardLine = null, problem = CoachProblem.PREVIEW_FAILED) }
        } } }
    }

    suspend fun dispatch(action: CoachAction): Boolean {
        if (action == CoachAction.Stop) { stop(CoachProblem.CANCELLED); return true }
        if (action == CoachAction.Analyze || action == CoachAction.ListenGuide) return prepare(action)
        if (action == CoachAction.Practice || action == CoachAction.Respond) {
            val selected = mutex.withLock {
                if (!guard() || !state.value.editable) return false
                if (action == CoachAction.Respond && state.value.heardLine != state.value.selectedLine) return false
                state.value.line?.let { it to state.value.revision } ?: return false
            }
            if (actions.allowed(selected.second) != null) return false
            if (host.preview.stop(VocalPreviewOwner.COACH) is TtsResult.Failure) return false
            return if (action == CoachAction.Practice) actions.practice(selected.first, selected.second) else actions.respond(selected.first, selected.second)
        }
        return mutex.withLock {
            if (state.value.phase == CoachPhase.CLOSED) return@withLock false
            if (action == CoachAction.Reload) {
                invalidate(); mutable.value = snapshot(); return@withLock true
            }
            if (!guard() || !state.value.editable) return@withLock false
            val current = state.value
            val next = when (action) {
                is CoachAction.Take -> if (current.takes.any { it.id == action.id }) select(current.copy(
                    referenceId = current.referenceId.takeUnless { it == action.id }), action.id) else return@withLock false
                is CoachAction.Reference -> if (action.id == null || current.takes.any { it.id == action.id && it.id != current.takeId })
                    current.copy(referenceId = action.id) else return@withLock false
                is CoachAction.Mode -> current.copy(mode = action.value)
                is CoachAction.Input -> current.copy(input = action.value)
                is CoachAction.Range -> current.copy(startSeconds = action.start.take(16), endSeconds = action.end.take(16))
                is CoachAction.SelectLine -> if (current.report?.lines?.indices?.contains(action.index) == true)
                    current.copy(selectedLine = action.index) else return@withLock false
                else -> return@withLock false
            }
            invalidate()
            mutable.value = next.copy(phase = CoachPhase.EDITING, problem = null, heardLine = null,
                report = if (action is CoachAction.SelectLine) current.report else null)
            true
        }
    }

    private suspend fun prepare(action: CoachAction): Boolean {
        val pending = mutex.withLock {
            if (!guard() || !state.value.editable) return false
            val value = state.value
            val request = value.request() ?: run { mutable.value = value.copy(problem = CoachProblem.INVALID_INPUT); return false }
            val line = if (action == CoachAction.ListenGuide) value.line ?: return false else null
            invalidate()
            val token = generation.value
            mutable.value = value.copy(phase = if (line == null) CoachPhase.ANALYZING else CoachPhase.PREPARING_GUIDE, problem = null, heardLine = null)
            val task = jobs.async(start = CoroutineStart.LAZY) {
                val blocked = actions.allowed(value.revision)
                if (blocked != null) return@async CoachResult.Failure(blocked)
                if (host.preview.stop(VocalPreviewOwner.COACH) is TtsResult.Failure) return@async CoachResult.Failure(CoachProblem.PREVIEW_FAILED)
                if (line == null) host.analyzer.analyze(value.project, value.revision, request)
                else {
                    val anySolo = value.project.tracks.any { it.solo }
                    val guideIds = value.project.tracks.filter { it.kind == TrackKind.GUIDE && !it.mute && it.gain > 0f && (!anySolo || it.solo) }
                        .map { it.id }.toSet()
                    val guide = value.project.copy(clips = value.project.clips.filter { clip ->
                        val asset = value.project.asset(clip.assetHash)
                        val start = clip.timelineStartFrame ?: ProgramCompiler.clipTickToFrame(clip.startTick, value.project.tempo)
                        val end = start + takeSourceFrame48(clip.range.end, asset.sampleRate) - takeSourceFrame48(clip.range.start, asset.sampleRate)
                        clip.trackId in guideIds && clip.gain > 0f && value.project.mix.masterGain > 0f && start < line.endFrame && end > line.startFrame
                    }.frozen())
                    if (guide.clips.isEmpty()) return@async CoachResult.Failure(CoachProblem.NO_GUIDE)
                    val rendered = host.renderer.render(guide, value.revision, VocalPracticeRequest(line.startFrame, line.endFrame))
                    currentCoroutineContext().ensureActive()
                    if (!current(token, value.revision)) return@async CoachResult.Failure(CoachProblem.STALE)
                    if (rendered !is PracticeResult.Success || !actions.preview(rendered.value, value.revision))
                        CoachResult.Failure(CoachProblem.PREVIEW_FAILED) else CoachResult.Success(null)
                }
            }
            work = task
            Triple(token, value.revision, task)
        }
        val (token, revision, task) = pending
        val result = try { task.start(); task.await() }
            catch (cancel: CancellationException) {
                task.cancel()
                if (!currentCoroutineContext().isActive) { withContext(NonCancellable) { stop(CoachProblem.CANCELLED) }; throw cancel }
                return false
            }
            catch (_: Exception) { CoachResult.Failure(CoachProblem.PCM_UNAVAILABLE) }
        return mutex.withLock {
            if (!current(token, revision)) return@withLock false
            work = null
            when (result) {
                is CoachResult.Failure -> { mutable.update { it.copy(phase = CoachPhase.EDITING, problem = result.problem) }; false }
                is CoachResult.Success -> {
                    val report = result.value
                    mutable.update { it.copy(phase = if (report == null) CoachPhase.LISTENING else CoachPhase.EDITING,
                        report = report ?: it.report, selectedLine = report?.let { r -> r.lines.indexOf(r.suggestedLine).coerceAtLeast(0) } ?: it.selectedLine) }
                    true
                }
            }
        }
    }
    private fun current(token: Long, revision: Long) = state.value.phase != CoachPhase.CLOSED && generation.value == token &&
        document.value.revision == revision && availability.value == VocalAvailability.EDITABLE
    private fun guard(): Boolean {
        val value = state.value
        val problem = when {
            value.phase == CoachPhase.CLOSED -> return false
            document.value.revision != value.revision -> CoachProblem.STALE
            availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
            else -> null
        }
        if (problem != null) mutable.update { it.copy(problem = problem) }
        return problem == null
    }
    private fun snapshot(): VocalCoachState {
        val value = document.value
        val state = VocalCoachState(value.project, value.revision, null)
        return select(state, state.takes.lastOrNull()?.id)
    }
    private fun select(value: VocalCoachState, id: String?): VocalCoachState {
        val take = value.takes.firstOrNull { it.id == id } ?: return value.copy(takeId = null)
        val start = take.correctedStartFrame().coerceAtLeast(0)
        val end = minOf(take.correctedEndFrame(value.project.asset(take.assetHash)), start + VocalPracticeRequest.MAX_FRAMES)
        return value.copy(takeId = id, startSeconds = (start / 48_000.0).toString(), endSeconds = (end / 48_000.0).toString())
    }
    private fun blocked(value: VocalAvailability) = if (value == VocalAvailability.RECORDING) CoachProblem.RECORDING else CoachProblem.BUSY
    private fun invalidate() { generation.update { it + 1 }; work?.cancel(); work = null; host.preview.requestStop(VocalPreviewOwner.COACH) }
    private suspend fun stop(problem: CoachProblem) = mutex.withLock { stopLocked(problem) }
    private fun stopLocked(problem: CoachProblem) {
        if (state.value.phase != CoachPhase.CLOSED) {
            invalidate(); mutable.update { it.copy(phase = CoachPhase.EDITING, heardLine = null, problem = problem,
                report = if (problem == CoachProblem.STALE || problem == CoachProblem.RECORDING) null else it.report) }
        }
    }
    fun close() {
        if (mutable.getAndUpdate { it.copy(phase = CoachPhase.CLOSED, heardLine = null) }.phase == CoachPhase.CLOSED) return
        invalidate(); owner.cancel()
    }
}
