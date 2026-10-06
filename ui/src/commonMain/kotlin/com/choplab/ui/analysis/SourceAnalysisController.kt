package com.choplab.ui.analysis

import com.choplab.core.DocumentState
import com.choplab.core.analysis.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

enum class SourceAnalysisAvailability { EDITABLE, BUSY, RECORDING }
enum class SourceAnalysisPhase { READY, ANALYSING, RESULT, APPLYING, CLOSED }
enum class SourceAnalysisProblem { STALE, BUSY, RECORDING, FAILED, APPLY_FAILED }
interface SourceAnalysisPorts {
    suspend fun analyse(asset: Asset, range: FrameRange): SourceMusicResult
    suspend fun apply(intent: Intent, expectedRevision: Long): Boolean
}
data class SourceAnalysisState(
    val asset: Asset, val range: FrameRange, val revision: Long, val tempo: Tempo,
    val availability: SourceAnalysisAvailability = SourceAnalysisAvailability.EDITABLE,
    val phase: SourceAnalysisPhase = SourceAnalysisPhase.READY,
    val result: SourceMusicResult? = null, val selectedMilliBpm: Int? = null,
    val problem: SourceAnalysisProblem? = null, val applied: Boolean = false,
) {
    val editable get() = availability == SourceAnalysisAvailability.EDITABLE && problem != SourceAnalysisProblem.STALE &&
        phase != SourceAnalysisPhase.CLOSED && phase != SourceAnalysisPhase.APPLYING
    val canApply get() = editable && phase == SourceAnalysisPhase.RESULT && selectedMilliBpm != null && selectedMilliBpm != tempo.milliBpm
}
sealed interface SourceAnalysisAction {
    data object Analyse : SourceAnalysisAction
    data object Cancel : SourceAnalysisAction
    data class SelectTempo(val milliBpm: Int) : SourceAnalysisAction
    data object Apply : SourceAnalysisAction
}

