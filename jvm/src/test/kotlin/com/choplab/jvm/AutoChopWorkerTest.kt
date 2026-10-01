package com.choplab.jvm

import com.choplab.core.Location
import com.choplab.core.chop.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.*

class AutoChopWorkerTest {
    @Test fun cancelledWindowRetainsItsSharedReservationAndBusySlotUntilTheDecoderActuallyReturns() = runBlocking<Unit> {
        val root = Files.createTempDirectory("auto-chop-cancel-")
        val block = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder { if (block.get()) { entered.complete(Unit); release.await() } }
        val store = FileAssetStore(root, decoder = decoder)
        val encoded = byteArrayOf(1)
        val asset = Asset(sha256(encoded), "flac", 1, 48_000, 2, 19_200_000, "fixture")
        store.publish(asset, ByteArrayInputStream(encoded))
        val memory = PcmMemoryBudget()
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        try {
            pcm.acquire(asset).use {
                val before = memory.statistics().usedBytes
                block.set(true)
                val worker = AutoChopWorker(pcm)
                val range = FrameRange(480_000, 528_000)
                val settings = AutoChopSettings(AutoChopMode.ATTACK)
                val pending = async { worker.prepare(asset, range, settings) }
                withTimeout(5_000) { entered.await() }
                assertEquals(before + AutoChopWorker.WINDOW_BYTES, memory.statistics().usedBytes)
                pending.cancel()
                assertEquals(AutoChopResult.Refused(AutoChopProblem.BUSY), worker.prepare(asset, range, settings))
                assertEquals(before + AutoChopWorker.WINDOW_BYTES, memory.statistics().usedBytes)
                release.countDown(); pending.join()
                assertEquals(before, memory.statistics().usedBytes)
                assertEquals(1, memory.statistics().leasedAssets)
            }
        } finally { release.countDown(); pcm.close(); root.toFile().deleteRecursively() }
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(decoder.opens.get(), decoder.closes.get())
    }
    @Test fun nativeRangesAt44148And96kRemainBoundedAndKeepOriginalBytes() = runBlocking<Unit> {
        val root = Files.createTempDirectory("auto-chop-")
        try {
            for (rate in listOf(44_100, 48_000, 96_000)) {
                val input = root.resolve("fixture-$rate.wav")
                val positions = listOf(rate / 4, rate / 2, rate * 3 / 4)
                val audio = FloatArray(rate * 2)
                positions.forEach { start -> repeat(rate / 100) { n ->
                    audio[(start + n) * 2] = 1.25f * (1 - n.toFloat() / (rate / 100))
                    audio[(start + n) * 2 + 1] = -audio[(start + n) * 2]
                } }
                Files.newOutputStream(input).use { out -> WavCodec.FloatWriter(out, rate.toLong(), rate).also { it.write(audio, 0, rate); it.finish() } }
                val original = Files.readAllBytes(input)
                val memory = PcmMemoryBudget()
                val assets = FileAssetStore(root.resolve("assets-$rate"))
                val asset = WavImportPort(assets, { input }).import(Location("input"))
                val pcm = WavPcmPort(assets, memory = memory)
                try {
                    val range = FrameRange(13, rate - 7L)
                    val result = assertIs<AutoChopResult.Ready>(AutoChopWorker(pcm).prepare(asset, range, AutoChopSettings(AutoChopMode.ATTACK)))
                    assertEquals(positions.size, result.markers.size)
                    positions.zip(result.markers).forEach { (expected, actual) -> assertTrue(abs(expected - actual) <= rate / 1000, "$rate: $expected -> $actual") }
                    assertTrue(result.markers.all { it > range.start && it < range.end })
                    assertContentEquals(original, Files.readAllBytes(assets.verifiedPath(asset)))
                    assertEquals(0, memory.statistics().leasedAssets)
                    assertTrue(memory.statistics().peakBytes <= memory.limitBytes)
                } finally { pcm.close() }
                assertEquals(0, memory.statistics().usedBytes)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun exhaustedSharedBudgetRefusesAttackButEqualNeedsNoAudioAndDoesNotLeak() = runBlocking<Unit> {
        val root = Files.createTempDirectory("auto-chop-budget-")
        val memory = PcmMemoryBudget(8)
        val pcm = WavPcmPort(FileAssetStore(root), memory = memory)
        try {
            val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 48_000, "fixture")
            val worker = AutoChopWorker(pcm)
            assertIs<AutoChopResult.Ready>(worker.prepare(asset, FrameRange(0, 48_000), AutoChopSettings()))
            assertEquals(AutoChopResult.Refused(AutoChopProblem.NO_MEMORY), worker.prepare(asset, FrameRange(0, 48_000), AutoChopSettings(AutoChopMode.ATTACK)))
            assertEquals(0, memory.statistics().usedBytes)
        } finally { pcm.close(); root.toFile().deleteRecursively() }
    }
}
