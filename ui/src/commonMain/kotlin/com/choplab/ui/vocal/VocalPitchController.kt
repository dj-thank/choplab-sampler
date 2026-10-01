package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ai.VocalPreviewPort
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

data class PreparedVocalPitch(val asset: Asset?, val report: PitchCorrectionReport)
/** Host-owned worker and the one shared SOURCE preview. No document or UI is owned here. */
interface VocalPitchHost {
    val preview: VocalPreviewPort
    suspend fun render(project: Project, draft: VocalPitchDraft, progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch
    suspend fun original(project: Project, draft: VocalPitchDraft): Asset
}
interface VocalPitchPorts {
    val previewing: StateFlow<Boolean>
    /** Read the current SOURCE owner; a projected notification can still describe an earlier audition. */
    fun isPreviewing(): Boolean = previewing.value
    suspend fun render(project: Project, draft: VocalPitchDraft, progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch
    suspend fun original(project: Project, draft: VocalPitchDraft): Asset
    suspend fun preview(asset: Asset, expectedRevision: Long): Boolean
    fun cancelPreview()
    suspend fun stopPreview(): Boolean
    suspend fun apply(intent: Intent, expectedRevision: Long): Boolean
}
enum class PitchEditorPhase { EDITING, PREPARING, APPLYING, CLOSED }
enum class PitchEditorProblem { NO_VOICE, INVALID_INPUT, STALE, BUSY, RECORDING, LIMIT, FAILED, PREVIEW_FAILED, APPLY_FAILED }
enum class PitchField { AMOUNT, RETUNE, VIBRATO }
enum class PitchAudition { NONE, ORIGINAL, CORRECTED }
data class PitchEditorState(
    val project: Project,
    val revision: Long,
    val clipId: String? = null,
    val key: Int = 0,
    val scale: PitchScale = PitchScale.CHROMATIC,
    val fields: Map<PitchField, String> = fields(PitchCorrectionSettings()),
    val phase: PitchEditorPhase = PitchEditorPhase.EDITING,
    val availability: VocalAvailability = VocalAvailability.EDITABLE,
    val problem: PitchEditorProblem? = null,
    val progressPhase: PitchCorrectionPhase? = null,
    val progress: Int = 0,
    val report: PitchCorrectionReport? = null,
    val prepared: Boolean = false,
    val audition: PitchAudition = PitchAudition.NONE,
    val applied: Boolean = false,
) {
    val clips: List<Clip> get() = project.clips.filter { clip -> project.tracks.any { it.id == clip.trackId && it.kind == TrackKind.VOCAL } }
    val editable: Boolean get() = phase == PitchEditorPhase.EDITING && availability == VocalAvailability.EDITABLE && problem != PitchEditorProblem.STALE
    fun settings(): PitchCorrectionSettings? = try {
        PitchCorrectionSettings(key, scale, fields.getValue(PitchField.AMOUNT).toFloat() / 100,
            fields.getValue(PitchField.RETUNE).toFloat(), fields.getValue(PitchField.VIBRATO).toFloat() / 100)
    } catch (_: IllegalArgumentException) { null }
    companion object {
        fun fields(settings: PitchCorrectionSettings) = mapOf(PitchField.AMOUNT to (settings.amount * 100).toString(),
            PitchField.RETUNE to settings.retuneMs.toString(), PitchField.VIBRATO to (settings.vibrato * 100).toString())
    }
}
sealed interface PitchAction {
    data class SelectClip(val id: String) : PitchAction
    data class Key(val value: Int) : PitchAction
    data class Scale(val value: PitchScale) : PitchAction
    data class Field(val field: PitchField, val text: String) : PitchAction
    data object Prepare : PitchAction
    data object PreviewOriginal : PitchAction
    data object PreviewCorrected : PitchAction
    data object Stop : PitchAction
    data object Cancel : PitchAction
    data object Reload : PitchAction
    data object Apply : PitchAction
    data class SelectSaved(val original: Boolean) : PitchAction
}

/** Silent settings and worker result until Apply. Revision and availability are fenced again by the presenter/actor. */
class VocalPitchController(private val document: StateFlow<DocumentState>, private val availability: StateFlow<VocalAvailability>,
                           private val ports: VocalPitchPorts, scope: CoroutineScope, initialClipId: String? = null) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val closed = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    private val work = MutableStateFlow<Job?>(null)
    @Volatile private var prepared: Pair<VocalPitchDraft, PreparedVocalPitch>? = null
    private val mutable = MutableStateFlow(snapshot(initialClipId))
    val state: StateFlow<PitchEditorState> = mutable.asStateFlow()
    init {
        jobs.launch { document.collect { value -> mutex.withLock {
            if (!closed.value && value.revision != state.value.revision && state.value.phase != PitchEditorPhase.APPLYING) {
                invalidate(); publish(state.value.copy(phase = PitchEditorPhase.EDITING, problem = PitchEditorProblem.STALE, prepared = false, audition = PitchAudition.NONE))
            }
        } } }
        jobs.launch { availability.collect { value -> mutex.withLock {
            if (!closed.value) {
                publish(state.value.copy(availability = value))
                if (value != VocalAvailability.EDITABLE && state.value.phase != PitchEditorPhase.APPLYING) {
                    invalidate(); publish(state.value.copy(phase = PitchEditorPhase.EDITING, problem = blocked(value), prepared = false, audition = PitchAudition.NONE))
                }
            }
        } } }
        jobs.launch { ports.previewing.collect { active -> if (!active) mutex.withLock {
            // A's stop can arrive after B is playing. Serialize with publication and consult the current SOURCE owner.
            if (!ports.isPreviewing()) publish(state.value.copy(audition = PitchAudition.NONE))
        } } }
    }

