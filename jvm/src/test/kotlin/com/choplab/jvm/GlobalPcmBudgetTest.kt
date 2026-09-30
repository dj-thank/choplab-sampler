package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class GlobalPcmBudgetTest {
    @Test fun workerReservationsRefuseWithoutWaitingAndCanShrinkAfterTheirBuffersAreReleased() = runBlocking {
        val memory = PcmMemoryBudget(32)
        val nativeAndCopy = memory.reserve(24)
        assertFailsWith<PcmMemoryLimit> { memory.reserve(16) }
        nativeAndCopy.shrinkTo(16)
        memory.reserve(16).use { assertEquals(32, memory.statistics().usedBytes) }
        assertFailsWith<IllegalArgumentException> { nativeAndCopy.shrinkTo(24) }
        nativeAndCopy.close(); nativeAndCopy.close()
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(32, memory.statistics().peakBytes)
    }

    @Test fun activeAndQueuedProgramsShareOneChargeAndRejectBeforeASecondCopyCanExceedIt() = runBlocking {
        val bytes = 1024L * 8
        val memory = PcmMemoryBudget(bytes * 3)
        val owner = Any()
        val disposed = mutableListOf<PcmAsset>()
        suspend fun decoded(value: Float): PcmLease = memory.reserve(bytes * 2).use { reservation ->
            val data = PcmAsset.fromInterleaved(FloatArray(2048) { value })
            reservation.publish(data, owner) { disposed.add(data) }
        }
        val a = decoded(.25f)
        val first = a.pcm
        val engine = EngineCore(EngineProgram(listOf(Pad(0, first))))
        a.close()
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(first))))
        engine.render(FloatArray(2))
        val b = decoded(.5f)
        val second = b.pcm
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SwapProgram(500, 2, EngineProgram(listOf(Pad(0, second))))))
        b.close()
        assertEquals(bytes * 2, memory.statistics().usedBytes, "SOURCE and program share the actual PCM object")
        assertFailsWith<PcmMemoryLimit> { decoded(.75f) }
        assertFalse(first.evicted); assertFalse(second.evicted)
        assertEquals(.25f, first.sample(0, 0)); assertEquals(.5f, second.sample(0, 1))
        engine.close() // Includes the not-yet-consumed queued program.
        engine.close()
        assertEquals(0, first.leaseCount); assertEquals(0, second.leaseCount)
        decoded(.75f).use { assertEquals(.75f, it.pcm.sample(0, 0)) }
        assertTrue(first.evicted)
        assertEquals(bytes * 3, memory.statistics().peakBytes, "Both decoder copies were admitted while the old program sounded")
        memory.releaseOwner(owner) {}
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(3, disposed.size)
    }

    @Test fun full128MiBLongPageCachesEvictOnlyIdleAssetsAndCanReadExactLateWindowsAgain() = runBlocking {
        val root = Files.createTempDirectory("global-long-pcm-")
        val decoder = SyntheticDecoder()
        val memory = PcmMemoryBudget()
        val store = FileAssetStore(root, decoder = decoder)
        val metadata = (0..8).map { value ->
            val encoded = byteArrayOf(value.toByte())
            Asset(sha256(encoded), "flac", 1, 48_000, 2, 400L * 48_000, "fixture").also {
                store.publish(it, ByteArrayInputStream(encoded))
            }
        }
        val shortBytes = byteArrayOf(42)
        val short = Asset(sha256(shortBytes), "flac", 1, 48_000, 2, 1_500_000, "resident fixture")
        store.publish(short, ByteArrayInputStream(shortBytes))
        val count = (memory.limitBytes / PcmResidency.bytes(metadata.first())).toInt()
        assertEquals(7, count)
        val leases = mutableListOf<PcmLease>()
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        var engine: EngineCore? = null
        try {
            repeat(count) { index ->
                val lease = pcm.acquire(metadata[index]).also(leases::add)
                pcm.prefetch(lease.pcm, 0, PagedPcm.DEFAULT_PAGES * PagedPcm.PAGE_FRAMES)
                assertEquals(512, lease.pcm.pages!!.statistics().loadedPages)
            }
            val original = leases.first().pcm
            engine = EngineCore(EngineProgram(listOf(Pad(0, original))))
            assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(original))))
            engine.render(FloatArray(2))
            assertFailsWith<PcmMemoryLimit> { pcm.acquire(metadata[count]) }
            assertFailsWith<PcmMemoryLimit> { pcm.acquire(short) }
            assertEquals(0, decoder.residentDecodes.get(), "No native array may be allocated before peak admission")
            assertEquals(count, memory.statistics().leasedAssets)
            assertTrue(leases.none { it.pcm.evicted })
            val idle = leases.removeAt(leases.lastIndex).also { it.close() }.pcm
            val replacement = pcm.acquire(metadata[count]).also(leases::add)
            assertTrue(idle.evicted)
            assertEquals(0, idle.pages!!.statistics().loadedPages, "Budget is returned after the old page arrays/provider are gone")
            val late = 375 * 48_000 + 3911
            memory.reserve(4096 * 8L).use {
                val window = pcm.readWindow(replacement.pcm, late, late + 4096)
                for (index in window.indices) assertEquals(SyntheticDecoder.sample(late + index / 2, index % 2, count), window[index])
            }
            // Clear the LRU map while the SOURCE/queued program still owns its first PCM.
            pcm.cache.clear()
            pcm.prefetch(original, late, late + 4096)
            assertEquals(SyntheticDecoder.sample(late, 1, 0), original.sample(late, 1))
            replacement.close()
            leases.remove(replacement)
            pcm.acquire(metadata[count - 1]).use { restored ->
                assertNotSame(idle, restored.pcm)
                pcm.prefetch(restored.pcm, late, late + 4096)
                assertEquals(SyntheticDecoder.sample(late, 0, count - 1), restored.pcm.sample(late, 0))
            }
            pcm.acquire(short).use { resident ->
                assertNull(resident.pcm.pages)
                assertEquals(SyntheticDecoder.sample(1_499_999, 1, 42), resident.pcm.sample(1_499_999, 1))
            }
            assertEquals(1, decoder.residentDecodes.get())
            val stats = memory.statistics()
            assertTrue(stats.peakBytes <= 128L * 1024 * 1024)
            assertTrue(stats.peakBytes > 112L * 1024 * 1024)
            println("global PCM peak=${stats.peakBytes} limit=${stats.limitBytes}; $count filled page caches, SOURCE+program+worker window")
        } finally {
            engine?.close(); leases.forEach { it.close() }; pcm.close()
            assertEquals(0, memory.statistics().usedBytes)
            root.toFile().deleteRecursively()
        }
        assertEquals(decoder.opens.get(), decoder.closes.get())
    }

    @Test fun cancelledUncooperativeDecodeKeepsItsReservationUntilTheProviderAndCopiesAreGone() = runBlocking {
        val root = Files.createTempDirectory("pcm-cancel-budget-")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val decoder = SyntheticDecoder { entered.complete(Unit); release.await() }
        val store = FileAssetStore(root, decoder = decoder)
        val bytes = byteArrayOf(1)
        val asset = Asset(sha256(bytes), "flac", 1, 48_000, 2, 19_200_000, "fixture")
        store.publish(asset, ByteArrayInputStream(bytes))
        val memory = PcmMemoryBudget(PcmResidency.bytes(asset))
        val pcm = WavPcmPort(store, decoder = decoder, memory = memory)
        try {
            val loading = async { pcm.acquire(asset) }
            withTimeout(5000) { entered.await() }
            loading.cancelAndJoin()
            assertEquals(memory.limitBytes, memory.statistics().usedBytes)
            assertFailsWith<PcmMemoryLimit> { memory.reserve(8) }
            release.countDown()
            withTimeout(5000) { while (memory.statistics().usedBytes != 0L) delay(1) }
            assertEquals(1, decoder.closes.get())
            pcm.acquire(asset).use { lease ->
                assertEquals(SyntheticDecoder.sample(0, 1, 1), lease.pcm.sample(0, 1))
            }
        } finally {
            release.countDown(); pcm.close(); root.toFile().deleteRecursively()
        }
        assertEquals(0, memory.statistics().usedBytes)
        assertEquals(decoder.opens.get(), decoder.closes.get())
    }

    @Test fun aCancelledPublicationReleasesTheLeaseEvenWhenItsProducerIgnoresCancellation() = runBlocking {
        val memory = PcmMemoryBudget(8192)
        val owner = Any()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        val metadata = Asset("a".repeat(64), "wav", 60, 48_000, 2, 2, "fixture")
        PcmAssetCache().use { cache ->
            val request = async {
                cache.acquire(metadata, discard = { memory.discard(it) }) {
                    val lease = memory.reserve(32).use {
                        val data = PcmAsset.fromInterleaved(FloatArray(4))
                        it.publish(data, owner) { disposed.complete(Unit); Unit }
                    }
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    lease
                }
            }
            entered.await(); request.cancelAndJoin()
            assertEquals(16, memory.statistics().usedBytes)
            release.complete(Unit)
            withTimeout(5000) { disposed.await() }
            withTimeout(5000) { while (memory.statistics().usedBytes != 0L) delay(1) }
        }
        memory.releaseOwner(owner) {}
        assertEquals(0, memory.statistics().usedBytes)
    }

    @Test fun closingPortWaitsForItsPreparedLeaseAndWorkerOwnsFinalDisposal() = runBlocking {
        val memory = PcmMemoryBudget(8192)
        val owner = Any()
        val released = CompletableDeferred<String>()
        val lease = memory.reserve(32).use {
            val pcm = PcmAsset.fromInterleaved(FloatArray(4) { .3f })
            it.publish(pcm, owner) { released.complete(Thread.currentThread().name) }
        }
        memory.releaseOwner(owner) {}
        assertEquals(16, memory.statistics().usedBytes)
        assertFalse(released.isCompleted)
        lease.close(); lease.close()
        val thread = withTimeout(5000) { released.await() }
        withTimeout(5000) { while (memory.statistics().usedBytes != 0L) delay(1) }
        assertTrue(thread.contains("DefaultDispatcher"), thread)
        assertEquals(0, memory.statistics().usedBytes)
    }

    internal class SyntheticDecoder(val beforeRead: () -> Unit = {}) : OriginalAudioDecoder {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val residentDecodes = AtomicInteger()
        override fun inspect(path: java.nio.file.Path, hash: String, cancelled: () -> Boolean) =
            WavInfo(48_000, 2, if (Files.readAllBytes(path).single().toInt() == 42) 1_500_000 else 19_200_000, 32, true)
        override fun decode(path: java.nio.file.Path, hash: String, cancelled: () -> Boolean): WavAudio {
            val info = inspect(path, hash, cancelled)
            check(info.frames == 1_500_000L) { "Long input must not be resident" }
            residentDecodes.incrementAndGet()
            return WavAudio(info, FloatArray(info.frames.toInt() * 2) { sample(it / 2, it % 2, 42) })
        }
        override fun openPcm(path: java.nio.file.Path, hash: String, cancelled: () -> Boolean): PcmFrameSource {
            opens.incrementAndGet()
            val number = Files.readAllBytes(path).single().toInt()
            return object : PcmFrameSource {
                override val info = inspect(path, hash, cancelled)
                override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean): FloatArray {
                    beforeRead()
                    return FloatArray(frameCount * 2) { sample(firstFrame + it / 2, it % 2, number) }
                }
                override fun close() { closes.incrementAndGet() }
            }
        }
        companion object {
            fun sample(frame: Int, channel: Int, number: Int): Float =
                if (channel == 0) (frame % 997 - 311) / 997f + number / 32f else (frame % 641 - 433) / 211f - number / 64f
        }
    }
}
