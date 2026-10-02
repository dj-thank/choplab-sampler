package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** Reconnection preserves listening levels in the actual stereo output, without resuming voices. */
class StreamingMonitorGainRecoveryTest {
    private class WriteGate(val matches: () -> Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }
    private class Sink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        val fail = AtomicBoolean()
        val blocks = CopyOnWriteArrayList<FloatArray>()
        val gate = AtomicReference<WriteGate?>()
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!fail.get()) { "Injected route loss" }
            blocks += FloatArray(length / 4) { index ->
                val at = offset + index * 4
                Float.fromBits((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                    ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24))
            }
            gate.get()?.let { waiting ->
                if (waiting.matches() && gate.compareAndSet(waiting, null)) {
                    waiting.entered.complete(Unit)
                    check(waiting.release.await(15, TimeUnit.SECONDS)) { "Test write gate timed out" }
                }
            }
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() = Unit
    }

    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("Synthetic PCM is supplied directly")
    })
    private fun pcm(left: Float, right: Float) =
        PcmAsset.fromInterleaved(FloatArray(8192) { if (it % 2 == 0) left else right })
    private fun program() = EngineProgram(listOf(Pad(0, pcm(.08f, -.035f), mode = PlayMode.LOOP,
        attackFrames = 0, loopCrossfadeFrames = 0)), revision = 7)
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(2) }
    private suspend fun stereo(sink: Sink, left: Float, right: Float) {
        val first = sink.blocks.size
        waitUntil { sink.blocks.size >= first + 8 }
        // Ignore finite voice/limiter startup tails; inspect every sample in four settled stereo blocks.
        for (block in sink.blocks.takeLast(4)) for (index in block.indices) {
            assertEquals(if (index % 2 == 0) left else right, block[index], .000002f, "channel=${index % 2}")
        }
    }

    @Test fun releaseAndDeviceFailurePreserveIndependentStereoLevelsAndStaySilentUntilPlayed() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } }, blockFrames = 64)
        val original = pcm(.12f, -.025f)
        var studioOrder = 0L
        var monitorOrder = 0L
        suspend fun monitor(factory: (Long, Long) -> EngineCommand) =
            driver.applyMonitoring(factory(driver.snapshot().frame, ++monitorOrder))
        suspend fun playOriginal() {
            assertTrue(monitor { frame, order -> EngineCommand.SetOriginalSource(frame, order, OriginalSource(original, loop = true)) })
            assertTrue(monitor { frame, order -> EngineCommand.PlayOriginalSource(frame, order) })
        }
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, ++studioOrder, program())))
            assertTrue(monitor { frame, order -> EngineCommand.SetOriginalMonitorGain(frame, order, .25f) })
            assertTrue(monitor { frame, order -> EngineCommand.SetSongMonitorGain(frame, order, .5f) })
            assertTrue(monitor { frame, order -> EngineCommand.SetHandMonitorGain(frame, order, .125f) })
            playOriginal()
            stereo(sinks.last(), .12f * .25f, -.025f * .25f)
            assertTrue(monitor { frame, order -> EngineCommand.PauseOriginalSource(frame, order) })
            stereo(sinks.last(), 0f, 0f)
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, ++studioOrder, 0)))
            stereo(sinks.last(), .08f * .5f, -.035f * .5f)

            for (fault in listOf(false, true)) {
                if (fault) sinks.last().fail.set(true) else assertTrue(driver.releaseOutput())
                waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
                assertEquals(if (fault) DriverFault.WRITE_FAILED else DriverFault.NONE, driver.status.value.fault)
                assertEquals(7L, driver.snapshot().programRevision)
                assertEquals(0, driver.snapshot().activeVoices)
                assertFalse(driver.originalPlayback().playing)
                // Offline monitoring remains refused, so these targets must never become future preferences.
                assertFalse(monitor { frame, order -> EngineCommand.SetOriginalMonitorGain(frame, order, 1f) })
                assertFalse(monitor { frame, order -> EngineCommand.SetSongMonitorGain(frame, order, 1f) })
                assertTrue(driver.reattach())
                waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
                val sink = sinks.last()
                stereo(sink, 0f, 0f)
                assertTrue(sink.blocks.all { block -> block.all { it == 0f } }, "Reconnection must never resume a voice")
                assertEquals(.125f, driver.handPlayback().gain)
                assertEquals(.25f, driver.originalPlayback().gain)
                playOriginal()
                stereo(sink, .12f * .25f, -.025f * .25f)
                assertTrue(monitor { frame, order -> EngineCommand.PauseOriginalSource(frame, order) })
                stereo(sink, 0f, 0f)
                // Song playback does not call SourceAuditionController; the driver must restore this bus too.
                assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, ++studioOrder, 0)))
                stereo(sink, .08f * .5f, -.035f * .5f)
                assertEquals(7L, driver.snapshot().programRevision, "Listening preferences never edit the Program")
            }
        } finally { driver.close() }
    }

    @Test fun cancelledFutureGainCannotUnmuteEitherBusAfterReconnection() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } }, blockFrames = 64)
        val original = pcm(.12f, -.025f)
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())))
            assertTrue(driver.applyMonitoring(EngineCommand.SetOriginalMonitorGain(driver.snapshot().frame, 1, 0f)))
            assertTrue(driver.applyMonitoring(EngineCommand.SetSongMonitorGain(driver.snapshot().frame, 2, 0f)))
            waitUntil { driver.diagnostics().let { it.inFlight == 0 && it.queued == 0 } }
            val future = launch(Dispatchers.Default) {
                driver.applyMonitoring(EngineCommand.SetOriginalMonitorGain(driver.snapshot().frame + 48_000L * 3600, 3, 1f))
            }
            waitUntil { driver.diagnostics().inFlight == 1 }
            future.cancelAndJoin()
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.ACK_CANCELLED, driver.status.value.fault)
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            val sink = sinks.last()
            assertTrue(driver.applyMonitoring(EngineCommand.SetOriginalSource(driver.snapshot().frame, 4, OriginalSource(original, loop = true))))
            assertTrue(driver.applyMonitoring(EngineCommand.PlayOriginalSource(driver.snapshot().frame, 5)))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            stereo(sink, 0f, 0f)
            assertTrue(sink.blocks.all { block -> block.all { it == 0f } }, "Muted SOURCE and song cannot leak even a first block")
            assertTrue(driver.originalPlayback().playing, "The SOURCE really runs behind its mute")
            assertTrue(driver.snapshot().activeVoices > 0, "The song PAD really runs behind its mute")
            assertEquals(7L, driver.snapshot().programRevision)
        } finally { driver.close() }
    }

    @Test fun resetDuringAcknowledgedGainRampRestoresTheTargetRatherThanItsIntermediateLevel() = runBlocking<Unit> {
        val sinks = CopyOnWriteArrayList<Sink>()
        val driver = StreamingEnginePort(compiler(), { Sink().also { sinks += it } }, blockFrames = 64)
        val original = pcm(.12f, -.025f)
        val gate = WriteGate { driver.originalPlayback().gain.let { it > 0f && it < 1f } }
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.applyMonitoring(EngineCommand.SetOriginalSource(driver.snapshot().frame, 1, OriginalSource(original, loop = true))))
            assertTrue(driver.applyMonitoring(EngineCommand.PlayOriginalSource(driver.snapshot().frame, 2)))
            stereo(sinks.last(), .12f, -.025f)
            sinks.last().gate.set(gate)
            val mute = async { driver.applyMonitoring(EngineCommand.SetOriginalMonitorGain(driver.snapshot().frame, 3, 0f)) }
            withTimeout(5_000) { gate.entered.await() }
            assertTrue(mute.await(), "The mute target was acknowledged before the blocked write")
            assertTrue(driver.originalPlayback().gain.let { it > 0f && it < 1f }, "The 96-frame ramp is still partway through its first 64-frame block")
            assertTrue(driver.releaseOutput())
            gate.release.countDown()
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(0f, driver.originalPlayback().gain, "Recovery must retain target zero, not the partial ramp")
            assertTrue(driver.reattach())
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            val sink = sinks.last()
            assertTrue(driver.applyMonitoring(EngineCommand.SetOriginalSource(driver.snapshot().frame, 4, OriginalSource(original, loop = true))))
            assertTrue(driver.applyMonitoring(EngineCommand.PlayOriginalSource(driver.snapshot().frame, 5)))
            stereo(sink, 0f, 0f)
            assertTrue(sink.blocks.all { block -> block.all { it == 0f } }, "No intermediate gain may leak after explicit replay")
            assertTrue(driver.originalPlayback().playing)
        } finally { gate.release.countDown(); driver.close() }
    }
}
