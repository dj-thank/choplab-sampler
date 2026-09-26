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
    private fun program() = EngineProgram(listOf(Pad(0, PcmAsset.fromInterleaved(FloatArray(8192) { if (it % 2 == 0) .3f else -.09f }),
        mode = PlayMode.LOOP, attackFrames = 0, releaseFrames = 96)), revision = 1)
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
            assertEquals(1L, driver.snapshot().programRevision)
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
        } finally { driver.close() }
    }
}
