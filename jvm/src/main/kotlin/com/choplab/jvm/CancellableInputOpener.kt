package com.choplab.jvm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch

/** One native open may outlive cancellation, but never holds the editor's action queue or starts a second worker. */
internal class CancellableInputOpener(private val factory: suspend () -> MicInput?, private val timeoutMillis: Long = 20_000) {
    private class Opening {
        val answer = CompletableDeferred<MicInput?>()
        val handoff = CountDownLatch(1)
        var cancelled = false
        var transferred = false
    }
    private val lock = Any()
    private var current: Opening? = null
    val busy get() = synchronized(lock) { current != null }

    suspend fun open(stillRequested: () -> Boolean = { true }): MicInput? {
        val session = synchronized(lock) {
            if (current != null || !stillRequested()) return null
            Opening().also { current = it }
        }
        Thread({
            var input: MicInput? = null
            try {
                if (synchronized(lock) { session.cancelled }) return@Thread
                input = runBlocking { factory() }
                session.answer.complete(input)
                session.handoff.await()
            } catch (_: Exception) { session.answer.complete(null) }
            finally {
                val release = synchronized(lock) { !session.transferred }
                if (release) try { input?.close() } catch (_: Exception) { }
                synchronized(lock) { if (current === session) current = null }
                session.answer.complete(null)
            }
        }, "ChopLab-input-open").apply { isDaemon = true; start() }
        var transferred = false
        try {
            val input = withTimeoutOrNull(timeoutMillis) { session.answer.await() }
            synchronized(lock) {
                if (input != null && !session.cancelled) { session.transferred = true; transferred = true; if (current === session) current = null }
            }
            return input.takeIf { transferred }
        } finally {
            synchronized(lock) { if (!transferred) session.cancelled = true }
            session.handoff.countDown()
        }
    }

    fun cancel() = synchronized(lock) {
        current?.let { it.cancelled = true; it.answer.complete(null); it.handoff.countDown() }
    }
}
