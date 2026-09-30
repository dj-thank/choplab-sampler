package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AcknowledgedTransportTest {
    @Test fun acknowledgedCountInAndItsCancellationSurviveAConcurrentReadoutPublication() = runBlocking<Unit> {
        val sink = GatedSink()
        val driver = StreamingEnginePort(ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = error("No PCM in a silent recording clock")
        }), { sink })
        // Studio can resume on a different Default thread, whose thread-local snapshot is still empty.
        val reader = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            await { sink.writes.get() == 1 }
            suspend fun applied(command: EngineCommand) {
                val before = sink.writes.get()
                val result = async(start = CoroutineStart.UNDISPATCHED) { driver.apply(command) }
                sink.advance.release()
                assertTrue(result.await())
                await { sink.writes.get() > before }
            }
            applied(EngineCommand.SwapProgram(0, 1, EngineProgram(emptyList(), tempo = Tempo(240_000),
                revision = 19, arrangement = Arrangement(emptyList(), 14_400_000))))
            applied(EngineCommand.Seek(0, 2, 12_345))
            applied(EngineCommand.CountInAndResume(0, 3, 2))
            val expectedCue = requireNotNull(driver.lastReceipt).appliedFrame + 96_000
            withReadoutPublishing(driver) {
                val state = withContext(reader) { driver.snapshot() }
                assertEquals(expectedCue, state.recordingStartFrame,
                    "APPLIED must expose its cue even when the live seqlock has no completed copy")
                assertEquals(12_345, state.recordingStartSequenceFrame)
                assertEquals(8, state.countInBeatsRemaining)
                assertEquals(19, state.programRevision)
                assertTrue(state.outputAttached)
                assertTrue(state.sequencePaused)
                assertFalse(state.playing)
            }
            repeat(4) {
                val before = sink.writes.get()
                sink.advance.release()
                await { sink.writes.get() > before }
            }
            val later = withContext(reader) { driver.snapshot() }
            assertTrue(later.frame > requireNotNull(driver.lastReceipt).appliedFrame + 256)
            withReadoutPublishing(driver) {
                assertEquals(later, withContext(reader) { driver.snapshot() },
                    "Once a reader saw newer coherent audio, contention cannot rewind it to an older acknowledgement")
            }
            applied(EngineCommand.Pause(0, 4))
            withReadoutPublishing(driver) {
                val state = withContext(reader) { driver.snapshot() }
                assertEquals(-1, state.recordingStartFrame, "A newer acknowledged cancellation replaces the cue")
                assertEquals(-1, state.recordingStartedFrame)
                assertEquals(0, state.countInBeatsRemaining)
            }
            applied(EngineCommand.CountInAndResume(0, 5, 1))
            driver.releaseOutput()
            sink.advance.release()
            await { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            withReadoutPublishing(driver) {
                val state = withContext(reader) { driver.snapshot() }
                assertFalse(state.outputAttached)
                assertEquals(-1, state.recordingStartFrame, "An old engine's receipt cannot arm a replacement engine")
                assertEquals(0, state.countInBeatsRemaining)
                assertFalse(state.playing)
            }
        } finally { sink.advance.release(100); driver.close(); reader.close() }
    }

    /** Hold the existing seqlock mid-publication, while the real audio owner is parked at its sink. */
    private suspend fun withReadoutPublishing(driver: StreamingEnginePort, block: suspend () -> Unit) {
        val view = StreamingEnginePort::class.java.getDeclaredField("engineView").run { isAccessible = true; get(driver) }
        val engine = view.javaClass.getDeclaredField("engine").run { isAccessible = true; get(view) as EngineCore }
        val version = LiveReadout::class.java.getDeclaredField("version").apply { isAccessible = true }
        val before = version.getLong(engine.readout)
        assertEquals(0, before and 1L)
        version.setLong(engine.readout, before + 1)
        try { block() } finally { version.setLong(engine.readout, before) }
    }

    private class GatedSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        val advance = Semaphore(0)
        val writes = AtomicInteger()
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            writes.incrementAndGet()
            check(advance.tryAcquire(10, TimeUnit.SECONDS)) { "Test did not release the output block" }
            return length
        }
        override fun close() { advance.release() }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(1) }
}
