package com.choplab.ui.source

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class OnlineCatalog { VIDEOS, MUSIC }
enum class OnlinePhase { READY, SEARCHING, CANDIDATES, INSPECTING, DETAILS, DOWNLOADING, SAVING, SAVED, FAILED, CANCELLED, CLOSED }
enum class OnlineProblem {
    INVALID_INPUT, UNAVAILABLE, RESTRICTED, RATE_LIMITED, TOO_LARGE, MALFORMED_RESPONSE,
    NETWORK, TIMED_OUT, CANCELLED, CLOSED, BUSY, UNSUPPORTED_FORMAT, FORMAT_CHANGED,
    INVALID_AUDIO, STORAGE, RECORDING, STALE_DOCUMENT, APPLY_REJECTED,
}
enum class OnlineAvailability { EDITABLE, BUSY, RECORDING }
enum class OnlineUseResult { APPLIED, BUSY, RECORDING, STALE_DOCUMENT, REJECTED, CLOSED }

data class OnlineAudioFormat(
    val id: String, val container: String, val codec: String?, val sampleRate: Int?, val channels: Int?,
    val bitrate: Int?, val approximateBitrate: Boolean, val bytes: Long?, val language: String?,
    val trackName: String?, val trackType: String?, val dynamicRangeCompressed: Boolean?,
) {
    fun problem(maxBytes: Long?): OnlineProblem? = when {
        bytes == null -> null
        bytes <= 0 -> OnlineProblem.MALFORMED_RESPONSE
        maxBytes != null && bytes > maxBytes -> OnlineProblem.TOO_LARGE
        else -> null
    }
}
data class OnlineCandidate(
    val id: String, val title: String, val uploader: String, val durationSeconds: Double,
    val artist: String? = null, val album: String? = null, val uploaderVerified: Boolean? = null,
    val formats: List<OnlineAudioFormat> = emptyList(), val selectedFormat: String? = null,
    val artwork: ImageBitmap? = null,
)
/** Opaque library identity only: platform paths and signed media URLs never enter UI state. */
data class OnlineSaved(val id: String, val title: String)
data class OnlineWorkerState(
    val phase: OnlinePhase = OnlinePhase.READY, val busy: Boolean = false,
    val candidates: List<OnlineCandidate> = emptyList(), val details: OnlineCandidate? = null,
    val progress: Int? = null, val saved: OnlineSaved? = null, val problem: OnlineProblem? = null,
    val artworkUnavailable: Boolean = false, val artworkLoading: Boolean = false, val maxDownloadBytes: Long? = null, val failedOperation: OnlinePhase? = null,
)
interface OnlineSourcePort {
    val state: StateFlow<OnlineWorkerState>
    fun search(query: String, catalog: OnlineCatalog): Boolean
    fun inspect(id: String): Boolean
    fun selectFormat(id: String): Boolean
    fun save(id: String): Boolean
    fun cancel()
    /** Must enqueue output stop immediately, without waiting for provider/file operations. */
    fun stopAll()
    fun close()
}
fun interface OnlineSourceApply {
    /** Host rechecks recording/preparation/work and expected revision at the actual Studio import boundary. */
    suspend fun useOriginal(savedId: String, expectedRevision: Long): OnlineUseResult
}
sealed interface OnlineSourceAction {
    data class Query(val value: String) : OnlineSourceAction
    data class Catalog(val value: OnlineCatalog) : OnlineSourceAction
    data object Search : OnlineSourceAction
    data class Inspect(val id: String) : OnlineSourceAction
    data class Format(val id: String) : OnlineSourceAction
    data object BackToCandidates : OnlineSourceAction
    data object Save : OnlineSourceAction
    data object UseOriginal : OnlineSourceAction
    data object Cancel : OnlineSourceAction
}
data class OnlineSourceView(
    val worker: OnlineWorkerState = OnlineWorkerState(), val query: String = "",
    val catalog: OnlineCatalog = OnlineCatalog.VIDEOS, val submittedQuery: String? = null,
    val submittedCatalog: OnlineCatalog? = null, val selectedId: String? = null,
    val applying: Boolean = false, val applied: Boolean = false, val closed: Boolean = false,
    val availability: OnlineAvailability = OnlineAvailability.EDITABLE, val issue: OnlineProblem? = null,
    val showingDetails: Boolean = false,
) {
    val inputMatches: Boolean get() = query.trim() == submittedQuery && catalog == submittedCatalog
    val editable: Boolean get() = !closed && !applying && !applied && !worker.busy
    val canSearch: Boolean get() = editable && query.trim().length in 1..240 && query.none { it.code < 32 }
    val canInspect: Boolean get() = editable && inputMatches
    val selectedFormat get() = worker.details?.formats?.singleOrNull { it.id == worker.details.selectedFormat }
    val canSave: Boolean get() = canInspect && worker.saved == null && worker.details?.id == selectedId &&
        selectedFormat != null && selectedFormat?.problem(worker.maxDownloadBytes) == null
    val canUse: Boolean get() = editable && inputMatches && worker.saved != null &&
        availability == OnlineAvailability.EDITABLE && issue != OnlineProblem.STALE_DOCUMENT
}

