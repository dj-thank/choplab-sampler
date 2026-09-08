package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single writer whose accepted saves survive an Activity/ViewModel teardown. */
class ProjectAutosaveWriter(
    private val save: (SamplerUiState, Long) -> Unit,
    private val onFailure: (Long, Exception) -> Unit,
    private val delayMillis: Long = 900L,
) : AutoCloseable {
    private data class Pending(val state: SamplerUiState, val revision: Long)
    private var pending: Pending? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false

    @Synchronized
    fun submit(state: SamplerUiState, revision: Long) {
        check(!closed) { "Autosave writer is closed" }
        pending = Pending(state, revision)
        schedule(delayMillis)
    }

    @Synchronized
    fun flush() {
        if (!closed && pending != null) schedule(0L)
    }

    @Synchronized
    fun <T> readAfterPending(read: () -> T): java.util.concurrent.Future<T> {
        check(!closed)
        flush()
        return executor.submit(java.util.concurrent.Callable { read() })
    }

    private fun schedule(delay: Long) {
        scheduled?.cancel(false)
        val request = pending ?: return
        scheduled = executor.schedule({
            val accepted = synchronized(this) {
                if (pending !== request) false else {
                    pending = null
                    true
                }
            }
            if (accepted) {
                try { save(request.state, request.revision) }
                catch (error: Exception) { onFailure(request.revision, error) }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        flush()
        closed = true
        // No UI-thread wait; already accepted writes drain in order.
    }

    private companion object {
        // Process-owned queue orders final saves before recovery in a recreated ViewModel.
        val executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "choplab-autosave").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
