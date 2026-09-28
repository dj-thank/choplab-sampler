package com.choplab.ui.onboarding

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class QuickStartProblem { READ_SETTINGS, SAVE_SETTINGS }
data class QuickStartState(val open: Boolean = false, val loading: Boolean = true,
    val problem: QuickStartProblem? = null, val interaction: Long = 0)

/** Profile-local UI preference only. This owner never reads/writes a Project or starts playback/recording. */
class QuickStartController(scope: CoroutineScope, autoShow: Boolean,
    private val completed: suspend () -> Boolean, private val rememberCompleted: suspend () -> Unit) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val saves = Mutex()
    private val mutable = MutableStateFlow(QuickStartState())
    val state = mutable.asStateFlow()
    init { jobs.launch {
        var problem: QuickStartProblem? = null
        val seen = try { completed().also { ensureActive() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { problem = QuickStartProblem.READ_SETTINGS; false }
        mutable.update { it.copy(loading = false, open = if (it.interaction == 0L) autoShow && !seen else it.open,
            problem = if (it.interaction == 0L) problem else it.problem) }
    } }
    fun open() { if (!owner.isActive) return; mutable.update { it.copy(open = true, interaction = it.interaction + 1) } }
    /** Closing is always available, even if the preference cannot be saved. Music is independent of it. */
    fun dismiss() {
        if (!owner.isActive) return
        mutable.update { it.copy(open = false, interaction = it.interaction + 1) }
        jobs.launch { saves.withLock {
            val problem = try { rememberCompleted(); ensureActive(); null }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { QuickStartProblem.SAVE_SETTINGS }
            mutable.update { it.copy(problem = problem) }
        } }
    }
    fun close() { owner.cancel() }
}
