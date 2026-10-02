package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.*

/** The original is heard at the song key even when the output once refused a key change. */
class SourceAuditionControllerTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No PAD loading in this test")
    })
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }
    private val paced = { object : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            length.also { LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000) }
        override fun close() = Unit
    } }

    @Test fun rebuiltOutputCannotReuseThePreviousSessionsLoadedSourceDuringReadoutContention() = runBlocking<Unit> {
        val f = SessionFixture(PcmMemoryBudget.shared.statistics().usedBytes)
        try {
            waitUntil { f.driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(f.audition.pitch(12f))
            assertTrue(f.audition.play(f.asset))
            // Seed this consumer thread with the old route's loaded=true snapshot.
            assertTrue(f.driver.originalPlayback().loaded)
            f.output.lost.set(true)
            waitUntil { f.driver.status.value.phase == DriverPhase.EDITING_ONLY }
            f.output.hold.set(true)
            f.output.lost.set(false)
            assertTrue(f.driver.reattach())
            waitUntil { f.output.parked.get() && f.driver.status.value.phase == DriverPhase.ATTACHED }
            f.observe.set(true)
            val result = withReadoutPublishing(f.driver) {
                // UNDISPATCHED retains the same thread-local old snapshot until the first real command.
                val pending = async(start = CoroutineStart.UNDISPATCHED) { f.audition.play(f.asset) }
                withTimeout(5_000) { f.firstCommand.await() }
                pending
            }
            f.output.resume()
            assertTrue(result.await(), "A fresh output must receive SOURCE and its key before Play: ${f.commands}")
            assertEquals(listOf("SetOriginalSource", "SetOriginalPitch", "PlayOriginalSource"), f.commands.toList())
            assertEquals(2, f.loads.get())
            assertTrue(f.driver.originalPlayback().playing)
        } finally { f.close() }
        assertEquals(f.beforeBudget, PcmMemoryBudget.shared.statistics().usedBytes)
    }

    @Test fun sameOutputResumesItsAcknowledgedSourceWithoutReloadingOnAContendedFreshReader() = runBlocking<Unit> {
        val f = SessionFixture(PcmMemoryBudget.shared.statistics().usedBytes)
        val reader = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            waitUntil { f.driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(f.audition.pitch(12f))
            assertTrue(f.audition.play(f.asset))
            waitUntil { f.driver.originalPlayback().sourceFrame >= 4_800 }
            assertTrue(f.audition.pause())
            f.output.hold.set(true)
            waitUntil { f.output.parked.get() }
            val position = f.driver.originalPlayback().sourceFrame
            assertTrue(position >= 4_800)
            f.observe.set(true)
            val result = withReadoutPublishing(f.driver) {
                // A new consumer has no snapshot; contention must not turn an acknowledged source into empty.
                val pending = async(reader) { f.audition.play(f.asset) }
                withTimeout(5_000) { f.firstCommand.await() }
                pending
            }
            f.output.resume()
            assertTrue(result.await())
            assertEquals(listOf("PlayOriginalSource"), f.commands.toList(), "Resume must not reset a loaded source to frame zero")
            assertEquals(1, f.loads.get())
            assertTrue(f.driver.originalPlayback().sourceFrame >= position)
        } finally { f.close(); reader.close() }
        assertEquals(f.beforeBudget, PcmMemoryBudget.shared.statistics().usedBytes)
    }

    /** Force the existing three-attempt seqlock to be busy only while the real audio owner is parked. */
    private suspend fun <T> withReadoutPublishing(driver: StreamingEnginePort, block: suspend () -> T): T {
        val view = StreamingEnginePort::class.java.getDeclaredField("engineView").run { isAccessible = true; get(driver) }
        val engine = view.javaClass.getDeclaredField("engine").run { isAccessible = true; get(view) as EngineCore }
        val version = LiveReadout::class.java.getDeclaredField("version").apply { isAccessible = true }
        val before = version.getLong(engine.readout)
        assertEquals(0L, before and 1L)
        version.setLong(engine.readout, before + 1)
        return try { block() } finally { version.setLong(engine.readout, before) }
    }

    private inner class SessionFixture(val beforeBudget: Long) : AutoCloseable {
        val output = ParkedOutput()
        val driver = StreamingEnginePort(compiler(), output::sink)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loads = AtomicInteger()
        val observe = AtomicBoolean()
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstCommand = CompletableDeferred<Unit>()
        val asset = Asset("c".repeat(64), "wav", 44, 48_000, 2, 480_000, "source")
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset {
                loads.incrementAndGet()
                return PcmAsset.fromInterleaved(FloatArray(480_000 * 2) { if (it % 2 == 0) .1f else -.2f })
            }
        }, scope, send = { command ->
            if (observe.get()) { commands += command.javaClass.simpleName; firstCommand.complete(Unit) }
            driver.applyMonitoring(command)
        })
        override fun close() { output.resume(); audition.close(); scope.cancel(); driver.close() }
    }

    private class ParkedOutput {
        val lost = AtomicBoolean()
        val hold = AtomicBoolean()
        val parked = AtomicBoolean()
        private val release = Semaphore(0)
        fun resume() { hold.set(false); release.release() }
        fun sink() = object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(!lost.get()) { "Injected output loss" }
                if (hold.get()) {
                    parked.set(true)
                    check(release.tryAcquire(5, TimeUnit.SECONDS)) { "Test did not release its audio owner" }
                    parked.set(false)
                }
                LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                return length
            }
            override fun close() = Unit
        }
    }

    @Test fun releaseCancelsLateDecodeAndAlsoAStartWaitingForGainAcknowledgement() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loading = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val gainEntered = CompletableDeferred<Unit>()
        val releaseGain = CompletableDeferred<Unit>()
        val delayGain = AtomicBoolean(false)
        val starts = AtomicInteger()
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = withContext(NonCancellable) {
                loading.complete(Unit); releaseLoad.await()
                PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 10) { .1f })
            }
        }, scope, send = { command ->
            if (command is EngineCommand.SetHandMonitorGain && delayGain.get()) {
                gainEntered.complete(Unit); releaseGain.await()
            }
            if (command is EngineCommand.ScratchOriginalStart) starts.incrementAndGet()
            driver.applyMonitoring(command)
        })
        val asset = Asset("a".repeat(64), "wav", 44, 48_000, 2, 480_000, "source")
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            val pending = async { runCatching { audition.scratchStart(asset, 96_000, 48_000, 192_000) } }
            withTimeout(5_000) { loading.await() }
            assertTrue(audition.scratchEnd())
            releaseLoad.complete(Unit)
            assertFalse(pending.await().getOrDefault(false))
            assertFalse(driver.originalPlayback().loaded)
            assertEquals(-1.0, audition.nativeHandFrame())
            assertEquals(0, starts.get(), "A late decode must not begin a released gesture")

            assertTrue(audition.play(asset))
            delayGain.set(true)
            val next = async { audition.scratchStart(asset, 96_000, 48_000, 192_000) }
            withTimeout(5_000) { gainEntered.await() }
            // Cancellation increments the generation immediately, before waiting for the control mutex.
            val end = async(start = CoroutineStart.UNDISPATCHED) { audition.scratchEnd() }
            releaseGain.complete(Unit)
            assertFalse(next.await())
            assertTrue(end.await())
            assertEquals(0, starts.get(), "Letting go during an acknowledgement cannot enqueue a later start")
            assertEquals(-1.0, audition.nativeHandFrame())
            assertTrue(driver.originalPlayback().playing)
        } finally {
            releaseLoad.complete(Unit); releaseGain.complete(Unit)
            audition.close(); scope.cancel(); driver.close()
        }
    }

    @Test fun handReusesLoadedPcmRetriesItsGainAndRestartsSilentAfterOutputLossOrRelease() = runBlocking<Unit> {
        val lost = AtomicBoolean(false)
        val refuseGain = AtomicBoolean(false)
        val loads = AtomicInteger()
        val driver = StreamingEnginePort(compiler(), { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(!lost.get()) { "Injected output loss" }
                LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                return length
            }
            override fun close() = Unit
        } })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset {
                loads.incrementAndGet()
                return PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 10) { .1f })
            }
        }, scope, send = { command ->
            if (command is EngineCommand.SetHandMonitorGain && refuseGain.get()) false else driver.applyMonitoring(command)
        })
        val asset = Asset("b".repeat(64), "wav", 44, 48_000, 2, 480_000, "source")
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            refuseGain.set(true)
            assertTrue(audition.play(asset), "SOURCE playback must not depend on HAND accepting its gain")
            assertFalse(audition.handGain(.25f))
            refuseGain.set(false)
            assertTrue(audition.scratchStart(asset, 96_000, 48_000, 192_000))
            waitUntil { driver.handPlayback().gain == .25f }
            assertTrue(audition.scratchTo(150_000.0, 48_000))
            assertTrue(driver.originalPlayback().playing)
            assertEquals(1, loads.get(), "HAND shares already loaded SOURCE PCM")
            lost.set(true)
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.WRITE_FAILED, driver.status.value.fault)
            assertEquals(-1.0, audition.nativeHandFrame())
            assertEquals(0, driver.snapshot().activeVoices)
            assertFalse(driver.originalPlayback().loaded)
            lost.set(false)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(-1.0, audition.nativeHandFrame())
            assertFalse(driver.originalPlayback().playing)
            assertTrue(audition.scratchStart(asset, 96_000, 48_000, 192_000))
            waitUntil { driver.handPlayback().gain == .25f }
            assertEquals(2, loads.get(), "A rebuilt engine needs its bounded SOURCE lease again")
            assertFalse(driver.originalPlayback().playing, "Restarting HAND alone cannot start SOURCE")
            assertTrue(driver.releaseOutput())
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(-1.0, audition.nativeHandFrame())
            assertEquals(0, driver.snapshot().activeVoices)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(-1.0, audition.nativeHandFrame())
            assertFalse(driver.originalPlayback().playing)
        } finally { audition.close(); scope.cancel(); driver.close() }
    }

    @Test fun aNativeRangeWhichNormalizesToNoOutputFramesIsRefusedBeforeLoading() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = error("An empty normalized range must not load PCM")
        }, scope)
        try {
            val asset = Asset("f".repeat(64), "wav", 44, 96_000, 2, 96_000, "source")
            assertFalse(audition.scratchStart(asset, 1, 1, 2))
            assertEquals(-1.0, audition.nativeHandFrame())
        } finally { audition.close(); scope.cancel(); driver.close() }
    }

    @Test fun aKeyTheOutputRefusedIsSentAgainBeforeTheOriginalPlaysOn() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refuseKeys = AtomicBoolean(false)
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 30) { .1f })
        }, scope, send = { command ->
            if (refuseKeys.get() && command is EngineCommand.SetOriginalPitch) false else driver.applyMonitoring(command)
        })
        val song = Asset("d".repeat(64), "wav", 44, 48_000, 2, 48_000L * 30, "song")
        /** Source frames the original moves per output frame over a short stretch of playback. */
        suspend fun rate(): Double {
            val source = driver.originalPlayback().sourceFrame
            val output = driver.snapshot().frame
            delay(200)
            return (driver.originalPlayback().sourceFrame - source).toDouble() / (driver.snapshot().frame - output)
        }
        suspend fun rateBecomes(expected: Double) = withTimeout(5_000) { while (abs(rate() - expected) > expected * .05) Unit }
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(audition.play(song))
            rateBecomes(1.0)

            // Refused while the original stays loaded, e.g. with the command queue full.
            refuseKeys.set(true)
            assertFalse(audition.pitch(12f))
            rateBecomes(1.0)
            refuseKeys.set(false)
            assertTrue(audition.pause())
            assertTrue(audition.play(song), "Playing on sends the refused key first")
            rateBecomes(2.0)
        } finally { audition.close(); scope.cancel(); driver.close() }
    }

    @Test fun sourceKeepsPlayingWhileHandUsesIndependentNativeFrames() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // A 44.1 kHz original, normalized to 48 kHz by its loader as usual.
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 10) { .1f })
        }, scope)
        val song = Asset("e".repeat(64), "wav", 44, 44_100, 2, 44_100L * 10, "song")
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertFalse(audition.scratchTo(1_000.0, 480), "Nothing is held before a scratch starts")
            assertTrue(audition.play(song))
            waitUntil { driver.originalPlayback().playing }
            // HAND starts at 2 s in the native 44.1 kHz coordinates; SOURCE continues.
            assertTrue(audition.scratchStart(song, 88_200, 44_100, 176_400))
            assertTrue(driver.originalPlayback().playing)
            assertEquals(2.0, driver.handPlayback().sourceFrame / 48_000.0, .01)
            assertEquals(88_200.0, audition.nativeHandFrame(), 1.0)
            // Moved half a second ahead over 0.25 s, then pulled far past the range end: it stops at 4 s.
            assertTrue(audition.scratchTo(110_250.0, 12_000))
            waitUntil { abs(driver.handPlayback().sourceFrame / 48_000.0 - 2.5) < .01 }
            assertTrue(audition.scratchTo(176_000.0, 48_000))
            waitUntil { abs(driver.handPlayback().sourceFrame / 48_000.0 - 4.0) < .01 }
            assertTrue(audition.scratchCut(0f))
            assertTrue(audition.scratchEnd())
            // HAND cannot seek or restart SOURCE when released.
            assertTrue(driver.originalPlayback().playing)
            assertTrue(driver.originalPlayback().sourceFrame < 3 * 48_000)
            assertEquals(-1.0, audition.nativeHandFrame())
            assertTrue(audition.pause())
            waitUntil { !driver.originalPlayback().playing }
            // Taken while paused, it stays paused when let go.
            assertTrue(audition.scratchStart(song, 88_200, 44_100, 176_400))
            assertTrue(audition.scratchEnd())
            delay(100)
            assertFalse(driver.originalPlayback().playing, "Taken while paused, it stays paused")
        } finally { audition.close(); scope.cancel(); driver.close() }
    }
}
