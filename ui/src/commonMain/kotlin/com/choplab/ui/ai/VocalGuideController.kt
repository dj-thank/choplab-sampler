package com.choplab.ui.ai

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

enum class VocalGuidePhase { EDITING, PREPARING, READY, APPLYING, APPLIED, FAILED, CLOSED }
enum class VocalGuideAvailability { EDITABLE, BUSY, RECORDING }
data class VocalGuideRow(val line: LyricLine, val reading: String, val confirmed: Boolean,
                         val mode: FlowMode = FlowMode.ONE_BAR, val prepared: PreparedVocalLine? = null,
                         val failure: TtsFailure? = null)
data class VocalGuideState(
    val phase: VocalGuidePhase = VocalGuidePhase.EDITING,
    val title: String = "",
    val language: LyricLanguage = LyricLanguage.JAPANESE,
    val startBeat: Long = 1,
    val rows: List<VocalGuideRow> = emptyList(),
    val plan: FlowPlan? = null,
    val issue: FlowIssue? = null,
    val voices: List<TtsVoice> = emptyList(),
    val voice: TtsVoice? = null,
    val loadingVoices: Boolean = true,
    val settings: TtsSettings = TtsSettings(),
    val densityConfirmed: Boolean = false,
    val preparingLine: String? = null,
    val failure: TtsFailure? = null,
) { override fun toString() = "VocalGuideState(phase=$phase, rows=${rows.size})" }

/** Hosts recheck recording/preparation flags under the presenter's action lock immediately before each operation. */
interface VocalGuideActions {
    suspend fun prepareAllowed(expectedRevision: Long): TtsResult<Unit>
    suspend fun preview(asset: Asset, expectedRevision: Long): TtsResult<Unit>
    suspend fun apply(intent: Intent.ApplyVocalGuide, expectedRevision: Long): Boolean
}
interface VocalGuidePort {
    val preview: VocalPreviewPort
    /** One offline service/provider per dialog, closed by its controller. */
    fun createSynthesis(): VocalSynthesisPort
}

