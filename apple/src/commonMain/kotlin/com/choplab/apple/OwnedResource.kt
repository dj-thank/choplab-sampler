package com.choplab.apple

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference

/** Install/close is one atomic ownership decision, including disposal before the first UI recomposition. */
internal class ResourceOwner<T : Any> {
    private class Value<T>(val value: T)
    private val state = AtomicReference<Any>(EMPTY)
    fun install(value: T): Boolean = state.compareAndSet(EMPTY, Value(value))
    @Suppress("UNCHECKED_CAST")
    fun close(): T? = (state.exchange(CLOSED) as? Value<T>)?.value
    private companion object { val EMPTY = Any(); val CLOSED = Any() }
}

/** A completed worker construction stays owned even when withContext discards its result on cancellation. */
internal suspend fun <T : Any> createAndPublishOwnedResource(
    create: () -> T,
    release: suspend (T) -> Unit,
    publish: (T) -> Unit,
) {
    val pending = AtomicReference<T?>(null)
    try {
        withContext(Dispatchers.Default) { pending.store(create()) }
        currentCoroutineContext().ensureActive()
        val resource = checkNotNull(pending.load())
        publish(resource)
        pending.store(null)
    } finally {
        pending.exchange(null)?.let { resource ->
            withContext(NonCancellable + Dispatchers.Default) { release(resource) }
        }
    }
}
