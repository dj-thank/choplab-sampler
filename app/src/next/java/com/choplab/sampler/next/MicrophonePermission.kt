package com.choplab.sampler.next

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets the editor's microphone port wait for the permission screen of whichever Activity is current, as
 * [DocumentPickers] does for documents. One request at a time; a newer request, a detached Activity or a closed
 * session answers "not allowed". An answer delivered to a recreated Activity (rotation) still completes the pending
 * request. State is touched only on [main].
 */
class MicrophonePermission(private val allowed: () -> Boolean, private val main: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private var launcher: (() -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private var closed = false

    /** Main thread. The current Activity hands over its permission launcher. */
    fun attach(launch: () -> Unit) { if (!closed) launcher = launch }
    fun detach(launch: () -> Unit) { if (launcher === launch) launcher = null }

    /** Main thread, from the Activity's permission callback. */
    fun complete(granted: Boolean) {
        val current = pending ?: return
        pending = null
        current.complete(granted)
    }

    /** True at once when already allowed; otherwise asks and waits for the answer. */
    suspend fun request(): Boolean {
        if (allowed()) return true
        val answer = withContext(main) {
            val launch = launcher
            if (closed || launch == null) return@withContext null
            pending?.complete(false)
            val next = CompletableDeferred<Boolean>()
            pending = next
            try { launch(); next } catch (_: RuntimeException) { pending = null; null }
        } ?: return false
        return answer.await()
    }

    /** Main thread. Releases a waiting request when the session ends. */
    fun close() {
        closed = true
        launcher = null
        pending?.complete(false)
        pending = null
    }
}