/** Dialog lifetime controller. Search, format selection and Save never call the document apply port. */
class OnlineSourceController(
    private val port: OnlineSourcePort,
    private val apply: OnlineSourceApply,
    private val expectedRevision: Long,
    scope: CoroutineScope,
    private val availability: StateFlow<OnlineAvailability> = MutableStateFlow(OnlineAvailability.EDITABLE),
) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + owner)
    private val lock = Mutex()
    private val pendingApply = MutableStateFlow<Job?>(null)
    private val stopGeneration = MutableStateFlow(0L)
    private val mutable = MutableStateFlow(OnlineSourceView(worker = port.state.value, availability = availability.value))
    val state = mutable.asStateFlow()
    init {
        ownedScope.launch { port.state.collect { worker -> mutable.update { if (it.closed) it else it.copy(worker = worker) } } }
        ownedScope.launch { availability.collect { value -> mutable.update { if (it.closed) it else it.copy(availability = value) } } }
    }

    suspend fun dispatch(action: OnlineSourceAction): Boolean = lock.withLock {
        val current = mutable.value.copy(worker = port.state.value, availability = availability.value)
        if (current.closed || current.applying || current.applied) return@withLock false
        when (action) {
            is OnlineSourceAction.Query -> {
                if (!current.editable) return@withLock false
                val value = action.value.take(241)
                if (value == current.query) return@withLock true
                if (value.trim() == current.query.trim() && value.none { it.code < 32 } && value.length <= 240) {
                    mutable.update { it.copy(query = value) }; return@withLock true
                }
                mutable.update { it.copy(query = value, submittedQuery = null, submittedCatalog = null, selectedId = null,
                    showingDetails = false, issue = if (value.any { char -> char.code < 32 } || value.length > 240) OnlineProblem.INVALID_INPUT else null) }; true
            }
            is OnlineSourceAction.Catalog -> {
                if (!current.editable) return@withLock false
                if (action.value == current.catalog) return@withLock true
                mutable.update { it.copy(catalog = action.value, submittedQuery = null, submittedCatalog = null, selectedId = null, showingDetails = false, issue = null) }; true
            }
            OnlineSourceAction.Search -> {
                if (!current.canSearch || !port.search(current.query.trim(), current.catalog)) return@withLock false
                mutable.update { it.copy(submittedQuery = current.query.trim(), submittedCatalog = current.catalog, selectedId = null, showingDetails = false, issue = null) }; true
            }
            is OnlineSourceAction.Inspect -> {
                if (!current.canInspect || current.worker.candidates.none { it.id == action.id }) return@withLock false
                if (current.worker.details?.id == action.id && current.selectedId == action.id && current.worker.phase != OnlinePhase.FAILED) {
                    mutable.update { it.copy(showingDetails = true) }; return@withLock true
                }
                if (!port.inspect(action.id)) return@withLock false
                mutable.update { it.copy(selectedId = action.id, showingDetails = true, issue = null) }; true
            }
            OnlineSourceAction.BackToCandidates -> { mutable.update { it.copy(showingDetails = false) }; true }
            is OnlineSourceAction.Format -> {
                val format = current.worker.details?.formats?.singleOrNull { it.id == action.id } ?: return@withLock false
                if (!current.canInspect || current.worker.details?.id != current.selectedId) return@withLock false
                format.problem(current.worker.maxDownloadBytes)?.let { issue -> mutable.update { it.copy(issue = issue) }; return@withLock false }
                if (current.worker.details?.selectedFormat == action.id) return@withLock true
                if (!port.selectFormat(action.id)) return@withLock false
                mutable.update { it.copy(issue = null) }; true
            }
            OnlineSourceAction.Save -> {
                current.selectedFormat?.problem(current.worker.maxDownloadBytes)?.let { issue -> mutable.update { it.copy(issue = issue) }; return@withLock false }
                if (!current.canSave || !port.save(requireNotNull(current.selectedId))) return@withLock false
                mutable.update { it.copy(issue = null) }; true
            }
            OnlineSourceAction.Cancel -> {
                if (!current.worker.busy && !current.worker.artworkLoading) return@withLock false
                port.cancel(); true
            }
            OnlineSourceAction.UseOriginal -> {
                if (!current.canUse) return@withLock false
                val saved = requireNotNull(current.worker.saved)
                val generation = stopGeneration.value
                mutable.update { it.copy(applying = true, issue = null) }
                // Opening this dialog must return to the host immediately. Only explicit Apply awaits its guarded import.
                val task = ownedScope.launch(start = CoroutineStart.LAZY) {
                    val result = try { apply.useOriginal(saved.id, expectedRevision) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { OnlineUseResult.REJECTED }
                    lock.withLock {
                        mutable.update { if (it.closed) it else it.copy(applying = false, applied = result == OnlineUseResult.APPLIED,
                            issue = when (result) {
                                OnlineUseResult.APPLIED -> null
                                OnlineUseResult.BUSY -> OnlineProblem.BUSY
                                OnlineUseResult.RECORDING -> OnlineProblem.RECORDING
                                OnlineUseResult.STALE_DOCUMENT -> OnlineProblem.STALE_DOCUMENT
                                OnlineUseResult.REJECTED -> OnlineProblem.APPLY_REJECTED
                                OnlineUseResult.CLOSED -> OnlineProblem.CLOSED
                            }) }
                    }
                }
                pendingApply.value = task
                task.invokeOnCompletion { cause ->
                    pendingApply.compareAndSet(task, null)
                    if (cause is CancellationException) mutable.update {
                        if (it.closed) it else it.copy(applying = false, issue = OnlineProblem.CANCELLED)
                    }
                }
                if (stopGeneration.value != generation || mutable.value.closed) task.cancel() else task.start()
                true
            }
        }
    }

    fun stopAll() { if (!mutable.value.closed) port.stopAll() }
    /** The host calls this synchronously before enqueueing Stop, including an Apply still waiting to run. */
    fun cancelPendingApply() {
        stopGeneration.update { it + 1 }
        pendingApply.value?.cancel()
    }
    suspend fun requestClose(): Boolean = lock.withLock {
        if (mutable.value.applying) false else { close(); true }
    }
    /** Host shutdown may force disposal; ordinary dialog dismissal uses requestClose. */
    fun close() {
        while (true) {
            val previous = mutable.value
            if (previous.closed) return
            if (mutable.compareAndSet(previous, previous.copy(closed = true))) break
        }
        owner.cancel()
        port.close()
    }
}
