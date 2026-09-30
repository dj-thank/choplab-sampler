package com.choplab.desktop.audio.wasapi

import com.choplab.desktop.next.NextWindowsAudio
import com.choplab.desktop.next.NextAudioRoute
import com.choplab.desktop.next.NextBackend
import com.choplab.jvm.AudioSink
import com.choplab.jvm.DriverPhase
import com.choplab.jvm.SinkEncoding
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.jvm.VoiceTakes
import com.choplab.jvm.WavCodec
import com.choplab.ui.SystemAudioCapture
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real NEXT backend/voice/file routes; only the native endpoint boundary is scripted. */
class NextWindowsAudioHostTest {
    @Test fun productionHostOpensNoInputsUntilAnActionAndKeepsVocalMonoAndSystemStereo() = runBlocking {
        val directory = Files.createTempDirectory("next-windows-host-")
        val native = ConcurrentHashMap<WasapiStreamMode, WasapiStreamsTest.FakeStream>()
        val streams = WasapiStreams(SharedWasapiPcmMemory(), WasapiNativeApi { mode ->
            WasapiStreamsTest.FakeStream(480).also { native[mode] = it }
        }, WasapiSlots())
        val javaOpens = AtomicInteger()
        val audio = NextWindowsAudio(streams, javaOutput = { javaOpens.incrementAndGet(); SilentSink() })
        val backend = NextBackend.createWithRoutes(directory, windows = audio)
        val output = launch(Dispatchers.Default) { while (isActive) { native[WasapiStreamMode.OUTPUT]?.signalOutput(480); delay(10) } }
        try {
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertEquals(setOf(WasapiStreamMode.OUTPUT), native.keys)
            assertNotNull(backend.systemAudio)
            assertEquals(VoiceTakes.Start.STARTED, backend.voice.start(1))
            val mic = native.getValue(WasapiStreamMode.MICROPHONE)
            mic.enqueue(WasapiStreamsTest.Packet(FloatArray(960) { if (it % 2 == 0) .4f else -.2f }, 0, 1_000_000))
            await { backend.voice.recordedMillis >= 10 }
            assertFalse(audio.select(NextAudioRoute.JAVA_SOUND))
            assertFalse(backend.chooseAudioRoute(NextAudioRoute.JAVA_SOUND))
            assertEquals(DriverPhase.ATTACHED, backend.engine.status.value.phase)
            val take = assertNotNull(backend.voice.stop("Synthetic voice"))
            assertEquals(1, take.asset.channels)
            val mono = backend.assets.openVerified(take.asset).use(WavCodec::read)
            assertTrue(mono.samples.all { it == .1f })
            val capture = assertNotNull(backend.systemAudio)
            assertEquals(SystemAudioCapture.Start.STARTED, capture.start(1))
            val stereo = FloatArray(960) { if (it % 2 == 0) .3f else -.7f }
            native.getValue(WasapiStreamMode.LOOPBACK).enqueue(WasapiStreamsTest.Packet(stereo, 0, 2_000_000))
            await { capture.recordedMillis >= 10 }
            val asset = assertNotNull(capture.stop("Synthetic mix"))
            assertEquals(2, asset.channels)
            assertContentEquals(stereo, backend.assets.openVerified(asset).use(WavCodec::read).samples)
            assertTrue(backend.chooseAudioRoute(NextAudioRoute.JAVA_SOUND))
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED && javaOpens.get() == 1 }
            assertEquals(NextAudioRoute.JAVA_SOUND, audio.route.value)
            assertEquals(0, backend.studio.document.value.revision)
        } finally { output.cancelAndJoin(); backend.shutdown(flush = false); directory.toFile().deleteRecursively() }
        assertTrue(native.values.all { it.closes.get() == 1 })
    }

    @Test fun anUnreleasedNativeOwnerRefusesTheExplicitAlternateRouteWithoutOpeningIt() = runBlocking {
        val leave = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val native = WasapiStreamsTest.FakeStream(480).apply { blockedClose = { entered.countDown(); leave.await() } }
        val streams = WasapiStreams(SharedWasapiPcmMemory(), WasapiNativeApi { native }, WasapiSlots())
        val javaOpens = AtomicInteger()
        val audio = NextWindowsAudio(streams, javaOutput = { javaOpens.incrementAndGet(); SilentSink() })
        try {
            val sink = assertIs<WasapiAudioSink>(audio.openOutput())
            sink.requestClose()
            await { entered.count == 0L }
            assertFalse(audio.select(NextAudioRoute.JAVA_SOUND))
            assertEquals(NextAudioRoute.WASAPI, audio.route.value)
            assertEquals(0, javaOpens.get())
            leave.countDown()
            assertTrue(audio.select(NextAudioRoute.JAVA_SOUND))
            audio.openOutput().close()
            assertEquals(1, javaOpens.get())
        } finally { leave.countDown(); audio.close() }
    }

    @Test fun monoAdapterAdmissionCanRefuseCaptureBeforeNativeInputOpens() = runBlocking {
        val ledger = PcmMemoryBudget(16_383)
        val opens = AtomicInteger()
        val streams = WasapiStreams(SharedWasapiPcmMemory(ledger), WasapiNativeApi { opens.incrementAndGet(); WasapiStreamsTest.FakeStream(480) }, WasapiSlots())
        val audio = NextWindowsAudio(streams, memory = ledger)
        try {
            assertFails { audio.openMicrophone() }
            assertEquals(0, opens.get())
            assertEquals(0, ledger.statistics().usedBytes)
        } finally { audio.close() }
    }

    @Test fun systemCaptureReportsNativeRefusalAndNeverFallsBackToMicrophone() = runBlocking {
        for ((hresult, expected) in listOf(0x80070005.toInt() to SystemAudioCapture.Start.DENIED,
            0x80070490.toInt() to SystemAudioCapture.Start.UNAVAILABLE)) {
            val directory = Files.createTempDirectory("next-loopback-refusal-")
            val modes = mutableListOf<WasapiStreamMode>()
            val streams = WasapiStreams(SharedWasapiPcmMemory(), WasapiNativeApi { mode ->
                synchronized(modes) { modes += mode }
                throw WasapiException("Synthetic open", hresult)
            }, WasapiSlots())
            val audio = NextWindowsAudio(streams)
            val assets = com.choplab.jvm.FileAssetStore(directory.resolve("assets"))
            val capture = com.choplab.desktop.next.NextWasapiSystemAudioCapture(assets, directory.resolve("scratch"), audio)
            try {
                assertEquals(expected, capture.start(1))
                assertEquals(listOf(WasapiStreamMode.LOOPBACK), modes)
                assertNull(capture.stop("Refused"))
                assertEquals(NextAudioRoute.WASAPI, audio.route.value)
            } finally { capture.close(); audio.close(); directory.toFile().deleteRecursively() }
        }
    }

    private class SilentSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int { java.util.concurrent.locks.LockSupport.parkNanos(5_000_000); return length }
        override fun close() {}
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
}
