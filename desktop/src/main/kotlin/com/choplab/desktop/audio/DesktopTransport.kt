package com.choplab.desktop.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Single-owner 16-step transport for the Windows Java Sound adapter. */
class DesktopTransport(
    private val onStep: (barIndex: Int, stepIndex: Int) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    @Volatile private var bpm = 92f
    @Volatile private var swing = 54f
    @Volatile private var worker: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start(bpm: Float, swing: Float) {
        updateTempo(bpm, swing)
        if (!running.compareAndSet(false, true)) return
        val transportThread = Thread(::runLoop, "ChopLab-Windows-Transport").apply { isDaemon = true }
        worker = transportThread
        transportThread.start()
    }

    fun updateTempo(bpm: Float, swing: Float) {
        this.bpm = bpm.coerceIn(40f, 240f)
        this.swing = swing.coerceIn(50f, 75f)
    }

    fun stop() {
        running.set(false)
        val active = worker
        if (active != null && active !== Thread.currentThread()) {
            active.interrupt()
            runCatching { active.join(500L) }
        }
        if (worker === active) worker = null
    }

    private fun runLoop() {
        val cursor = DesktopTransportCursor()
        try {
            while (running.get()) {
                onStep(cursor.barIndex, cursor.stepIndex)
                val deadline = System.nanoTime() +
                    DesktopTransportTiming.stepDurationNanos(cursor.stepIndex, bpm, swing)
                while (running.get()) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) break
                    LockSupport.parkNanos(minOf(remaining, 5_000_000L))
                    if (Thread.interrupted() && !running.get()) return
                }
                cursor.advance()
            }
        } finally {
            running.set(false)
            if (worker === Thread.currentThread()) worker = null
        }
    }

    override fun close() = stop()
}

internal class DesktopTransportCursor {
    var barIndex: Int = 0
        private set
    var stepIndex: Int = 0
        private set

    fun reset() {
        barIndex = 0
        stepIndex = 0
    }

    fun advance() {
        if (stepIndex == 15) {
            stepIndex = 0
            barIndex = if (barIndex == Int.MAX_VALUE) 0 else barIndex + 1
        } else {
            stepIndex++
        }
    }
}

object DesktopTransportTiming {
    fun stepDurationNanos(step: Int, bpm: Float, swing: Float): Long {
        val safeBpm = bpm.coerceIn(40f, 240f)
        val safeSwing = swing.coerceIn(50f, 75f)
        val straight = 60.0 / safeBpm / 4.0
        val ratio = if (step % 2 == 0) safeSwing / 50.0 else 2.0 - safeSwing / 50.0
        return (straight * ratio * 1_000_000_000.0).toLong().coerceAtLeast(1L)
    }
}
