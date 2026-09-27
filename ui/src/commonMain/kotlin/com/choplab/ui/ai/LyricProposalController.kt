package com.choplab.ui.ai

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

enum class LyricProposalPhase { IDLE, GENERATING, PREVIEW, APPLYING, APPLIED, FAILED, CLOSED }
data class LyricProposalState(
    val phase: LyricProposalPhase = LyricProposalPhase.IDLE,
    val proposal: LyricProposal? = null,
    val before: FrozenList<LyricLine> = frozenListOf(),
    val placed: FrozenList<LyricLine> = frozenListOf(),
    val usage: LyricUsage? = null,
    val modelVersion: String? = null,
    val failure: LyricAiFailure? = null,
    val retryRemainingSeconds: Long = 0,
) {
    override fun toString() = "LyricProposalState(phase=$phase)"
}

/** Host must apply through Studio's atomic Action.Edit(SetLyrics(lines), expectedRevision) guard. */
fun interface LyricProposalApply {
    suspend fun replace(lines: FrozenList<LyricLine>, expectedRevision: Long): Boolean
}

class LyricProposalController(
    private val document: StateFlow<DocumentState>,
    private val provider: LlmProvider,
    private val apply: LyricProposalApply,
    scope: CoroutineScope,
    val availability: LyricProviderAvailability = LyricProviderAvailability.UNVERIFIED,
    private val clockMillis: () -> Long = defaultClock(),
) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(LyricProposalState())
    val state: StateFlow<LyricProposalState> = mutable.asStateFlow()
    private var generation = 0L
    private var basedOnRevision: Long? = null
    private var retryAt = 0L
    private var cooldown: Job? = null
    private val closing = MutableStateFlow(false)
    private val closed get() = closing.value
    @Volatile private var work: Job? = null
    @Volatile private var key: SessionApiKey? = null

    init {
        jobs.launch {
            document.collect { value -> mutex.withLock {
                if (!closed && basedOnRevision != null && value.revision != basedOnRevision &&
                    state.value.phase in listOf(LyricProposalPhase.GENERATING, LyricProposalPhase.PREVIEW)) {
                    generation++; work?.cancel(); key?.close(); key = null
                    publish(state.value.copy(phase = LyricProposalPhase.FAILED, proposal = null, placed = frozenListOf(),
                        failure = LyricAiFailure(LyricAiProblem.STALE_DOCUMENT, costUnknown = true)))
                }
            } }
        }
    }

    /** Exactly one explicit button press and consent authorizes one attempt. There is no background retry. */
    suspend fun generate(request: LyricRequest, sessionKey: SessionApiKey, startTick: Long, beatsPerLine: Int,
                         consent: Boolean): Boolean = mutex.withLock {
        if (closed || state.value.phase == LyricProposalPhase.GENERATING || state.value.phase == LyricProposalPhase.APPLYING) {
            sessionKey.close(); return@withLock false
        }
        if (availability != LyricProviderAvailability.AVAILABLE) {
            sessionKey.close(); publish(LyricProposalState(phase = LyricProposalPhase.FAILED,
                failure = LyricAiFailure(LyricAiProblem.PROVIDER_UNVERIFIED))); return@withLock false
        }
        if (!consent) {
            sessionKey.close(); publish(state.value.copy(phase = LyricProposalPhase.FAILED,
                failure = LyricAiFailure(LyricAiProblem.CONSENT_REQUIRED))); return@withLock false
        }
        if (clockMillis() < retryAt) { sessionKey.close(); return@withLock false }
        if (startTick !in 0..ProjectLimits.MAX_TIMELINE_TICKS || beatsPerLine !in 1..16) {
            sessionKey.close(); publish(LyricProposalState(phase = LyricProposalPhase.FAILED,
                failure = LyricAiFailure(LyricAiProblem.INVALID_INPUT))); return@withLock false
        }
        work?.cancel(); key?.close()
        key = sessionKey
        // Dialog/host close is synchronous and may win while this request is validating under the mutex.
        if (closed) { sessionKey.close(); key = null; return@withLock false }
        val token = ++generation
        val snapshot = document.value
        basedOnRevision = snapshot.revision
        publish(LyricProposalState(phase = LyricProposalPhase.GENERATING, before = snapshot.project.lyrics))
        work = jobs.launch {
            val result = try { provider.lyrics(request, sessionKey) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { LyricProviderResult.Failure(LyricAiFailure(LyricAiProblem.PROVIDER_REJECTED, costUnknown = true)) }
                finally { sessionKey.close() }
            mutex.withLock {
                if (closed || token != generation || document.value.revision != snapshot.revision) return@withLock
                key = null
                when (result) {
                    is LyricProviderResult.Success -> {
                        val placed = runCatching { result.proposal.place(startTick, beatsPerLine, "ai-${snapshot.revision}-$token") }.getOrNull()
                        if (placed == null) publish(state.value.copy(phase = LyricProposalPhase.FAILED,
                            failure = LyricAiFailure(LyricAiProblem.INVALID_RESPONSE, costUnknown = true)))
                        else publish(state.value.copy(phase = LyricProposalPhase.PREVIEW, proposal = result.proposal,
                            placed = placed, usage = result.usage, modelVersion = result.modelVersion))
                    }
                    is LyricProviderResult.Failure -> {
                        val seconds = result.failure.retryAfterSeconds ?: 0
                        retryAt = clockMillis() + seconds * 1_000
                        publish(state.value.copy(phase = LyricProposalPhase.FAILED, failure = result.failure, retryRemainingSeconds = seconds))
                        cooldown?.cancel()
                        if (seconds > 0) cooldown = jobs.launch {
                            while (isActive && !closed && clockMillis() < retryAt) {
                                delay(1_000)
                                mutex.withLock {
                                    if (!closed) publish(state.value.copy(retryRemainingSeconds =
                                        ((retryAt - clockMillis()).coerceAtLeast(0) + 999) / 1_000))
                                }
                            }
                        }
                    }
                }
            }
        }
        true
    }

    suspend fun applyPreview(): Boolean {
        val pending = mutex.withLock {
            if (closed || state.value.phase != LyricProposalPhase.PREVIEW) return false
            val revision = basedOnRevision ?: return false
            if (document.value.revision != revision) {
                publish(state.value.copy(phase = LyricProposalPhase.FAILED, proposal = null, placed = frozenListOf(),
                    failure = LyricAiFailure(LyricAiProblem.STALE_DOCUMENT))); return false
            }
            publish(state.value.copy(phase = LyricProposalPhase.APPLYING))
            Triple(generation, revision, state.value.placed)
        }
        // The actor checks the revision again atomically; a change after the preview check is still refused.
        val operation = jobs.async { apply.replace(pending.third, pending.second) }
        work = operation
        val accepted = try { operation.await() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
        return mutex.withLock {
            if (closed || generation != pending.first) return@withLock false
            basedOnRevision = null
            publish(state.value.copy(phase = if (accepted) LyricProposalPhase.APPLIED else LyricProposalPhase.FAILED,
                failure = if (accepted) null else LyricAiFailure(LyricAiProblem.APPLY_REJECTED)))
            accepted
        }
    }

    suspend fun cancel() = mutex.withLock {
        if (closed || state.value.phase == LyricProposalPhase.APPLYING) return@withLock
        val attempted = state.value.phase == LyricProposalPhase.GENERATING || state.value.proposal != null
        generation++; work?.cancel(); key?.close(); key = null; basedOnRevision = null
        publish(LyricProposalState(phase = LyricProposalPhase.FAILED,
            failure = LyricAiFailure(LyricAiProblem.CANCELLED, costUnknown = attempted),
            retryRemainingSeconds = ((retryAt - clockMillis()).coerceAtLeast(0) + 999) / 1_000))
    }

    /** Host/dialog lifetime owns the provider. Close cannot trigger an edit or retain the proposal/credentials. */
    fun close() {
        if (!closing.compareAndSet(false, true)) return
        work?.cancel(); key?.close(); key = null
        try { provider.close() } finally {
            owner.cancel()
            mutable.value = LyricProposalState(phase = LyricProposalPhase.CLOSED)
        }
    }
    private fun publish(value: LyricProposalState) {
        mutable.update { if (closed) LyricProposalState(phase = LyricProposalPhase.CLOSED) else value }
    }
    private companion object {
        fun defaultClock(): () -> Long { val start = TimeSource.Monotonic.markNow(); return { start.elapsedNow().inWholeMilliseconds } }
    }
}
