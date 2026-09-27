package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** Output lost to a route change or a missing device comes back without rebuilding the editor. */
class StreamingOutputRecoveryTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No file loading in driver-only tests")
    })
    private fun program(revision: Long = 1) = EngineProgram(listOf(Pad(0, PcmAsset.fromInterleaved(FloatArray(8192) { if (it % 2 == 0) .3f else -.09f }),
        mode = PlayMode.LOOP, attackFrames = 0, releaseFrames = 96)), revision = revision)
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    /** Float sink that paces like a device and can be made to fail, like an unplugged route. */
    private class Sink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        val fail = AtomicBoolean(false)
        @Volatile var closed = false
        @Volatile var energy = 0.0
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            check(!fail.get()) { "Audio output route changed" }
            var sum = energy
            for (at in offset until offset + length step 8) {
                val left = Float.fromBits((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                    ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24))
                sum += left.toDouble() * left
            }
            energy = sum
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() { closed = true }
    }

    @Test fun lostOutputReattachesToAFreshSinkWithTheConfirmedProgram() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertFalse(driver.reattach(), "An attached output needs no reattach")
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            waitUntil { sinks[0].energy > 1 }

            sinks[0].fail.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.WRITE_FAILED, driver.status.value.fault)
            assertTrue(sinks[0].closed)
            assertEquals(0, driver.snapshot().activeVoices)
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 3, 0)), "Nothing may sound while detached")
            assertEquals(1, sinks.size, "Output never reopens by itself")

            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(2, sinks.size)
            assertEquals(1L, driver.snapshot().programRevision, "The confirmed Program survives the new sink")
            assertTrue(driver.snapshot().outputAttached)
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 4, 0)))
            waitUntil { sinks[1].energy > 1 }
        } finally { driver.close() }
        assertTrue(sinks.all { it.closed })
        assertFalse(driver.reattach(), "A closed driver never reopens output")
    }

    @Test fun missingDeviceCanBeRetriedUntilItAppears() = runBlocking<Unit> {
        val available = AtomicBoolean(false)
        val attempts = AtomicInteger()
        val driver = StreamingEnginePort(compiler(), {
            attempts.incrementAndGet()
            check(available.get()) { "No output device" }
            Sink()
        })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.NO_OUTPUT, driver.status.value.fault)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())), "Edits stay usable")

            assertTrue(driver.reattach())
            waitUntil { attempts.get() == 2 }
            delay(30)
            assertEquals(DriverPhase.EDITING_ONLY, driver.status.value.phase)
            assertEquals(DriverFault.NO_OUTPUT, driver.status.value.fault)

            available.set(true)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(3, attempts.get())
            // The readout shows the carried-over program once the new line renders its first block.
            waitUntil { driver.snapshot().programRevision == 1L }
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
        } finally { driver.close() }
    }

    @Test fun lossesAreCountedOncePerTroubleNotPerFailedRetry() = runBlocking<Unit> {
        val available = AtomicBoolean(true)
        val sinks = CopyOnWriteArrayList<Sink>()
        val attempts = AtomicInteger()
        val driver = StreamingEnginePort(compiler(), {
            attempts.incrementAndGet()
            check(available.get()) { "No output device" }
            Sink().also { sinks += it }
        })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(0L, driver.status.value.faults)
            assertTrue(driver.releaseOutput())
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverStatus(DriverPhase.EDITING_ONLY), driver.status.value, "Releasing is not a loss")

            // Wanted back, but the device is gone: a new trouble.
            available.set(false)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.fault == DriverFault.NO_OUTPUT }
            assertEquals(1L, driver.status.value.faults)
            // Trying again while it is still gone is the same trouble.
            assertTrue(driver.reattach())
            waitUntil { attempts.get() == 3 }
            delay(30)
            assertEquals(DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.NO_OUTPUT, faults = 1), driver.status.value)

            available.set(true)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(1L, driver.status.value.faults)
            // The reopened device failing is a new trouble.
            sinks.last().fail.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.WRITE_FAILED, faults = 2), driver.status.value)
        } finally { driver.close() }
        assertEquals(DriverStatus(DriverPhase.CLOSED, faults = 2), driver.status.value)
    }

    @Test fun releasedOutputHoldsNoDeviceUntilTheEditorReturns() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            waitUntil { sinks[0].energy > 1 }

            assertTrue(driver.releaseOutput())
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.NONE, driver.status.value.fault, "Releasing for the background is not a fault")
            assertTrue(sinks[0].closed)
            assertEquals(0, driver.snapshot().activeVoices)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 3, program())), "Edits stay usable")
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 4, 0)))
            delay(50)
            assertEquals(1, sinks.size, "Nothing reopens while the editor is hidden")

            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(2, sinks.size)
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 5, 0)))
            waitUntil { sinks[1].energy > 1 }
        } finally { driver.close() }
    }

    /** Paces like a device until told to stall (writes nothing) or freeze (a write that does not return). */
    private class TroubleSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        val stalled = AtomicBoolean(false)
        val frozenMillis = AtomicInteger(0)
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            if (stalled.get()) return 0
            // Unlike a park, a sleeping write is not woken early when a command arrives, like a stuck device call.
            frozenMillis.getAndSet(0).takeIf { it > 0 }?.let { Thread.sleep(it.toLong()) }
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() = Unit
    }

    @Test fun documentChangesSurviveADeviceThatStallsWhileTheyAreApplied() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<TroubleSink>()
        val driver = StreamingEnginePort(compiler(), { TroubleSink().also { sinks += it } })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            sinks[0].stalled.set(true)
            // The edit arrives while the owner waits on the stalled device. It gives up after its stall deadline and
            // rebuilds a silent engine, dropping what was queued for the old one; the Program must still arrive.
            delay(100)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())), "A stalled device must not lose an edit")
            assertEquals(1L, driver.snapshot().programRevision)
            assertEquals(DriverPhase.EDITING_ONLY, driver.status.value.phase)
            assertEquals(DriverFault.WRITE_FAILED, driver.status.value.fault)
            assertTrue(driver.apply(EngineCommand.Release(driver.snapshot().frame, 2, 0)), "Stopping a PAD needs no device")
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 3, 0)), "Nothing may claim to sound without a device")
        } finally { driver.close() }
    }

    @Test fun documentChangesSurviveAWriteThatOutlivesTheAcknowledgementDeadline() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<TroubleSink>()
        val driver = StreamingEnginePort(compiler(), { TroubleSink().also { sinks += it } }, acknowledgementMillis = 200)
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            sinks[0].frozenMillis.set(600)
            delay(20)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())), "A frozen write must not lose an edit")
            assertEquals(1L, driver.snapshot().programRevision)
            if (driver.status.value.phase != DriverPhase.ATTACHED) assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(1L, driver.snapshot().programRevision, "The output plays the edited Program")
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
        } finally { driver.close() }
    }

    @Test fun editsAreAcknowledgedWhileASlowDeviceIsStillOpening() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<TroubleSink>()
        val slowOpens = AtomicBoolean(true)
        // Like an emulator's audio server: creating the output takes longer than an edit may wait.
        val driver = StreamingEnginePort(compiler(), {
            if (slowOpens.get()) Thread.sleep(1_500)
            TroubleSink().also { sinks += it }
        })
        try {
            waitUntil { driver.diagnostics().openingDevice }
            val looping = driver.diagnostics().loops
            val started = System.nanoTime()
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())), "An edit while the first device opens")
            assertTrue(System.nanoTime() - started < 1_000_000_000L, "The edit did not wait for the device")
            assertEquals(1L, driver.snapshot().programRevision)
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)), "Nothing sounds before a device is attached")
            assertTrue(driver.diagnostics().let { it.loops > looping && it.openingDevice && it.queued == 0 && it.inFlight == 0 }, "${driver.diagnostics()}")
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            waitUntil { !driver.diagnostics().openingDevice }
            assertEquals(1L, driver.snapshot().programRevision, "The opened device plays the edited Program")

            sinks[0].stalled.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertTrue(driver.reattach())
            val again = System.nanoTime()
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 3, program(revision = 2))), "An edit while a device reopens")
            assertTrue(System.nanoTime() - again < 1_000_000_000L, "The edit did not wait for the reopening")
            assertEquals(2L, driver.snapshot().programRevision)
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(2, sinks.size)
            assertEquals(2L, driver.snapshot().programRevision, "The reopened device plays the latest Program")
        } finally { driver.close() }
    }

    @Test fun aFaultWhileADeviceOpensKeepsOutputClosedUntilReattached() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val firstOpen = java.util.concurrent.CountDownLatch(1)
        val driver = StreamingEnginePort(compiler(), {
            if (sinks.isEmpty()) firstOpen.await()
            Sink().also { sinks += it }
        })
        try {
            // An edit bound far ahead stays in flight; abandoning it resets the engine with a fault meanwhile.
            val abandoned = launch(Dispatchers.Default) {
                driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame + EngineFormat.SAMPLE_RATE * 3_600L, 1, program()))
            }
            delay(100)
            abandoned.cancelAndJoin()
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.ACK_CANCELLED, driver.status.value.fault)

            // The device finishes opening after that fault: like any faulted output it waits for a reattach.
            firstOpen.countDown()
            waitUntil { sinks.size == 1 && sinks[0].closed }
            delay(30)
            assertEquals(DriverStatus(DriverPhase.EDITING_ONLY, fault = DriverFault.ACK_CANCELLED, faults = 1), driver.status.value)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 2, program())), "Edits stay usable")

            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(2, sinks.size)
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 3, 0)))
            waitUntil { sinks[1].energy > 1 }
        } finally { firstOpen.countDown(); driver.close() }
    }

    @Test fun quickReturnAfterReleaseEndsAttached() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            repeat(20) {
                assertTrue(driver.releaseOutput())
                assertTrue(driver.reattach())
            }
            delay(100)
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(1, sinks.count { !it.closed }, "Exactly one device stays open")
        } finally { driver.close() }
    }
}
