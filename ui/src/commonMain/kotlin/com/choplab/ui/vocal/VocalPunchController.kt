package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToLong

data class PunchCompletion(val result: VocalPunchResult, val saved: Boolean)
interface VocalPunchActions {
    /** Claims the host's single recording slot and saves all returned candidates before releasing it. */
    suspend fun record(request: VocalPunchRequest, expectedRevision: Long): PunchCompletion
    fun stop()
}
data class VocalPunchState(val startSeconds: String, val endSeconds: String, val preRollBars: Int = 1,
    val countInBars: Int = 1, val passes: Int = 1, val manualMillis: String = "0", val busy: Boolean = false,
    val stopping: Boolean = false, val closing: Boolean = false, val closed: Boolean = false, val saved: Int = 0, val problem: PunchProblem? = null)

/** Configuration is session-only. Stop/Close preserve completed and partial candidates; Apply belongs to comp. */
class VocalPunchController(private val document: StateFlow<DocumentState>, val progress: StateFlow<VocalPunchProgress>,
    private val actions: VocalPunchActions, scope: CoroutineScope, start: Long, end: Long) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutable = MutableStateFlow(VocalPunchState((start / 48_000.0).toString(), (end / 48_000.0).toString()))
    val state = mutable.asStateFlow()
    private var work: Deferred<PunchCompletion>? = null
    fun update(change: (VocalPunchState) -> VocalPunchState) {
        if (!state.value.busy && !state.value.closed) mutable.update { change(it).copy(problem = null, saved = 0) }
    }
    suspend fun record(): Boolean {
        if (state.value.busy || state.value.closed) return false
        val value = state.value
        val request = try {
            fun number(text: String) = requireNotNull(text.toDoubleOrNull()).also { require(it.isFinite()) }
            val start = number(value.startSeconds); val end = number(value.endSeconds); val manual = number(value.manualMillis)
            require(start in 0.0..86400.0 && end in 0.0..86400.0 && manual in -10000.0..10000.0)
            VocalPunchRequest((start * 48000).roundToLong(), (end * 48000).roundToLong(), value.preRollBars, value.countInBars,
                value.passes, (manual * 48).roundToLong().toInt()).also { it.plan(document.value.project) }
        } catch (_: IllegalArgumentException) { mutable.value = value.copy(problem = PunchProblem.INVALID); return false }
        val revision = document.value.revision
        mutable.value = value.copy(busy = true, stopping = false, saved = 0, problem = null)
        val pending = jobs.async(start = CoroutineStart.LAZY) {
            val completion = try { actions.record(request, revision) }
                catch (_: Exception) { PunchCompletion(VocalPunchResult(problem = PunchProblem.SAVE_FAILED), false) }
            mutable.update { it.copy(busy = false, saved = if (completion.saved) completion.result.captured?.passes?.size ?: 0 else 0,
                manualMillis = "0", problem = completion.result.problem ?: if (!completion.saved && completion.result.captured != null) PunchProblem.SAVE_FAILED else null) }
            if (state.value.closing) finishClose()
            completion
        }
        work = pending; pending.start()
        // A rotating/recreated composition may cancel its waiter, never the session-owned recording/save.
        return pending.await().saved
    }

    fun stop() { mutable.update { it.copy(stopping = true) }; actions.stop() }
    fun requestClose() {
        if (state.value.closed) return
        if (state.value.busy) { mutable.update { it.copy(closing = true, stopping = true) }; actions.stop() } else finishClose()
    }
    suspend fun closeAndJoin() { requestClose(); work?.join(); finishClose() }
    private fun finishClose() {
        mutable.update { it.copy(closed = true, closing = false) }
        val pending = work
        if (pending == null || pending.isCompleted) owner.cancel() else pending.invokeOnCompletion { owner.cancel() }
    }
}
