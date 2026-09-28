package com.choplab.desktop.audio.wasapi

import com.choplab.jvm.PcmMemoryBudget
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedWasapiStreamsTest {
    @Test
    fun aProductionPcmReservationCanRefuseNativeAdmissionWithoutEvictingOrLosingItsOwner() = runBlocking {
        val ledger = PcmMemoryBudget(800_000)
        val otherWorker = ledger.reserve(450_000)
        val nativeOpens = AtomicInteger()
        val streams = WasapiStreams(SharedWasapiPcmMemory(ledger), WasapiNativeApi {
            nativeOpens.incrementAndGet(); WasapiStreamsTest.FakeStream(480)
        }, WasapiSlots())
        try {
            val refused = assertIs<WasapiOpen.Unavailable>(streams.openOutput())
            assertEquals(WasapiFault.MEMORY_LIMIT, refused.failure.fault)
            assertTrue(refused.release.await())
            assertEquals(0, nativeOpens.get())
            assertEquals(450_000, ledger.statistics().usedBytes)
            otherWorker.close()
            val sink = assertIs<WasapiOpen.Ready<WasapiAudioSink>>(streams.openOutput()).stream
            assertEquals(40_448, ledger.statistics().usedBytes) // output ring + actual endpoint + scratch
            sink.close()
            assertEquals(0, ledger.statistics().usedBytes)
            assertTrue(ledger.statistics().peakBytes <= ledger.limitBytes)
        } finally { otherWorker.close(); streams.close() }
    }

    @Test
    fun allThreeModesShareTheActualLedgerAndAStuckNativeCloseKeepsItsCharge() = runBlocking {
        val ledger = PcmMemoryBudget()
        val leave = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val streams = WasapiStreams(SharedWasapiPcmMemory(ledger), WasapiNativeApi { mode ->
            WasapiStreamsTest.FakeStream(480).apply {
                if (mode == WasapiStreamMode.LOOPBACK) blockedClose = { entered.countDown(); leave.await() }
            }
        }, WasapiSlots())
        try {
            val output = assertIs<WasapiOpen.Ready<WasapiAudioSink>>(streams.openOutput()).stream
            val mic = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(WasapiStreamMode.MICROPHONE)).stream
            val loopback = assertIs<WasapiOpen.Ready<WasapiMicInput>>(streams.openInput(WasapiStreamMode.LOOPBACK)).stream
            assertEquals(439_808, ledger.statistics().usedBytes)
            output.close()
            mic.close()
            val release = loopback.requestClose()
            withTimeout(1_000) { while (entered.count > 0) delay(1) }
            assertFalse(loopback.closeWithin(20))
            assertFalse(release.complete)
            assertEquals(199_680, ledger.statistics().usedBytes)
            leave.countDown()
            assertTrue(release.await())
            assertEquals(0, ledger.statistics().usedBytes)
            assertTrue(ledger.statistics().peakBytes <= ledger.limitBytes)
        } finally { leave.countDown(); streams.close() }
        // Public default construction has no native side effect on any OS.
        createWasapiStreams().close()
    }
}
