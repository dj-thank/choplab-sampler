package com.choplab.jvm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private class InputOpeningSession(val active: () -> Boolean) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<InputOpeningSession>
}
/** Native adapters also check the owning request before launch, closing the cancel-before-launch race. */
suspend fun inputOpeningActive(): () -> Boolean = kotlinx.coroutines.currentCoroutineContext()[InputOpeningSession]?.active ?: { true }

enum class InputOpeningFailure { NONE, CANCELLED, TIMEOUT }

/** One owned deadline cancels the native open and retains its slot until the worker releases any late result. */
internal class CancellableInputOpener(private val factory: suspend () -> MicInput?, private val timeoutMillis: Long = 20_000,
    private val cancelNative: () -> Unit = {}) {
    private class Opening {
        val answer = CompletableDeferred<MicInput?>()
        val handoff = CountDownLatch(1)
        val cancellationSent = AtomicBoolean()
        val nativeCancelled = CountDownLatch(1)
        var cancelled = false
        var transferred = false
    }
    private val lock = Any()
    private var current: Opening? = null
    val busy get() = synchronized(lock) { current != null }
    @Volatile var failure = InputOpeningFailure.NONE
        private set

    suspend fun open(stillRequested: () -> Boolean = { true }): MicInput? {
        val session = synchronized(lock) {
            if (current != null || !stillRequested()) return null
            failure = InputOpeningFailure.NONE
            Opening().also { current = it }
        }
        Thread({
            var input: MicInput? = null
            try {
                if (synchronized(lock) { session.cancelled }) return@Thread
                input = runBlocking(InputOpeningSession { synchronized(lock) { !session.cancelled } && stillRequested() }) { factory() }
                session.answer.complete(input)
                session.handoff.await()
            } catch (error: Exception) { session.answer.completeExceptionally(error) }
            finally {
                val release = synchronized(lock) { !session.transferred }
                if (release) try { input?.close() } catch (_: Exception) { }
                // Keep the slot until its native cancellation has completed; it must not cancel the next helper.
                val waitForCancellation = synchronized(lock) {
                    if (session.cancellationSent.get() && session.nativeCancelled.count > 0) true
                    else { if (current === session) current = null; false }
                }
                if (waitForCancellation) {
                    session.nativeCancelled.await()
                    synchronized(lock) { if (current === session) current = null }
                }
                session.answer.complete(null)
            }
        }, "ChopLab-input-open").apply { isDaemon = true; start() }
        var transferred = false
        var answered = false
        try {
            // A wrapper distinguishes a successful null input from the deadline itself.
            val answer = withTimeoutOrNull(timeoutMillis) { listOf(session.answer.await()) }
            answered = answer != null
            if (answer == null) cancel(session, InputOpeningFailure.TIMEOUT)
            val input = answer?.firstOrNull()
            synchronized(lock) {
                if (input != null && !session.cancelled && stillRequested()) {
                    session.transferred = true; transferred = true
                    if (current === session) current = null
                }
            }
            return input.takeIf { transferred }
        } finally {
            if (!transferred && !answered) cancel(session, InputOpeningFailure.CANCELLED)
            session.handoff.countDown()
        }
    }

    private fun cancel(session: Opening, reason: InputOpeningFailure) {
        val owned = synchronized(lock) {
            if (session.transferred || current !== session) false else {
                session.cancelled = true
                if (failure == InputOpeningFailure.NONE) failure = reason
                val sendCancellation = session.cancellationSent.compareAndSet(false, true)
                session.answer.complete(null)
                sendCancellation
            }
        }
        if (owned) try { cancelNative() } catch (_: Exception) { }
            finally { session.nativeCancelled.countDown() }
        session.handoff.countDown()
    }
    fun cancel() { synchronized(lock) { current }?.let { cancel(it, InputOpeningFailure.CANCELLED) } }
}