/** Candidates stay session-only. Only an explicit tempo Apply crosses the document's revision boundary. */
class SourceAnalysisController(
    private val document: StateFlow<DocumentState>,
    private val availability: StateFlow<SourceAnalysisAvailability>,
    private val ports: SourceAnalysisPorts,
    scope: CoroutineScope,
) {
    private val initial = document.value
    private val source = requireNotNull(initial.project.source)
    private val asset = initial.project.asset(source.assetHash)
    private val range = FrameRange(source.range.start, minOf(source.range.end, source.range.start + asset.sampleRate.toLong() * SourceMusicAnalysis.MAX_SECONDS))
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SourceAnalysisState(asset, range, initial.revision, initial.project.tempo, availability.value))
    val state = mutable.asStateFlow()
    @Volatile private var serial = 0L
    @Volatile private var closed = false
    @Volatile private var work: Job? = null
    init {
        jobs.launch { document.collect { current -> lock.withLock {
            if (!closed && current.revision != state.value.revision && state.value.phase != SourceAnalysisPhase.APPLYING) {
                serial++; work?.cancel()
                mutable.value = state.value.copy(phase = SourceAnalysisPhase.READY, result = null, selectedMilliBpm = null,
                    problem = SourceAnalysisProblem.STALE, applied = false)
            }
        } } }
        jobs.launch { availability.collect { value -> lock.withLock {
            if (!closed) {
                mutable.value = state.value.copy(availability = value)
                if (value != SourceAnalysisAvailability.EDITABLE && state.value.phase == SourceAnalysisPhase.ANALYSING) {
                    serial++; work?.cancel()
                    mutable.value = state.value.copy(phase = SourceAnalysisPhase.READY, problem = blocked(value))
                }
            }
        } } }
    }

    suspend fun dispatch(action: SourceAnalysisAction): Boolean {
        if (action == SourceAnalysisAction.Analyse) return analyse()
        if (action == SourceAnalysisAction.Apply) return apply()
        return lock.withLock {
            if (closed) return@withLock false
            when (action) {
                SourceAnalysisAction.Cancel -> {
                    if (state.value.phase != SourceAnalysisPhase.ANALYSING) return@withLock false
                    serial++; work?.cancel(); mutable.value = state.value.copy(phase = SourceAnalysisPhase.READY, problem = null); true
                }
                is SourceAnalysisAction.SelectTempo -> {
                    if (!guard() || state.value.phase != SourceAnalysisPhase.RESULT || state.value.result?.tempos?.none { it.milliBpm == action.milliBpm } != false) return@withLock false
                    mutable.value = state.value.copy(selectedMilliBpm = action.milliBpm, applied = false); true
                }
                else -> false
            }
        }
    }

    private suspend fun analyse(): Boolean {
        val pending = lock.withLock {
            if (!guard() || state.value.phase == SourceAnalysisPhase.ANALYSING) return false
            mutable.value = state.value.copy(phase = SourceAnalysisPhase.ANALYSING, result = null, selectedMilliBpm = null, problem = null, applied = false)
            val token = ++serial
            val task = jobs.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                ports.analyse(asset, range).also { result ->
                    val first = (range.start * SourceMusicAnalysis.RATE + asset.sampleRate - 1) / asset.sampleRate
                    val end = range.end * SourceMusicAnalysis.RATE / asset.sampleRate
                    require(result.frames.toLong() == (end - first).coerceAtLeast(0)) { "Incomplete analysis" }
                }
            }
            work = task
            token to task
        }
        val (token, task) = pending
        task.start()
        val result = try { task.await() } catch (_: CancellationException) { null } catch (_: Exception) { null }
        return lock.withLock {
            if (closed || serial != token || !guard()) return@withLock false
            mutable.value = state.value.copy(phase = if (result == null) SourceAnalysisPhase.READY else SourceAnalysisPhase.RESULT,
                result = result, problem = if (result == null) SourceAnalysisProblem.FAILED else null)
            result != null
        }
    }

    private suspend fun apply(): Boolean {
        val pending = lock.withLock {
            if (!guard() || !state.value.canApply) return false
            val current = state.value
            mutable.value = current.copy(phase = SourceAnalysisPhase.APPLYING, problem = null)
            Triple(++serial, current.revision, Intent.SetTempo(Tempo(requireNotNull(current.selectedMilliBpm), current.tempo.swingPermille)))
        }
        val task = jobs.async { ports.apply(pending.third, pending.second) }
        work = task
        val accepted = try { task.await() } catch (_: CancellationException) { false } catch (_: Exception) { false }
        return lock.withLock {
            if (closed || serial != pending.first) return@withLock false
            val current = document.value
            val unchangedSource = current.project.source == source
            val stale = !unchangedSource || current.revision != pending.second + (if (accepted) 1 else 0)
            mutable.value = state.value.copy(revision = current.revision, tempo = current.project.tempo,
                phase = if (stale) SourceAnalysisPhase.READY else SourceAnalysisPhase.RESULT,
                result = state.value.result.takeUnless { stale }, selectedMilliBpm = state.value.selectedMilliBpm.takeUnless { stale },
                applied = accepted && !stale, problem = when { stale -> SourceAnalysisProblem.STALE; accepted -> null; else -> SourceAnalysisProblem.APPLY_FAILED })
            accepted
        }
    }

    private fun guard(): Boolean {
        val problem = when {
            document.value.revision != state.value.revision || state.value.problem == SourceAnalysisProblem.STALE -> SourceAnalysisProblem.STALE
            availability.value != SourceAnalysisAvailability.EDITABLE -> blocked(availability.value)
            else -> null
        }
        if (problem != null && !closed) mutable.value = state.value.copy(problem = problem)
        return !closed && problem == null && state.value.phase != SourceAnalysisPhase.APPLYING
    }
    private fun blocked(value: SourceAnalysisAvailability) = if (value == SourceAnalysisAvailability.RECORDING) SourceAnalysisProblem.RECORDING else SourceAnalysisProblem.BUSY
    suspend fun close(): Boolean = lock.withLock {
        if (state.value.phase == SourceAnalysisPhase.APPLYING) return@withLock false
        dispose(); true
    }
    fun dispose() { closed = true; serial++; work?.cancel(); owner.cancel(); mutable.value = state.value.copy(phase = SourceAnalysisPhase.CLOSED) }
}
