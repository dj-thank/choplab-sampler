package com.choplab.desktop.audio.wasapi

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.Pad
import com.choplab.engine.PcmAsset
import com.choplab.engine.PlayMode
import com.choplab.jvm.DriverFault
import com.choplab.jvm.DriverPhase
import com.choplab.jvm.FileAssetStore
import com.choplab.jvm.StreamingEnginePort
import com.choplab.jvm.VoiceRecorder
import com.choplab.jvm.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual production ports and files; only the Windows boundary is scripted. No real device opens here. */
class WasapiProductionPortsTest {
    @Test
    fun theContinuousEngineUsesTheNativeSinkAndDeviceLossStopsVoicesWithoutImplicitFallback() = runBlocking {
        val budget = WasapiStreamsTest.Budget()
        val native = WasapiStreamsTest.FakeStream(480)
        val opens = AtomicInteger()
        val streams = WasapiStreams(budget, WasapiNativeApi { opens.incrementAndGet(); native }, WasapiSlots())
        val compiler = ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = error("No files in the synthetic output fixture")
        })
        val driver = StreamingEnginePort(compiler, {
            runBlocking { assertIs<WasapiOpen.Ready<WasapiAudioSink>>(streams.openOutput()).stream }
        }, blockFrames = 192)
        val events = launch(Dispatchers.Default) {
            while (isActive) { native.signalOutput(480); delay(10) }
        }
        try {
            await { driver.status.value.phase == DriverPhase.ATTACHED }
            val pcm = PcmAsset.fromInterleaved(FloatArray(8_192) { if (it % 2 == 0) .3f else -.09f })
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1,
                EngineProgram(listOf(Pad(0, pcm, mode = PlayMode.LOOP, attackFrames = 0, releaseFrames = 96)), revision = 7))))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            await { native.rendered.any { block -> block.indices.any { it % 2 == 0 && block[it] > .1f && block[it + 1] < 0f } } }
            assertEquals(7, driver.snapshot().programRevision)
            native.eventFailure = 0x88890004.toInt()
            await { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.WRITE_FAILED, driver.status.value.fault)
            assertEquals(0, driver.snapshot().activeVoices)
            assertEquals(1, opens.get())
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 3, 0)))
            await { budget.retained == 0L }
        } finally { events.cancelAndJoin(); driver.close(); streams.close() }
        assertEquals(1, native.closes.get())
    }

    @Test
    fun microphoneAndLoopbackRetainTheSameStereoFloatTakeOnDeviceLossAndReleaseTheirNativeOwners() = runBlocking {
        val temporary = Files.createTempDirectory("choplab-wasapi-take-")
        try {
            for (mode in listOf(WasapiStreamMode.MICROPHONE, WasapiStreamMode.LOOPBACK)) {
                val budget = WasapiStreamsTest.Budget()
                val native = WasapiStreamsTest.FakeStream(480)
                val streams = WasapiStreams(budget, WasapiNativeApi { native }, WasapiSlots())
                val input = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(mode)).stream
                val scratch = temporary.resolve("scratch-${mode.name}")
                val store = FileAssetStore(temporary.resolve("assets-${mode.name}"))
                val recorder = VoiceRecorder(input, scratch, maxSeconds = 1)
                val expected = FloatArray(9_600) { i -> if (i % 2 == 0) (i / 2 % 67) / 40f else -(i / 2 % 61) / 50f }
                try {
                    repeat(10) { packet ->
                        native.enqueue(WasapiStreamsTest.Packet(expected.copyOfRange(packet * 960, (packet + 1) * 960),
                            packet * 480L, packet * 100_000L + 5_000_000L))
                    }
                    await { recorder.recordedMillis == 100L }
                    native.eventFailure = 0x88890026.toInt()
                    await { recorder.interrupted && native.closes.get() == 1 }
                    val take = assertNotNull(recorder.finish(store, "Synthetic stereo"))
                    assertEquals(4_800, take.asset.frames)
                    assertEquals(48_000, take.asset.sampleRate)
                    assertEquals(2, take.asset.channels)
                    val audio = store.openVerified(take.asset).use(WavCodec::read)
                    assertContentEquals(expected, audio.samples)
                    assertEquals(WasapiFault.DEVICE_LOST, input.status().failure!!.fault)
                    assertEquals(0, budget.retained)
                    assertEquals(0, Files.list(scratch).use { it.count() })
                } finally { recorder.discard(); streams.close() }
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test
    fun startFailureAndLargestAcceptedNativeBufferKeepBoundedAdmissionAndCompleteCleanup() = runBlocking {
        val budget = WasapiStreamsTest.Budget()
        val failing = WasapiStreamsTest.FakeStream(480).apply { startFailure = 0x80070005.toInt() }
        val streams = WasapiStreams(budget, WasapiNativeApi { failing }, WasapiSlots())
        val failure = assertIs<WasapiOpen.Unavailable>(streams.openInput(WasapiStreamMode.MICROPHONE))
        assertEquals(WasapiStage.START, failure.failure.stage)
        assertEquals(WasapiFault.ACCESS_DENIED, failure.failure.fault)
        assertTrue(failure.release.await())
        assertEquals(0, budget.retained)
        assertEquals(1, failing.closes.get())
        streams.close()

        val large = WasapiStreamsTest.FakeStream(48_000)
        val capture = WasapiStreams(budget, WasapiNativeApi { large }, WasapiSlots())
        val input = assertIs<WasapiOpen.Ready<WasapiMicInput>>(capture.openInput(WasapiStreamMode.MICROPHONE)).stream
        try {
            assertEquals(3L * 48_000 * 8, budget.retained)
            val packet = FloatArray(96_000) { if (it % 2 == 0) .25f else -.5f }
            large.enqueue(WasapiStreamsTest.Packet(packet, 0, 0))
            await { input.status().captureClock?.deliveredFrames == 48_000L }
            val read = FloatArray(packet.size)
            assertEquals(packet.size, input.read(read))
            assertContentEquals(packet, read)
        } finally { input.close(); capture.close() }
        assertEquals(0, budget.retained)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(1) }
}