    suspend fun dispatch(action: PitchAction): Boolean {
        if (action in listOf(PitchAction.Prepare, PitchAction.PreviewOriginal, PitchAction.PreviewCorrected, PitchAction.Apply) || action is PitchAction.SelectSaved)
            return prepare(action)
        return mutex.withLock {
            if (closed.value) return@withLock false
            if (action == PitchAction.Stop || action == PitchAction.Cancel) {
                if (state.value.phase == PitchEditorPhase.APPLYING) { ports.cancelPreview(); return@withLock false }
                invalidate(); publish(state.value.copy(phase = PitchEditorPhase.EDITING, prepared = false, report = null,
                    progressPhase = null, problem = null, audition = PitchAudition.NONE)); return@withLock true
            }
            if (action == PitchAction.Reload) {
                if (state.value.phase != PitchEditorPhase.EDITING) return@withLock false
                invalidate(); publish(snapshot(state.value.clipId)); return@withLock true
            }
            if (!guard()) return@withLock false
            val current = state.value
            val next = when (action) {
                is PitchAction.SelectClip -> { require(current.clips.any { it.id == action.id }); selected(current, action.id) }
                is PitchAction.Key -> { require(action.value in 0..11); current.copy(key = action.value) }
                is PitchAction.Scale -> current.copy(scale = action.value)
                is PitchAction.Field -> { require(action.text.length <= 24); current.copy(fields = current.fields + (action.field to action.text)) }
                else -> return@withLock false
            }
            invalidate(); publish(next.copy(report = null, prepared = false, applied = false, progressPhase = null, audition = PitchAudition.NONE,
                problem = if (next.settings() == null) PitchEditorProblem.INVALID_INPUT else null)); true
        }
    }

