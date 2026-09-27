package com.choplab.sampler.next

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The four system document screens the editor asks for. */
enum class PickerKind { AUDIO, PROJECT, SAVE_PROJECT, EXPORT_WAV }

/**
 * Lets suspend editor ports wait for a system document screen owned by whichever Activity is current.
 * One request at a time; a newer request, a detached Activity or a closed session answers null.
 * Results delivered to a recreated Activity (rotation) still complete the pending request.
 * State is touched only on [main]; [R] is the picked document (a content Uri on Android).
 */
class DocumentPickers<R : Any>(private val main: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private class Pending<R>(val kind: PickerKind, val answer: CompletableDeferred<R?> = CompletableDeferred())
    private var launcher: ((PickerKind, String?) -> Unit)? = null
    private var pending: Pending<R>? = null
    private var closed = false

    /** Main thread. The current Activity hands over its result launchers. */
    fun attach(launch: (PickerKind, String?) -> Unit) { if (!closed) launcher = launch }
    fun detach(launch: (PickerKind, String?) -> Unit) { if (launcher === launch) launcher = null }

    /** Main thread, from the Activity's result callbacks. */
    fun complete(kind: PickerKind, result: R?) {
        val current = pending ?: return
        if (current.kind != kind) return
        pending = null
        current.answer.complete(result)
    }

    suspend fun pick(kind: PickerKind, suggestedName: String? = null): R? {
        val request = withContext(main) {
            val launch = launcher
            if (closed || launch == null) return@withContext null
            pending?.answer?.complete(null)
            val next = Pending<R>(kind)
            pending = next
            // A device without a documents provider has no screen to show; answer "nothing picked".
            try { launch(kind, suggestedName); next } catch (_: RuntimeException) { pending = null; null }
        } ?: return null
        return request.answer.await()
    }

    /** Main thread. Releases a waiting request when the session ends. */
    fun close() {
        closed = true
        launcher = null
        pending?.answer?.complete(null)
        pending = null
    }
}
