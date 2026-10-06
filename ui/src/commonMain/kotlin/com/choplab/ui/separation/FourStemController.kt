package com.choplab.ui.separation

import com.choplab.core.DocumentState
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.separation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

enum class FourStemAvailability { EDITABLE, BUSY, RECORDING }
enum class FourStemPhase { IDLE, PREPARING, CANCELLING, READY, APPLYING, APPLIED, FAILED, CLOSED }
data class FourStemState(
    val sourceName: String? = null,
    val phase: FourStemPhase = FourStemPhase.IDLE,
    val availability: FourStemAvailability = FourStemAvailability.EDITABLE,
    val allowModelDownload: Boolean = false,
    val mix: StemMix = StemMix.ALL,
    val firstBar: Int = 1,
    val progress: SeparationProgress? = null,
    val prepared: PreparedFourStems? = null,
    val problem: SeparationProblem? = null,
    val memoryReceipt: SeparationMemoryReceipt? = null,
) {
    val editable: Boolean get() = phase in listOf(FourStemPhase.IDLE, FourStemPhase.READY, FourStemPhase.FAILED) &&
        availability == FourStemAvailability.EDITABLE && sourceName != null && problem != SeparationProblem.STALE_DOCUMENT
}

interface FourStemActions {
    /** Recheck actual arming/recording/work flags under the presenter's action lock, not only the derived availability flow. */
    suspend fun prepareAllowed(expectedRevision: Long): SeparationResult<Unit>
    /** Directly await the presenter's serialized applyPreparedEdit with Studio's atomic expectedRevision guard. */
    suspend fun apply(intent: Intent.SetArrangement, expectedRevision: Long): Boolean
}

