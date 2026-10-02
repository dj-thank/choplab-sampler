package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class LiveChopOutputTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No decode in output clock tests")
    })
    private suspend fun until(test: () -> Boolean) = withTimeout(5_000) { while (!test()) delay(1) }
    private class Sink : AudioSink {
        override var encoding = SinkEncoding.FLOAT32
        @Volatile var buffer = 1024
        @Volatile var rate = 48_000
        @Volatile var epoch = 0L
        @Volatile var pending = 700L
        @Volatile var partial = false
        @Volatile var paused = false
        @Volatile var blocked = false
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            LockSupport.parkNanos(500_000)
            if (paused) { blocked = true; return 0 }
            if (partial) { partial = false; paused = true; return length / 2 }
            return length
        }
        override fun close() = Unit
        override fun bufferFrames() = buffer
        override fun pendingFrames() = pending
        override fun timingSampleRate() = rate
        override fun timingEpoch() = epoch
    }

    @Test fun partialWritesAreCountedOnceAndUnknownTimingIsNotZero() = runBlocking<Unit> {
        val sink = Sink()
        val driver = StreamingEnginePort(compiler(), { sink })
        try {
            until { driver.liveChopOutput() != null }
            sink.partial = true
            until { sink.blocked }
            val output = assertNotNull(driver.liveChopOutput())
            assertEquals((700L + 128 + MasterLimiter.LOOKAHEAD_FRAMES) * 1_000_000_000L / 48_000,
                output.estimatedDelayNanos, "Only the unwritten half-block is added to the device queue")
            val stable = assertNotNull(driver.liveChopOutput())
            assertEquals(output.route, stable.route)
            assertTrue(stable.eventNanos >= output.eventNanos)
            sink.pending = -1
            assertNull(assertNotNull(driver.liveChopOutput()).estimatedDelayNanos)
            sink.pending = Long.MAX_VALUE
            assertNull(assertNotNull(driver.liveChopOutput()).estimatedDelayNanos)
        } finally { sink.paused = false; driver.close() }
    }

    @Test fun outputSessionFormatBufferAndClockCannotReuseACorrection() = runBlocking<Unit> {
        var sink = Sink()
        val driver = StreamingEnginePort(compiler(), { sink })
        try {
            until { driver.liveChopOutput() != null }
            val first = assertNotNull(driver.liveChopOutput()).route
            sink.buffer = 2048
            assertNotEquals(first, assertNotNull(driver.liveChopOutput()).route)
            sink.buffer = 1024; sink.epoch++
            assertNotEquals(first, assertNotNull(driver.liveChopOutput()).route)
            sink.encoding = SinkEncoding.PCM16
            assertFalse(assertNotNull(driver.liveChopOutput()).route.floatOutput)
            sink.rate = 44_100
            assertNull(driver.liveChopOutput(), "The source clock cannot be interpreted through an incompatible device format")
            sink.rate = 48_000
            assertTrue(driver.releaseOutput())
            until { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertNull(driver.liveChopOutput())
            sink = Sink()
            assertTrue(driver.reattach())
            until { driver.liveChopOutput() != null }
            val reopened = assertNotNull(driver.liveChopOutput()).route
            assertNotSame(first.outputSession, reopened.outputSession)
            assertNotSame(first.engineClock, reopened.engineClock)
        } finally { sink.paused = false; driver.close() }
    }
}