/** A captured document becomes audio only after explicit Apply. Private native PCM never enters view state. */
class VocalGuideController(
    private val document: StateFlow<DocumentState>,
    private val availability: StateFlow<VocalGuideAvailability>,
    private val synthesis: VocalSynthesisPort,
    val preview: VocalPreviewPort,
    private val actions: VocalGuideActions,
    scope: CoroutineScope,
) {
    private val snapshot = document.value
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val closing = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    @Volatile private var work: Job? = null
    @Volatile private var cancelling = false
    private val originalStructure = snapshot.project.lyricStructure
    private val mutable = MutableStateFlow(initial())
    val state = mutable.asStateFlow()
    private fun initial(): VocalGuideState {
        val structure = originalStructure
        val ids = structure?.sections?.flatMap { it.lines }?.map { it.lineId }.orEmpty()
        val lines = if (ids.isEmpty()) snapshot.project.lyrics.toList() else ids.mapNotNull { id -> snapshot.project.lyrics.firstOrNull { it.id == id } }
        if (lines.isEmpty() || lines.size > 64) return VocalGuideState(failure = TtsFailure(if (lines.isEmpty()) TtsProblem.INVALID_INPUT else TtsProblem.TOO_LARGE))
        return VocalGuideState(title = structure?.title.orEmpty(), language = structure?.language ?: LyricLanguage.JAPANESE,
            startBeat = lines.first().startTick / ProjectLimits.PPQ + 1,
            rows = lines.map { line ->
                val reading = structure?.reading(line.id)
                VocalGuideRow(line, reading?.reading.orEmpty(), reading?.text == line.text)
            })
    }

    init {
        mutable.value = planned(mutable.value)
        jobs.launch {
            val result = try { synthesis.voices() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
            mutex.withLock {
                if (closing.value) return@withLock
                when (result) {
                    is TtsResult.Success -> {
                        val voices = result.value.filter { it.offline }
                        publish(state.value.copy(voices = voices, voice = voices.firstOrNull { it.language == state.value.language },
                            loadingVoices = false, failure = if (voices.isEmpty()) TtsFailure(TtsProblem.NO_OFFLINE_VOICE) else state.value.failure))
                    }
                    is TtsResult.Failure -> publish(state.value.copy(loadingVoices = false, failure = result.failure))
                }
            }
        }
        jobs.launch {
            document.collect { value ->
                if (value.revision != snapshot.revision && state.value.phase !in listOf(VocalGuidePhase.APPLYING, VocalGuidePhase.APPLIED, VocalGuidePhase.CLOSED))
                    cancel(TtsProblem.STALE_DOCUMENT)
            }
        }
        jobs.launch {
            availability.collect { value ->
                if (value != VocalGuideAvailability.EDITABLE && state.value.phase != VocalGuidePhase.APPLYING &&
                    (state.value.phase == VocalGuidePhase.PREPARING || preview.state.value.ownsSource))
                    cancel(if (value == VocalGuideAvailability.RECORDING) TtsProblem.RECORDING else TtsProblem.BUSY)
            }
        }
    }

    private fun planned(value: VocalGuideState): VocalGuideState {
        if (value.rows.isEmpty()) return value.copy(plan = null)
        val unconfirmed = value.rows.firstOrNull { !it.confirmed }
        if (unconfirmed != null) return value.copy(plan = null, issue = FlowIssue(FlowProblem.MISSING_READING, unconfirmed.line.id))
        val structure = runCatching {
            val readings = value.rows.associate { row -> row.line.id to LyricReading.create(row.line.id, row.line.text, row.reading, value.language) }
            val sections = originalStructure?.takeIf { it.sections.isNotEmpty() }?.sections?.map { section ->
                section.copy(lines = section.lines.map { requireNotNull(readings[it.lineId]) }.frozen())
            } ?: listOf(LyricSection("Verse", LyricSectionKind.VERSE, value.rows.size.coerceIn(1, 64), readings.values.toList().frozen()))
            LyricStructure(value.title, value.language, sections.frozen())
        }.getOrNull() ?: return value.copy(plan = null, issue = FlowIssue(FlowProblem.MISSING_READING))
        return when (val result = FlowPlanner.plan(snapshot.project.copy(lyricStructure = structure), (value.startBeat - 1) * ProjectLimits.PPQ,
            FlowMode.ONE_BAR, value.rows.associate { it.line.id to it.mode })) {
            is FlowResult.Ready -> value.copy(plan = result.plan, issue = null)
            is FlowResult.Invalid -> value.copy(plan = null, issue = result.issue)
        }
    }
    private fun editable() = !closing.value && !cancelling && state.value.phase !in listOf(VocalGuidePhase.APPLYING, VocalGuidePhase.APPLIED) && document.value.revision == snapshot.revision
    private fun invalidate(value: VocalGuideState) {
        generation.update { it + 1 }; work?.cancel(); preview.requestStop()
        publish(planned(value.copy(phase = VocalGuidePhase.EDITING, rows = value.rows.map { it.copy(prepared = null, failure = null) },
            preparingLine = null, densityConfirmed = false, failure = null)))
    }
    suspend fun placement(title: String = state.value.title, language: LyricLanguage = state.value.language,
                          startBeat: Long = state.value.startBeat): Boolean = mutex.withLock {
        if (!editable() || title.length > 160 || startBeat !in 1..(ProjectLimits.MAX_TIMELINE_TICKS / ProjectLimits.PPQ)) return@withLock false
        invalidate(state.value.copy(title = title, language = language, startBeat = startBeat,
            voice = state.value.voice?.takeIf { it.language == language } ?: state.value.voices.firstOrNull { it.language == language },
            rows = state.value.rows.map { if (language == state.value.language) it else it.copy(confirmed = false) }))
        true
    }
    suspend fun reading(lineId: String, value: String, confirm: Boolean = false): Boolean = mutex.withLock {
        if (!editable() || value.length > 512 || value.any { it < ' ' || it == '\u007f' }) return@withLock false
        val line = state.value.rows.firstOrNull { it.line.id == lineId } ?: return@withLock false
        val valid = runCatching { LyricReading.create(lineId, line.line.text, value, state.value.language) }.isSuccess
        invalidate(state.value.copy(rows = state.value.rows.map { if (it.line.id == lineId) it.copy(reading = value, confirmed = confirm && valid) else it }))
        valid || !confirm
    }
    suspend fun mode(lineId: String?, value: FlowMode): Boolean = mutex.withLock {
        if (!editable()) return@withLock false
        invalidate(state.value.copy(rows = state.value.rows.map { if (lineId == null || it.line.id == lineId) it.copy(mode = value) else it }))
        true
    }
    suspend fun voice(value: TtsVoice): Boolean = mutex.withLock {
        if (!editable() || value !in state.value.voices || value.language != state.value.language) return@withLock false
        invalidate(state.value.copy(voice = value)); true
    }
    suspend fun settings(value: TtsSettings): Boolean = mutex.withLock {
        if (!editable()) return@withLock false
        invalidate(state.value.copy(settings = value)); true
    }
    suspend fun confirmDensity(value: Boolean) = mutex.withLock { if (editable()) publish(state.value.copy(densityConfirmed = value)) }

    suspend fun prepare(lineId: String? = null, regenerate: Boolean = false): Boolean = mutex.withLock {
        if (!editable() || state.value.phase == VocalGuidePhase.PREPARING) return@withLock false
        if (availability.value != VocalGuideAvailability.EDITABLE) {
            publish(state.value.copy(failure = TtsFailure(if (availability.value == VocalGuideAvailability.RECORDING) TtsProblem.RECORDING else TtsProblem.BUSY)))
            return@withLock false
        }
        val blocked = actions.prepareAllowed(snapshot.revision)
        if (blocked is TtsResult.Failure) { publish(state.value.copy(failure = blocked.failure)); return@withLock false }
        val plan = state.value.plan ?: return@withLock false
        val voice = state.value.voice ?: run { publish(state.value.copy(failure = TtsFailure(TtsProblem.NO_OFFLINE_VOICE))); return@withLock false }
        val rows = plan.rows.filter { lineId == null || it.line.id == lineId }
        if (rows.isEmpty()) return@withLock false
        preview.requestStop()
        val token = generation.updateAndGet { it + 1 }
        val settings = state.value.settings
        publish(state.value.copy(phase = VocalGuidePhase.PREPARING, failure = null))
        work = jobs.launch {
            for (row in rows) {
                if (!current(token)) return@launch
                mutex.withLock { if (current(token)) publish(state.value.copy(preparingLine = row.line.id)) }
                val result = try {
                    when (val permitted = actions.prepareAllowed(snapshot.revision)) {
                        is TtsResult.Failure -> permitted
                        is TtsResult.Success -> synthesis.prepare(row, snapshot.project.tempo, voice, settings, regenerate)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
                mutex.withLock {
                    if (!current(token)) return@withLock
                    publish(state.value.copy(rows = state.value.rows.map { previous ->
                        if (previous.line.id != row.line.id) previous else when (result) {
                            is TtsResult.Success -> previous.copy(prepared = result.value, failure = null)
                            is TtsResult.Failure -> previous.copy(prepared = null, failure = result.failure)
                        }
                    }))
                }
            }
            mutex.withLock { if (current(token)) publish(state.value.copy(phase = if (state.value.rows.all { it.prepared != null }) VocalGuidePhase.READY else VocalGuidePhase.EDITING, preparingLine = null)) }
        }
        true
    }
    suspend fun listen(lineId: String): Boolean {
        val entry = mutex.withLock {
            if (!editable() || state.value.phase == VocalGuidePhase.PREPARING) return false
            state.value.rows.firstOrNull { it.line.id == lineId }?.prepared ?: return false
        }
        val result = actions.preview(entry.asset, snapshot.revision)
        if (result is TtsResult.Failure) mutex.withLock { if (!closing.value) publish(state.value.copy(failure = result.failure)) }
        return result is TtsResult.Success
    }
    suspend fun apply(): Boolean {
        val intent = mutex.withLock {
            if (!editable() || state.value.phase != VocalGuidePhase.READY) return false
            val plan = state.value.plan ?: return false
            if (plan.hasDensityAdvice && !state.value.densityConfirmed) {
                publish(state.value.copy(failure = TtsFailure(TtsProblem.DENSITY_CONFIRMATION))); return false
            }
            when (val edit = plan.guideEdit(snapshot.project, state.value.rows.map { requireNotNull(it.prepared) }, "vocal-${snapshot.revision}-${generation.value}")) {
                is TtsResult.Failure -> { publish(state.value.copy(failure = edit.failure)); return false }
                is TtsResult.Success -> { publish(state.value.copy(phase = VocalGuidePhase.APPLYING)); edit.value }
            }
        }
        val operation = jobs.async { preview.stop() is TtsResult.Success && actions.apply(intent, snapshot.revision) }
        work = operation
        val accepted = try { operation.await() }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
        mutex.withLock { if (!closing.value) publish(state.value.copy(phase = if (accepted) VocalGuidePhase.APPLIED else VocalGuidePhase.FAILED,
            failure = if (accepted) null else TtsFailure(TtsProblem.APPLY_REJECTED))) }
        return accepted
    }
    /** Synchronous cancellation fence for Stop, permission opening, stage change and disposal. */
    fun cancel(problem: TtsProblem = TtsProblem.CANCELLED) {
        if (closing.value || state.value.phase in listOf(VocalGuidePhase.APPLYING, VocalGuidePhase.APPLIED)) return
        cancelling = true
        val token = generation.updateAndGet { it + 1 }; work?.cancel(); preview.requestStop()
        jobs.launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock {
            if (!closing.value && generation.value == token) publish(state.value.copy(
                phase = if (problem == TtsProblem.STALE_DOCUMENT) VocalGuidePhase.FAILED else VocalGuidePhase.EDITING,
                rows = state.value.rows.map { it.copy(prepared = null) }, preparingLine = null, failure = TtsFailure(problem)))
            cancelling = false
        } }
    }
    private fun current(token: Long) = !closing.value && generation.value == token && document.value.revision == snapshot.revision
    private fun publish(value: VocalGuideState) { mutable.update { if (closing.value) VocalGuideState(phase = VocalGuidePhase.CLOSED, loadingVoices = false) else value } }
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        generation.update { it + 1 }; work?.cancel(); preview.requestStop()
        try { synthesis.close() } finally { owner.cancel(); mutable.value = VocalGuideState(phase = VocalGuidePhase.CLOSED, loadingVoices = false) }
    }
}
