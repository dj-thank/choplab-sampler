package com.choplab.ui

import com.choplab.core.DocumentState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Deterministically retain a notification while callers can adopt the flow's newer current value. */
internal class CreationRevisionNotifications {
    val captured = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val delivered = CompletableDeferred<Unit>()
    @OptIn(InternalCoroutinesApi::class)
    fun observe(current: StateFlow<DocumentState>): StateFlow<DocumentState> = object : StateFlow<DocumentState> by current {
        override suspend fun collect(collector: FlowCollector<DocumentState>): Nothing = current.collect { value ->
            if (!captured.isCompleted) {
                captured.complete(Unit); release.await(); collector.emit(value); delivered.complete(Unit)
            } else collector.emit(value)
        }
    }
}
