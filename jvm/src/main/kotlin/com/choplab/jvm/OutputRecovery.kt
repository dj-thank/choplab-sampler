package com.choplab.jvm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * While an editor is visible, reopens output lost to a route change, a missing device or an
 * acknowledgement fault: a few spaced attempts after each loss, plus [retry] for host device events or a return to
 * the editor.
 * Playback never resumes; a lost output already stopped every voice. A lifecycle release (no fault)
 * is the host's to undo. An output that fails again soon after this policy reopened it counts as the same
 * trouble; after a few such losses the policy waits for [retry] instead of cycling. Losses are counted from the
 * driver's own tally, so a loss this watcher never saw between two identical states still counts.
 */
class OutputRecovery(
    private val engine: StreamingEnginePort,
    private val scope: CoroutineScope,
    private val delaysMillis: LongArray = longArrayOf(300, 1_000, 3_000),
    private val stableMillis: Long = 10_000,
    private val maxQuickLosses: Int = 3,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private var watcher: Job? = null
    private var attempts: Job? = null
    private var seenFaults = -1L
    private var reopenedAt = -1L
    private var quickLosses = 0L

    init {
        require(delaysMillis.isNotEmpty() && delaysMillis.all { it in 1..60_000 })
        require(stableMillis > 0 && maxQuickLosses >= 1)
    }

    fun start() {
        synchronized(lock) {
            if (watcher != null) return
            watcher = scope.launch {
                engine.status.collect { status ->
                    synchronized(lock) {
                        if (status.phase == DriverPhase.ATTACHED) { attempts?.cancel(); attempts = null }
                        // A loss already present when watching starts is one fresh trouble, however many preceded it.
                        val losses = if (seenFaults < 0) (if (lost(status)) 1L else 0L) else status.faults - seenFaults
                        seenFaults = status.faults
                        if (losses > 0 && lost(status)) scheduleAfterLoss(losses)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            watcher?.cancel(); watcher = null
            attempts?.cancel(); attempts = null
        }
    }

    /**
     * A device appeared or went away (headphones, USB, Bluetooth), or the user came back to the editor: a fresh
     * situation, try once more now. Does nothing unless output is lost to a fault.
     */
    fun retry() {
        synchronized(lock) {
            quickLosses = 0
            if (watcher == null || !lost(engine.status.value)) return
            reopenedAt = clock()
        }
        engine.reattach()
    }

    private fun scheduleAfterLoss(losses: Long) {
        val quick = reopenedAt >= 0 && clock() - reopenedAt < stableMillis
        quickLosses = if (quick) (quickLosses + losses).coerceAtMost(Int.MAX_VALUE.toLong()) else 1
        attempts?.cancel()
        attempts = if (quickLosses > maxQuickLosses) null else scope.launch {
            for (wait in delaysMillis) {
                delay(wait)
                synchronized(lock) { reopenedAt = clock() }
                engine.reattach()
            }
        }
    }

    private fun lost(status: DriverStatus) = status.phase == DriverPhase.EDITING_ONLY && status.fault != DriverFault.NONE
}
