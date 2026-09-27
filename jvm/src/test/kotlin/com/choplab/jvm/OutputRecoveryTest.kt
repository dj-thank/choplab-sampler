package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.PcmAsset
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** The shared policy both editor hosts use to bring lost output back while visible. */
class OutputRecoveryTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No file loading in driver-only tests")
    })
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    /** Paces like a device; a switch makes it fail like an unplugged route, another keeps new ones from opening. */
    private class Device {
        val available = AtomicBoolean(true)
        val failing = AtomicBoolean(false)
        val opened = AtomicInteger()
        fun open(): AudioSink {
            check(available.get()) { "No output device" }
            opened.incrementAndGet()
            return object : AudioSink {
                override val encoding = SinkEncoding.FLOAT32
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    check(!failing.get()) { "Route changed" }
                    LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                    return length
                }
                override fun close() = Unit
            }
        }
    }

    @Test fun lostOutputComesBackWhileVisible() = runBlocking<Unit> {
        val device = Device()
        val driver = StreamingEnginePort(compiler(), device::open)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recovery = OutputRecovery(driver, scope, longArrayOf(10, 20, 40))
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            recovery.start()
            device.failing.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            device.failing.set(false)
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(2, device.opened.get())
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }

    @Test fun missingDeviceIsTriedOnlyAFewTimesUntilADeviceEvent() = runBlocking<Unit> {
        val device = Device().apply { available.set(false) }
        val attempts = AtomicInteger()
        val driver = StreamingEnginePort(compiler(), { attempts.incrementAndGet(); device.open() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recovery = OutputRecovery(driver, scope, longArrayOf(10, 20, 40))
        try {
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            recovery.start()
            delay(400)
            val bounded = attempts.get()
            assertTrue(bounded in 2..5, "Initial open plus a short schedule, not a loop: $bounded")
            delay(200)
            assertEquals(bounded, attempts.get(), "No further attempts without a new loss or device event")

            device.available.set(true)
            recovery.retry()
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }

    @Test fun missingDeviceIsTriedOnTheWholeScheduleNotRestartedByEachFailure() = runBlocking<Unit> {
        val attempts = CopyOnWriteArrayList<Long>()
        val driver = StreamingEnginePort(compiler(), { attempts += System.nanoTime(); error("No output device") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recovery = OutputRecovery(driver, scope, longArrayOf(40, 80, 160))
        try {
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            recovery.start()
            waitUntil { attempts.size == 4 }
            delay(300)
            assertEquals(4, attempts.size, "Initial open plus the three scheduled attempts")
            // Each failed attempt is the same trouble, so the waits keep growing instead of starting over.
            val gaps = (2 until attempts.size).map { (attempts[it] - attempts[it - 1]) / 1_000_000 }
            assertTrue(gaps[0] >= 60 && gaps[1] >= 130, "Attempts spread over the schedule: gaps $gaps ms")
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }

    @Test fun hiddenEditorNeitherRetriesNorUndoesARelease() = runBlocking<Unit> {
        val device = Device()
        val driver = StreamingEnginePort(compiler(), device::open)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recovery = OutputRecovery(driver, scope, longArrayOf(10, 20, 40))
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            recovery.start()
            assertTrue(driver.releaseOutput())
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            delay(150)
            assertEquals(DriverPhase.EDITING_ONLY, driver.status.value.phase, "A lifecycle release stays released")
            assertEquals(1, device.opened.get())

            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            recovery.stop()
            device.failing.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            device.failing.set(false)
            recovery.retry()
            delay(150)
            assertEquals(DriverPhase.EDITING_ONLY, driver.status.value.phase, "A stopped policy never reopens")
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }

    /** Runs the policy's coroutines only when the test says so, to control what its watcher can observe. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runPending() { while (true) (tasks.poll() ?: return).run() }
    }

    @Test fun reopeningsTheWatcherNeverSawStillCountAsQuickLosses() = runBlocking<Unit> {
        val opens = AtomicInteger()
        // Every line fails on its first write, like a device that never drains.
        val driver = StreamingEnginePort(compiler(), {
            opens.incrementAndGet()
            object : AudioSink {
                override val encoding = SinkEncoding.FLOAT32
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int = error("Output stalled")
                override fun close() = Unit
            }
        })
        val manual = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + manual)
        val recovery = OutputRecovery(driver, scope, longArrayOf(10), stableMillis = 60_000, maxQuickLosses = 3)
        try {
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            recovery.start()
            repeat(8) {
                manual.runPending()
                val before = opens.get()
                delay(40)
                manual.runPending()
                // Each reopening attaches and fails before the watcher runs again, so it sees the same failure twice.
                if (opens.get() > before) waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            }
            assertEquals(4, opens.get(), "Initial open plus three quick reopenings, then it waits for a device event")
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }

    @Test fun deviceThatFailsRightAfterOpeningStopsCyclingUntilADeviceEvent() = runBlocking<Unit> {
        val opens = AtomicInteger()
        val healthy = AtomicBoolean(false)
        // Every reopened line fails on its first write, like a device that never drains.
        val driver = StreamingEnginePort(compiler(), {
            opens.incrementAndGet()
            object : AudioSink {
                override val encoding = SinkEncoding.FLOAT32
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    check(healthy.get()) { "Output stalled" }
                    LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                    return length
                }
                override fun close() = Unit
            }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recovery = OutputRecovery(driver, scope, longArrayOf(10), stableMillis = 60_000, maxQuickLosses = 3)
        try {
            recovery.start()
            waitUntil { opens.get() >= 4 }
            delay(300)
            val settled = opens.get()
            assertTrue(settled in 4..5, "Initial open plus three quick reopenings, then it waits: $settled")
            assertEquals(DriverPhase.EDITING_ONLY, driver.status.value.phase)

            healthy.set(true)
            recovery.retry()
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(settled + 1, opens.get())
        } finally { recovery.stop(); scope.cancel(); driver.close() }
    }
}