    private suspend fun prepare(action: PitchAction): Boolean {
        val pending = mutex.withLock {
            if (!guard()) return false
            val value = state.value
            val settings = value.settings() ?: run { publish(value.copy(problem = PitchEditorProblem.INVALID_INPUT)); return false }
            val id = value.clipId ?: run { publish(value.copy(problem = PitchEditorProblem.NO_VOICE)); return false }
            val draft = try { VocalPitchEdits.draft(DocumentState(value.project, value.revision), id, fresh(), settings) }
                catch (failure: VocalPitchException) { publish(value.copy(problem = problem(failure.problem))); return false }
            ports.cancelPreview()
            val token = generation.updateAndGet { it + 1 }
            publish(value.copy(phase = PitchEditorPhase.PREPARING, progress = 0, progressPhase = null, problem = null, applied = false, audition = PitchAudition.NONE))
            val task = jobs.async(start = CoroutineStart.LAZY) {
                if (!ports.stopPreview()) throw PreviewFailed()
                checkCurrent(token, value.revision)
                if (action is PitchAction.SelectSaved) {
                    val saved = value.project.pitchCorrections.firstOrNull { it.clipId == id } ?: error("No saved correction")
                    val intent = VocalPitchEdits.select(DocumentState(value.project, value.revision), saved.id, action.original)
                    apply(token, value.revision, intent)
                } else if (action == PitchAction.PreviewOriginal) {
                    val original = ports.original(value.project, draft)
                    checkCurrent(token, value.revision)
                    if (!ports.preview(original, value.revision)) throw PreviewFailed()
                    PitchAudition.ORIGINAL
                } else {
                    val result = prepared?.takeIf { it.first == draft }?.second ?: ports.render(value.project, draft) { phase, at, total ->
                        if (!closed.value && generation.value == token) mutable.update { it.copy(progressPhase = phase, progress = (at.toLong() * 100 / total).toInt()) }
                    }
                    mutex.withLock {
                        checkCurrent(token, value.revision); prepared = draft to result
                        publish(state.value.copy(report = result.report, prepared = result.asset != null))
                    }
                    if (action == PitchAction.Apply && result.asset != null) {
                        val intent = VocalPitchEdits.apply(document.value, draft, result.asset)
                        apply(token, value.revision, intent)
                    } else if (action == PitchAction.PreviewCorrected && result.asset != null) {
                        if (!ports.preview(result.asset, value.revision)) throw PreviewFailed()
                        PitchAudition.CORRECTED
                    } else PitchAudition.NONE
                }
            }
            work.value = task
            if (closed.value || generation.value != token) task.cancel()
            Triple(token, task, value.revision)
        }
        val (token, task, revision) = pending
        var failure: PitchEditorProblem? = null
        val result = try { task.start(); task.await() }
            catch (cancel: CancellationException) {
                task.cancel()
                if (!currentCoroutineContext().isActive) { cancelPending(token); throw cancel }
                null
            }
            catch (error: VocalPitchException) { failure = problem(error.problem); null }
            catch (_: PreviewFailed) { failure = PitchEditorProblem.PREVIEW_FAILED; null }
            catch (_: Exception) { failure = PitchEditorProblem.FAILED; null }
        return mutex.withLock {
            if (closed.value || token != generation.value) return@withLock false
            work.compareAndSet(task, null)
            if (result == null) {
                ports.cancelPreview()
                publish(state.value.copy(phase = PitchEditorPhase.EDITING, problem = when {
                    document.value.revision != revision -> PitchEditorProblem.STALE
                    availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
                    else -> failure ?: PitchEditorProblem.FAILED
                }, audition = PitchAudition.NONE)); false
            } else if (state.value.phase == PitchEditorPhase.APPLYING) {
                prepared = null; publish(snapshot(state.value.clipId).copy(applied = true)); true
            } else {
                publish(state.value.copy(phase = PitchEditorPhase.EDITING,
                    audition = result.takeIf { ports.isPreviewing() } ?: PitchAudition.NONE, progressPhase = null)); true
            }
        }
    }
    private suspend fun apply(token: Long, revision: Long, intent: Intent): PitchAudition {
        mutex.withLock { checkCurrent(token, revision); publish(state.value.copy(phase = PitchEditorPhase.APPLYING)) }
        if (!ports.apply(intent, revision)) throw IllegalStateException("Pitch apply rejected")
        return PitchAudition.NONE
    }
    private suspend fun cancelPending(token: Long) = withContext(NonCancellable) { mutex.withLock {
        if (!closed.value && generation.value == token) { invalidate(); publish(state.value.copy(phase = PitchEditorPhase.EDITING, prepared = false, audition = PitchAudition.NONE)) }
    } }
    private fun checkCurrent(token: Long, revision: Long) {
        if (closed.value || generation.value != token || document.value.revision != revision || availability.value != VocalAvailability.EDITABLE)
            throw CancellationException("Pitch preparation became stale")
    }
    private fun guard(): Boolean {
        val problem = when {
            closed.value -> return false
            document.value.revision != state.value.revision -> PitchEditorProblem.STALE
            availability.value != VocalAvailability.EDITABLE -> blocked(availability.value)
            state.value.phase != PitchEditorPhase.EDITING -> PitchEditorProblem.BUSY
            else -> null
        }
        if (problem != null) publish(state.value.copy(problem = problem))
        return problem == null
    }
    private fun snapshot(clipId: String?): PitchEditorState {
        val value = document.value
        val initial = PitchEditorState(value.project, value.revision, availability = availability.value)
        return selected(initial, initial.clips.firstOrNull { it.id == clipId }?.id ?: initial.clips.firstOrNull()?.id)
    }
    private fun selected(value: PitchEditorState, id: String?): PitchEditorState {
        val settings = value.project.pitchCorrections.firstOrNull { it.clipId == id }?.settings ?: PitchCorrectionSettings()
        return value.copy(clipId = id, key = settings.key, scale = settings.scale, fields = PitchEditorState.fields(settings))
    }
    private fun fresh(): String {
        val used = state.value.project.pitchCorrections.map { it.id }.toSet()
        var n = 1
        while ("pitch-${state.value.revision}-$n" in used) n++
        return "pitch-${state.value.revision}-$n"
    }
    private fun blocked(value: VocalAvailability) = if (value == VocalAvailability.RECORDING) PitchEditorProblem.RECORDING else PitchEditorProblem.BUSY
    private fun problem(value: VocalPitchProblem) = when (value) {
        VocalPitchProblem.STALE -> PitchEditorProblem.STALE
        VocalPitchProblem.LIMIT -> PitchEditorProblem.LIMIT
        VocalPitchProblem.NO_VOICE_CLIP -> PitchEditorProblem.NO_VOICE
        VocalPitchProblem.INVALID_INPUT -> PitchEditorProblem.INVALID_INPUT
        VocalPitchProblem.RENDER_FAILED -> PitchEditorProblem.FAILED
    }
    private fun invalidate() { generation.update { it + 1 }; work.getAndUpdate { null }?.cancel(); prepared = null; ports.cancelPreview() }
    private fun publish(value: PitchEditorState) { if (!closed.value) mutable.value = value }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidate(); owner.cancel(); mutable.value = state.value.copy(phase = PitchEditorPhase.CLOSED, prepared = false, audition = PitchAudition.NONE)
    }
    private class PreviewFailed : IllegalStateException()
}
