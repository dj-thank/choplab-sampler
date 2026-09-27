package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** The diagnostics readout: what the output reports, how long blocks take, and how often output was lost. */
class OutputHealthTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No file loading in driver-only tests")
    })
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    /** A paced float device that reports its buffer, its run-outs and what it still has to play. */
    private class ReportingSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        val fail = AtomicBoolean(false)
        val runOuts = AtomicInteger(3)
        /** While set, a write waits: the owner renders one block and then holds it. */
        val hold = AtomicBoolean(false)
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!fail.get()) { "Audio output route changed" }
            while (hold.get()) LockSupport.parkNanos(1_000_000)
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() = Unit
        override fun bufferFrames() = 2048
        override fun underruns() = runOuts.get()
        override fun pendingFrames() = 700L
    }

    @Test fun healthReportsTheDeviceBlockTimesAndLosses() = runBlocking<Unit> {
        val sink = ReportingSink()
        val driver = StreamingEnginePort(compiler(), { sink })
        try {
            val starting = driver.health()
            assertEquals(0, starting.measuredBlocks)
            assertNull(starting.renderP99)
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED && driver.health().measuredBlocks >= 200 }
            sink.runOuts.set(5)
            waitUntil { driver.health().underruns == 5 }
            val health = driver.health()
            assertTrue(health.attached)
            assertEquals(SinkEncoding.FLOAT32, health.encoding)
            assertEquals(256, health.blockFrames)
            assertEquals(2048, health.bufferFrames)
            assertEquals(700L, health.pendingFrames)
            assertEquals(0L, health.outputLosses)
            val p99 = assertNotNull(health.renderP99)
            val max = assertNotNull(health.renderMax)
            assertTrue(p99.isFinite() && p99 >= 0 && max >= p99, "p99 $p99, max $max")

            sink.fail.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            val lost = driver.health()
            assertFalse(lost.attached)
            assertEquals(1L, lost.outputLosses)
            assertNull(lost.bufferFrames, "Nothing is reported about a device that is gone")
            assertNull(lost.underruns)
            assertNull(lost.pendingFrames)
            assertTrue(lost.measuredBlocks >= 200, "Measured blocks stay readable after a loss")

            // Block times describe the current device only: a reopened output starts counting again.
            sink.hold.set(true)
            sink.fail.set(false)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            delay(50)
            assertTrue(driver.health().measuredBlocks <= 1, "Counting restarts with the new device: ${driver.health()}")
            sink.hold.set(false)
        } finally { sink.hold.set(false); driver.close() }
    }

    @Test fun aDeviceThatReportsNothingLeavesThoseFieldsUnknown() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), { object : AudioSink {
            override val encoding = SinkEncoding.PCM16
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                length.also { LockSupport.parkNanos(length.toLong() / 4 * 1_000_000_000L / 48_000) }
            override fun close() = Unit
        } })
        try {
            waitUntil { driver.health().measuredBlocks >= 10 }
            val health = driver.health()
            assertEquals(SinkEncoding.PCM16, health.encoding)
            assertNull(health.bufferFrames)
            assertNull(health.underruns)
            assertNull(health.pendingFrames)
            assertNotNull(health.renderP99)
        } finally { driver.close() }
    }
}
