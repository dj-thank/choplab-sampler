package com.choplab.jvm

import com.choplab.core.PcmResidency
import com.choplab.core.analysis.SourceMusicAnalysis
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.test.*

class SourceMusicWorkerTest {
    @Test fun cancellingAnUncooperativeWindowRetainsItsReservationUntilTheWorkerReturns() = runBlocking {
        val directory = Files.createTempDirectory("source-analysis-cancel-")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val block = AtomicBoolean()
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder {
            if (block.get()) { entered.complete(Unit); release.await() }
        }
        val store = FileAssetStore(directory, decoder = decoder)
        val bytes = byteArrayOf(1)
        val asset = Asset(sha256(bytes), "flac", 1, 48_000, 2, 400L * 48_000, "Long original")
        store.write(asset, bytes)
        val memory = PcmMemoryBudget(PcmResidency.bytes(asset) + SourceMusicAnalysis.WORK_BYTES)
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        try {
            pcm.acquire(asset).use { active ->
                block.set(true)
                val task = async { analyseSourceMusic(pcm, asset, FrameRange(375L * 48_000, 381L * 48_000)) }
                withTimeout(5_000) { entered.await() }
                task.cancel()
                assertEquals(memory.limitBytes, memory.statistics().usedBytes)
                assertEquals(2, active.pcm.leaseCount)
                assertFalse(task.isCompleted)
                release.countDown()
                withTimeout(5_000) { task.join() }
                assertEquals(PcmResidency.bytes(asset), memory.statistics().usedBytes)
                assertEquals(1, active.pcm.leaseCount)
            }
        } finally { release.countDown(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(decoder.opens.get(), decoder.closes.get())
    }

    @Test fun nativeRangeUsesSharedResampledPcmWithoutChangingOriginalBytes() = runBlocking {
        val directory = Files.createTempDirectory("source-analysis-native-")
        val store = FileAssetStore(directory)
        val memory = PcmMemoryBudget()
        val pcm = WavPcmPort(store, memory = memory)
        try {
            val rate = 44_100
            val samples = FloatArray(8 * rate * 2) { index ->
                val frame = index / 2
                val phase = frame % (rate * 60 / 98.0)
                val kick = if (phase < rate / 10) .7 * sin(phase * 2 * PI * 78 / rate) * exp(-phase / (rate * .015)) else 0.0
                (kick * if (index % 2 == 0) 1 else -1).toFloat()
            }
            val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples, rate, 2) }.toByteArray()
            val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), rate, 2, samples.size / 2L, "Original")
            store.write(asset, bytes)
            // Non-integral 44.1 -> 48 kHz boundaries must not include a frame outside the selected range.
            val range = FrameRange(101, asset.frames - 103)
            val result = analyseSourceMusic(pcm, asset, range)
            val first = (range.start * 48_000 + rate - 1) / rate
            val end = range.end * 48_000 / rate
            assertEquals((end - first).toInt(), result.frames)
            assertTrue(result.tempos.any { abs(it.milliBpm - 98_000) <= 1_000 })
            assertTrue(result.keys.isEmpty())
            assertContentEquals(bytes, store.read(asset))
            assertEquals(0, memory.statistics().leasedAssets)
            assertEquals(PcmResidency.bytes(asset), memory.statistics().usedBytes)
            assertFailsWith<IllegalArgumentException> { analyseSourceMusic(pcm, asset, FrameRange(0, asset.frames + 1)) }
        } finally { pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0, memory.statistics().usedBytes)
    }

    @Test fun latePagedRangesReserveWorkRefuseOverBudgetAndReturnEveryLease() = runBlocking {
        val directory = Files.createTempDirectory("source-analysis-pages-")
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder()
        val store = FileAssetStore(directory, decoder = decoder)
        val bytes = byteArrayOf(1)
        val asset = Asset(sha256(bytes), "flac", 1, 48_000, 2, 400L * 48_000, "Long original")
        store.write(asset, bytes)
        val memory = PcmMemoryBudget(PcmResidency.bytes(asset) + SourceMusicAnalysis.WORK_BYTES)
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        try {
            pcm.acquire(asset).use { active ->
                memory.reserve(1).use {
                    assertFailsWith<PcmMemoryLimit> { analyseSourceMusic(pcm, asset, FrameRange(375L * 48_000, 381L * 48_000)) }
                    assertFalse(active.pcm.evicted)
                    assertEquals(1, memory.statistics().leasedAssets)
                    assertEquals(1, active.pcm.leaseCount, "Refused analysis returns its extra PCM lease")
                }
                val result = analyseSourceMusic(pcm, asset, FrameRange(375L * 48_000 + 3911, 381L * 48_000 + 3911))
                assertEquals(6 * 48_000, result.frames)
                assertEquals(1, active.pcm.leaseCount)
                assertEquals(memory.limitBytes, memory.statistics().peakBytes)
                assertEquals(PcmResidency.bytes(asset), memory.statistics().usedBytes)
                assertFailsWith<IllegalArgumentException> { analyseSourceMusic(pcm, asset, FrameRange(0, 31L * 48_000)) }
            }
        } finally { pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(decoder.opens.get(), decoder.closes.get())
    }
}
