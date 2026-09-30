package com.choplab.desktop.audio.wasapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasapiStreamsTest {
    @Test
    fun outputBackpressureAndSplitWritesKeepEveryFrameChannelAndHeadroomWithoutNativeCallsOnTheProducer() = runBlocking {
        val budget = Budget()
        val fake = FakeStream(480)
        val streams = streams(budget) { fake }
        val sink = assertIs<WasapiOpen.Ready<WasapiAudioSink>>(streams.openOutput()).stream
        assertEquals(1, fake.rendered.size) // one native startup silence block, before Start
        assertTrue(fake.rendered.first().all { it == 0f })
        val source = FloatArray(8_321 * 2) { i -> if (i % 2 == 0) (i / 2 % 31 + 1) / 16f else -(i / 2 % 29 + 1) / 15f }
        val bytes = bytes(source)
        val chunks = intArrayOf(1, 17, 96, 192, 480, 4096)
        var frame = 0
        var chunk = 0
        val actual = mutableListOf<Float>()
        while (frame < source.size / 2) {
            val want = minOf(chunks[chunk++ % chunks.size], source.size / 2 - frame)
            var pending = want
            while (pending > 0) {
                val accepted = sink.write(bytes, frame * 8, pending * 8) / 8
                frame += accepted
                pending -= accepted
                if (accepted == 0) {
                    assertEquals(WASAPI_OUTPUT_RING_FRAMES, sink.status().queuedFrames)
                    val rendered = fake.outputEvent(480)
                    actual += rendered.toList()
                }
            }
        }
        while (sink.status().queuedFrames > 0) {
            val queued = sink.status().queuedFrames
            val n = minOf(queued, 480)
            actual += fake.outputEvent(n).toList()
        }
        assertContentEquals(source, actual.toFloatArray())
        assertEquals(0, sink.status().softwareStarvationFrames)
        assertEquals(-1, sink.underruns()) // no claim of a measured hardware counter
        assertEquals(480, sink.bufferFrames())
        assertTrue(sink.pendingFrames() in 0..480)
        assertTrue(sink.closeWithin(1_000))
        streams.close()
        assertEquals(0, budget.retained)
        assertEquals(1, fake.closes.get())
        assertEquals(1, fake.nativeThreads.size)
        assertFalse(Thread.currentThread().id in fake.nativeThreads)
    }

    @Test
    fun inputReadsExactStereoAcrossPacketAndConsumerBoundariesAndPublishesQpcInItsOwnDomain() = runBlocking {
        for (mode in listOf(WasapiStreamMode.MICROPHONE, WasapiStreamMode.LOOPBACK)) {
            val budget = Budget()
            val fake = FakeStream(480)
            val streams = streams(budget) { fake }
            val input = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(mode)).stream
            assertEquals(48_000, input.sampleRate)
            assertEquals(2, input.channels)
            assertEquals(480, input.bufferFrames)
            val expected = FloatArray(1_337 * 2) { if (it % 2 == 0) it / 1024f else -it / 2048f }
            var at = 0
            var chunk = 0
            val chunks = intArrayOf(1, 17, 96, 192, 480)
            while (at < expected.size / 2) {
                val count = minOf(chunks[chunk++ % chunks.size], expected.size / 2 - at)
                fake.enqueue(Packet(expected.copyOfRange(at * 2, (at + count) * 2), 10_000L + at,
                    123_456_789L + at * 10_000_000L / 48_000, if (at == 0) 1 else 0))
                at += count
            }
            await { input.status().captureClock?.deliveredFrames == 1_337L }
            val actual = FloatArray(expected.size)
            val buffer = FloatArray(34)
            var sample = 0
            while (sample < actual.size) {
                val count = input.read(buffer)
                assertTrue(count > 0 && count % 2 == 0)
                buffer.copyInto(actual, sample, 0, count)
                sample += count
            }
            assertContentEquals(expected, actual)
            assertTrue(input.status().initialDiscontinuity)
            assertEquals(1_337L, input.status().captureClock!!.deliveredFrames)
            assertTrue(input.status().captureClock!!.packetQpc100ns in 123_456_789L..124_456_789L)
            input.stop()
            assertEquals(-1, input.read(buffer))
            assertTrue(input.closeWithin(1_000))
            streams.close()
            assertEquals(0, budget.retained)
            assertEquals(1, fake.closes.get())
        }
    }

    @Test
    fun lateOpenAfterTimeoutNeverStartsAndCannotBeReplacedUntilNativeFinallyReleasesTheBudget() = runBlocking {
        val budget = Budget()
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val fake = FakeStream(480)
        val count = AtomicInteger()
        val slots = WasapiSlots()
        val native = WasapiNativeApi {
            if (count.incrementAndGet() == 1) { entered.countDown(); leave.await() }
            fake
        }
        val streams = WasapiStreams(budget, native, slots)
        try {
            val open = async { streams.openOutput(80) }
            withTimeout(1_000) { while (entered.count > 0) delay(1) }
            val timedOut = assertIs<WasapiOpen.Unavailable>(open.await())
            assertEquals(WasapiFault.OPEN_TIMEOUT, timedOut.failure.fault)
            assertFalse(timedOut.mayChooseFallback)
            assertTrue(budget.retained > 0)
            val secondFacade = WasapiStreams(budget, native, slots)
            val busy = assertIs<WasapiOpen.Unavailable>(secondFacade.openOutput())
            assertEquals(WasapiFault.BUSY, busy.failure.fault)
            assertFalse(busy.mayChooseFallback)
            assertEquals(1, count.get())
            leave.countDown()
            assertTrue(timedOut.release.await())
            assertTrue(timedOut.mayChooseFallback)
            assertEquals(0, fake.starts.get())
            assertEquals(1, fake.closes.get())
            assertEquals(0, budget.retained)
            secondFacade.close()
        } finally { leave.countDown(); streams.close() }
    }

    @Test
    fun cancellationDuringNativeOpenAndDuringMemoryAdmissionCannotPublishALateStreamOrLeakAdmission() = runBlocking {
        val budget = Budget()
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val fake = FakeStream(480)
        val streams = streams(budget) { entered.countDown(); leave.await(); fake }
        try {
            val opening = async { streams.openOutput() }
            await { entered.count == 0L }
            opening.cancelAndJoin()
            assertTrue(budget.retained > 0)
            leave.countDown()
            await { fake.closes.get() == 1 && budget.retained == 0L }
            assertEquals(0, fake.starts.get())
        } finally { leave.countDown(); streams.close() }

        val reserving = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val nativeCalls = AtomicInteger()
        val waiting = WasapiStreams(WasapiPcmMemory {
            reserving.complete(Unit)
            try { blocked.await(); error("not admitted") } finally { cancelled.complete(Unit) }
        }, WasapiNativeApi { nativeCalls.incrementAndGet(); FakeStream(480) }, WasapiSlots())
        val opening = async { waiting.openOutput() }
        reserving.await()
        opening.cancelAndJoin()
        withTimeout(1_000) { cancelled.await() }
        waiting.close()
        assertEquals(0, nativeCalls.get())
    }

    @Test
    fun stopUnblocksTheConsumerWhileANoncooperativeNativeWaitRetainsItsSlotAndAllReservations() = runBlocking {
        val budget = Budget()
        val waitEntered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val fake = FakeStream(480).apply { blockedWait = { waitEntered.countDown(); unblock.await() } }
        val streams = streams(budget) { fake }
        val input = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(WasapiStreamMode.MICROPHONE)).stream
        try {
            await { waitEntered.count == 0L }
            val read = async(Dispatchers.IO) { input.read(FloatArray(96)) }
            input.stop()
            assertEquals(-1, withTimeout(1_000) { read.await() })
            assertFalse(input.closeWithin(30))
            assertTrue(budget.retained > 0)
            assertEquals(0, fake.closes.get())
            val busy = assertIs<WasapiOpen.Unavailable>(streams.openInput(WasapiStreamMode.MICROPHONE))
            assertEquals(WasapiFault.BUSY, busy.failure.fault)
            assertFalse(busy.mayChooseFallback)
            unblock.countDown()
            assertTrue(busy.release.await())
            assertEquals(0, budget.retained)
            assertEquals(1, fake.closes.get())
            assertTrue(input.closeWithin(30))
        } finally { unblock.countDown(); streams.close() }
    }

    @Test
    fun nativeCloseCannotFreeMemoryOrPermitAReplacementUntilItActuallyReturns() = runBlocking {
        val budget = Budget()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val fake = FakeStream(480).apply { blockedClose = { entered.countDown(); unblock.await() } }
        val streams = streams(budget) { fake }
        val sink = assertIs<WasapiOpen.Ready<WasapiAudioSink>>(streams.openOutput()).stream
        try {
            val release = sink.requestClose()
            await { entered.count == 0L }
            assertFalse(release.complete)
            assertFalse(sink.closeWithin(20))
            assertTrue(budget.retained > 0)
            assertEquals(WasapiFault.BUSY, assertIs<WasapiOpen.Unavailable>(streams.openOutput()).failure.fault)
            unblock.countDown()
            assertTrue(release.await())
            assertEquals(0, budget.retained)
            assertEquals(1, fake.closes.get())
        } finally { unblock.countDown(); streams.close() }
    }

    @Test
    fun failuresExposeTypedReasonsAndRequireAnExplicitFallbackAfterCleanup() = runBlocking {
        val cases = mapOf(
            0x80070490.toInt() to WasapiFault.NO_ENDPOINT,
            0x80070005.toInt() to WasapiFault.ACCESS_DENIED,
            0x88890008.toInt() to WasapiFault.UNSUPPORTED_FORMAT,
            0x88890004.toInt() to WasapiFault.DEVICE_LOST,
            0x88890026.toInt() to WasapiFault.DEVICE_LOST,
            0x8889000a.toInt() to WasapiFault.DEVICE_BUSY,
            0x88890010.toInt() to WasapiFault.SERVICE_UNAVAILABLE,
            0x80010106.toInt() to WasapiFault.NATIVE_FAILURE,
        )
        for ((hr, expected) in cases) {
            val budget = Budget()
            val calls = AtomicInteger()
            val streams = streams(budget) { calls.incrementAndGet(); throw WasapiException("fixture", hr) }
            val refused = assertIs<WasapiOpen.Unavailable>(streams.openOutput())
            assertEquals(expected, refused.failure.fault)
            assertEquals(hr, refused.failure.hresult)
            assertTrue(refused.release.await())
            assertTrue(refused.mayChooseFallback)
            assertEquals(1, calls.get()) // no hidden retry or opening some other endpoint
            streams.close()
            assertEquals(0, budget.retained)
        }
        val budget = Budget(32_768)
        val nativeCalls = AtomicInteger()
        val streams = streams(budget) { nativeCalls.incrementAndGet(); FakeStream(480) }
        val refused = assertIs<WasapiOpen.Unavailable>(streams.openOutput())
        assertEquals(WasapiFault.MEMORY_LIMIT, refused.failure.fault)
        assertTrue(refused.release.await())
        assertEquals(0, nativeCalls.get())
        assertEquals(0, budget.retained)
        streams.close()
    }

    @Test
    fun invalidNativeClockMissingFramesNonFiniteAndOverrunEndInputInsteadOfCompressingTime() = runBlocking {
        for (case in listOf("flag", "gap", "clock", "backwards", "nan", "overrun")) {
            val budget = Budget()
            val fake = FakeStream(480)
            val streams = streams(budget) { fake }
            val input = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(WasapiStreamMode.LOOPBACK)).stream
            fake.enqueue(Packet(FloatArray(960) { .5f }, 0, 10_000))
            await { input.status().captureClock?.deliveredFrames == 480L }
            assertEquals(960, input.read(FloatArray(960))) // preserve the already delivered prefix
            when (case) {
                "flag" -> fake.enqueue(Packet(FloatArray(960), 480, 20_000, 1))
                "gap" -> fake.enqueue(Packet(FloatArray(960), 481, 20_000))
                "clock" -> fake.enqueue(Packet(FloatArray(960), 480, 20_000, 4))
                "backwards" -> fake.enqueue(Packet(FloatArray(960), 480, 9_999))
                "nan" -> fake.enqueue(Packet(FloatArray(960) { Float.NaN }, 480, 20_000))
                "overrun" -> repeat(51) { fake.enqueue(Packet(FloatArray(960), 480L * (it + 1), 20_000L + it * 10_000)) }
            }
            await { input.status().phase == WasapiStreamPhase.CLOSED }
            assertEquals(when (case) {
                "flag", "gap" -> WasapiFault.INPUT_DISCONTINUITY
                "clock", "backwards" -> WasapiFault.INPUT_TIMESTAMP_ERROR
                "nan" -> WasapiFault.NON_FINITE_PCM
                else -> WasapiFault.INPUT_OVERRUN
            }, input.status().failure!!.fault)
            assertEquals(0, budget.retained)
            assertEquals(1, fake.closes.get())
            streams.close()
        }
    }

    private fun streams(budget: WasapiPcmMemory, open: () -> FakeStream) = WasapiStreams(budget, WasapiNativeApi { open() }, WasapiSlots())

    internal class Budget(private val limit: Long = 128L * 1024 * 1024) : WasapiPcmMemory {
        @Volatile var retained = 0L
            private set
        @Volatile var peak = 0L
            private set
        override suspend fun reserve(bytes: Long): WasapiPcmReservation = synchronized(this) {
            check(bytes <= limit - retained)
            retained += bytes
            peak = maxOf(peak, retained)
            object : WasapiPcmReservation {
                var held = bytes
                override fun shrinkTo(bytes: Long) = synchronized(this@Budget) {
                    require(bytes in 0..held)
                    retained -= held - bytes
                    held = bytes
                }
                override fun close() = shrinkTo(0)
            }
        }
    }

    internal data class Packet(val stereo: FloatArray, val first: Long, val qpc: Long, val flags: Int = 0)
    internal class FakeStream(override val bufferFrames: Int) : WasapiNativeStream {
        override val mixFormat = WaveFormat(3, 2, 48_000, 384_000, 8, 32, 0)
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val rendered = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
        val nativeThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        private val events = LinkedBlockingQueue<Int>()
        private val packets = LinkedBlockingQueue<Packet>()
        private val output = LinkedBlockingQueue<FloatArray>()
        private var padding = bufferFrames
        var blockedWait: (() -> Unit)? = null
        var blockedClose: (() -> Unit)? = null
        var startFailure: Int? = null
        @Volatile var eventFailure: Int? = null
        private fun owned() { nativeThreads += Thread.currentThread().id }
        override fun start() {
            owned()
            startFailure?.let { throw WasapiException("Start", it) }
            starts.incrementAndGet()
        }
        override fun awaitEvent(timeoutMillis: Int): Boolean {
            owned()
            blockedWait?.invoke()
            eventFailure?.let { throw WasapiException("Wait", it) }
            val available = events.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return false
            padding = bufferFrames - available
            return true
        }
        override fun paddingFrames(): Int { owned(); return padding }
        override fun render(stereo: FloatArray, frames: Int) {
            owned()
            val block = stereo.copyOf(frames * 2)
            rendered += block
            if (starts.get() > 0) output.put(block)
        }
        suspend fun outputEvent(available: Int): FloatArray {
            events.put(available)
            return withTimeout(2_000) {
                var result = output.poll()
                while (result == null) { delay(1); result = output.poll() }
                result
            }
        }
        fun signalOutput(available: Int) { events.put(available) }
        fun enqueue(packet: Packet) { packets.put(packet); events.put(0) }
        override fun capture(stereo: FloatArray, packet: WasapiPacket): Int {
            owned()
            val next = packets.poll() ?: return 0
            next.stereo.copyInto(stereo)
            packet.flags = next.flags
            packet.firstFrame = next.first
            packet.qpc100ns = next.qpc
            return next.stereo.size / 2
        }
        override fun close() { owned(); blockedClose?.invoke(); closes.incrementAndGet() }
    }

    private companion object {
        fun bytes(samples: FloatArray): ByteArray = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach(::putFloat)
        }.array()
        suspend fun await(condition: () -> Boolean) = withTimeout(2_000) { while (!condition()) delay(1) }
    }
}