/** A dialog owns one cancellable worker; opening and preparation never change the source or song. */
class FourStemController(private val document: StateFlow<DocumentState>, private val availability: StateFlow<FourStemAvailability>,
                         private val worker: FourStemPort, private val actions: FourStemActions, scope: CoroutineScope) {
    private val snapshot = document.value
    private val source = snapshot.project.source?.let { snapshot.project.asset(it.assetHash) }
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val closing = MutableStateFlow(false)
    private val generation = MutableStateFlow(0L)
    @Volatile private var work: Job? = null
    private val mutable = MutableStateFlow(FourStemState(source?.name, availability = availability.value,
        problem = if (source == null) SeparationProblem.INVALID_INPUT else null))
    val state = mutable.asStateFlow()
    init {
        jobs.launch { document.collect { current ->
            if (current.revision != snapshot.revision && state.value.phase !in listOf(FourStemPhase.APPLYING, FourStemPhase.APPLIED))
                cancel(SeparationProblem.STALE_DOCUMENT)
        } }
        jobs.launch { availability.collect { current ->
            update { it.copy(availability = current) }
            if (current != FourStemAvailability.EDITABLE && state.value.phase == FourStemPhase.PREPARING) cancel(blocked(current))
        } }
    }

    suspend fun settings(mix: StemMix = state.value.mix, firstBar: Int = state.value.firstBar,
                         allowModelDownload: Boolean = state.value.allowModelDownload): Boolean = mutex.withLock {
        if (!guard() || firstBar !in 1..26_041) return@withLock false
        update { it.copy(mix = mix, firstBar = firstBar, allowModelDownload = allowModelDownload, problem = null) }
        true
    }

    /** Starts the worker and returns; modal lifetime never holds the presenter's serialized action lock. */
    suspend fun start(): Boolean = mutex.withLock {
        if (!guard()) return@withLock false
        val asset = source ?: return@withLock false
        val token = generation.updateAndGet { it + 1 }
        val download = state.value.allowModelDownload
        update { it.copy(phase = FourStemPhase.PREPARING, prepared = null, problem = null, progress = null, memoryReceipt = null) }
        val operation = jobs.launch(start = CoroutineStart.LAZY) {
            try {
                var preparedByWorker = false
                val result = when (val allowed = actions.prepareAllowed(snapshot.revision)) {
                    is SeparationResult.Failure -> allowed
                    is SeparationResult.Success -> {
                        ensureCurrent(token)
                        preparedByWorker = true
                        worker.prepare(asset, download) { progress ->
                            val memory = worker.memoryReceipt()
                            update(token) { if (current(token)) it.copy(progress = progress, memoryReceipt = memory) else it }
                        }
                    }
                }
                val memory = if (preparedByWorker) worker.memoryReceipt() else null
                mutex.withLock {
                    if (current(token)) {
                        work = null
                        update(token) { before -> when (result) {
                            is SeparationResult.Success -> before.copy(phase = FourStemPhase.READY, prepared = result.value, problem = null, memoryReceipt = memory)
                            is SeparationResult.Failure -> before.copy(phase = FourStemPhase.FAILED, problem = result.failure.problem, memoryReceipt = memory)
                        } }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (refused: PreparationRefused) { update(token) { it.copy(phase = FourStemPhase.FAILED, problem = refused.problem) } }
            catch (_: Exception) { update(token) { it.copy(phase = FourStemPhase.FAILED, problem = SeparationProblem.FAILED) } }
            finally { finish(currentCoroutineContext()[Job]) }
        }
        track(operation)
        true
    }

    suspend fun apply(): Boolean {
        val pending = mutex.withLock {
            if (!guard() || state.value.phase != FourStemPhase.READY) return false
            val prepared = state.value.prepared ?: return false
            val value = state.value
            val prefix = freshPrefix(snapshot.project, snapshot.revision)
            val intent = when (val edit = prepared.placement(snapshot.project, (value.firstBar - 1L) * 3840, value.mix, prefix)) {
                is SeparationResult.Failure -> { update { it.copy(problem = edit.failure.problem) }; return false }
                is SeparationResult.Success -> edit.value
            }
            val token = generation.updateAndGet { it + 1 }
            update { it.copy(phase = FourStemPhase.APPLYING, problem = null) }
            val operation = jobs.async(start = CoroutineStart.LAZY) {
                try { ensureCurrent(token); actions.apply(intent, snapshot.revision) }
                finally { finish(currentCoroutineContext()[Job]) }
            }
            track(operation)
            token to operation
        }
        val accepted = try { pending.second.await() }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
        update(pending.first) { it.copy(
            phase = if (accepted) FourStemPhase.APPLIED else FourStemPhase.FAILED,
            problem = if (accepted) null else when {
                document.value.revision != snapshot.revision -> SeparationProblem.STALE_DOCUMENT
                availability.value != FourStemAvailability.EDITABLE -> blocked(availability.value)
                else -> SeparationProblem.FAILED
            }) }
        return accepted
    }

    /** Stop, navigation, host loss and close can set this fence without awaiting the native call. */
    fun cancel(problem: SeparationProblem = SeparationProblem.CANCELLED) {
        if (closing.value || state.value.phase in listOf(FourStemPhase.APPLYING, FourStemPhase.APPLIED)) return
        generation.update { it + 1 }
        worker.cancel()
        val pending = work
        update { it.copy(phase = if (pending != null && !pending.isCompleted) FourStemPhase.CANCELLING else FourStemPhase.FAILED,
            prepared = null, problem = problem) }
        pending?.cancel()
    }
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        generation.update { it + 1 }
        try { worker.close() } finally {
            work?.cancel(); owner.cancel()
            mutable.value = FourStemState(phase = FourStemPhase.CLOSED)
        }
    }
    private suspend fun finish(operation: Job?) = withContext(NonCancellable) {
        mutex.withLock { if (work === operation) {
            work = null
            if (state.value.phase == FourStemPhase.CANCELLING) update { it.copy(phase = FourStemPhase.FAILED, progress = null) }
        } }
    }
    private fun track(operation: Job) {
        work = operation
        // A lazy job cancelled before its body starts does not enter that body's finally block.
        operation.invokeOnCompletion { if (!closing.value) jobs.launch(start = CoroutineStart.UNDISPATCHED) { finish(operation) } }
        operation.start()
    }
    private fun guard(): Boolean {
        val problem = when {
            closing.value -> SeparationProblem.CLOSED
            source == null -> SeparationProblem.INVALID_INPUT
            document.value.revision != snapshot.revision -> SeparationProblem.STALE_DOCUMENT
            availability.value != FourStemAvailability.EDITABLE -> blocked(availability.value)
            !state.value.editable || work?.isCompleted == false -> SeparationProblem.BUSY
            else -> null
        }
        if (problem != null) { update { it.copy(problem = problem) }; return false }
        return true
    }
    private suspend fun ensureCurrent(token: Long) {
        currentCoroutineContext().ensureActive()
        if (closing.value || generation.value != token) throw CancellationException()
        if (document.value.revision != snapshot.revision) throw PreparationRefused(SeparationProblem.STALE_DOCUMENT)
        if (availability.value != FourStemAvailability.EDITABLE) throw PreparationRefused(blocked(availability.value))
    }
    private fun current(token: Long) = !closing.value && token == generation.value && document.value.revision == snapshot.revision
    private fun update(token: Long? = null, transform: (FourStemState) -> FourStemState) {
        mutable.update { if (closing.value || (token != null && generation.value != token)) it else transform(it) }
    }
    private fun blocked(value: FourStemAvailability) = if (value == FourStemAvailability.RECORDING) SeparationProblem.RECORDING else SeparationProblem.BUSY
    private fun freshPrefix(project: Project, revision: Long): String {
        var number = 0
        while (true) {
            val candidate = "stems-$revision-${++number}"
            if (project.tracks.none { it.id.startsWith("$candidate-") } && project.clips.none { it.id.startsWith("$candidate-") }) return candidate
        }
    }
    private class PreparationRefused(val problem: SeparationProblem) : IllegalStateException()
}
