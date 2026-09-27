package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LongPcmIntegrationTest {
    private fun sample(frame: Int, channel: Int): Float =
        if (channel == 0) ((frame % 997) - 311) / 997f else ((frame % 641) - 433) / 211f

    @Test fun fourHundredSecondWavKeepsOriginalBytesAndLateRangesThroughArchiveAndExactExport() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("long-pcm-")
        try {
            val input = directory.resolve("source.wav")
            val frames = 400 * 48_000
            Files.newOutputStream(input).use { output ->
                val writer = WavCodec.FloatWriter(output, frames.toLong(), channels = 2)
                val buffer = FloatArray(4096 * 2)
                var first = 0
                while (first < frames) {
                    val count = minOf(4096, frames - first)
                    for (index in 0 until count * 2) buffer[index] = sample(first + index / 2, index % 2)
                    writer.write(buffer, frameCount = count); first += count
                }
                writer.finish()
            }
            val store = FileAssetStore(directory.resolve("assets"))
            val asset = WavImportPort(store, { input }).import(Location("source"))
            assertEquals(frames.toLong(), asset.frames)
            assertTrue(asset.byteCount > EngineFormat.MAX_RESIDENT_BYTES)
            val from = 375 * 48_000 + 3981
            val end = from + 15_001
            val project = Fixtures.project(asset).let { it.copy(pads = it.pads.map { pad ->
                if (pad.id == 0) pad.copy(range = FrameRange(from.toLong(), end.toLong()), reverse = true, pitchSemitones = 7.0, tone = .57f) else pad
            }.frozen()) }
            WavPcmPort(store).use { pcm ->
                val data = pcm.load(asset)
                assertNotNull(data.pages)
                assertEquals(frames, data.frameCount)
                assertEquals(PcmResidency.bytes(asset), data.residentBytes)
                assertTrue(data.residentBytes < 20L * 1024 * 1024)
                pcm.prefetch(data, from - 128, end + 128)
                for (frame in listOf(0, from, from + 114, end - 1)) for (channel in 0..1)
                    assertEquals(sample(frame, channel).toRawBits(), data.sample(frame, channel).toRawBits())
                val compiler = ProgramCompiler(pcm)
                val compiled = compiler.compile(project, "pattern-1", 1)
                assertSame(data, compiled.pad(0)!!.asset)
                assertEquals(data.residentBytes, compiled.residentBytes)
                // Independent synthetic oracle at the SAME absolute frame coordinates: shifting a
                // pitched range to zero changes floating-point position accumulation in the old graph too.
                val referencePages = PagedPcm(frames)
                for (page in from / PagedPcm.PAGE_FRAMES..end / PagedPcm.PAGE_FRAMES) {
                    val first = page * PagedPcm.PAGE_FRAMES
                    referencePages.publish(page, FloatArray(PagedPcm.PAGE_FRAMES * 2) { sample(first + it / 2, it % 2) })
                }
                val oraclePad = ProgramCompiler.enginePad(project.pads[0], asset, PcmAsset.paged(referencePages))
                val oracle = PadRender.render(oraclePad) { _, render -> render() }
                val actual = PadRender.render(compiled.pad(0)!!) { windows, render -> runBlocking { pcm.prepared(windows, render) } }
                assertContentEquals(oracle, actual, "The late reverse/pitched/tone range must use the same graph")
                val referenceProgram = EngineProgram(listOf(oraclePad), compiled.pattern, compiled.tempo)
                val reference = ByteArrayOutputStream().also { StreamingWavRenderer.render(referenceProgram, it, 32_000, 192, seed = 7, prepared = { _, render -> render() }) }.toByteArray()
                val rendered = ByteArrayOutputStream()
                StreamingWavRenderer.render(compiled, rendered, 32_000, 192, seed = 7,
                    prepared = { windows, render -> runBlocking { compiler.prepared(windows, render) } })
                assertContentEquals(reference, rendered.toByteArray())
                assertEquals(0L, referencePages.misses)
                val beforeClear = data.pages!!.statistics()
                pcm.cache.clear() // Program/SOURCE lease still owns the same provider after LRU eviction.
                pcm.prefetch(data, frames - 2048, frames)
                assertEquals(sample(frames - 1, 1), data.sample(frames - 1, 1))
                assertTrue(data.pages!!.statistics().retainedBytes <= beforeClear.capacityBytes)
            }
            val archive = directory.resolve("project.choplab")
            Files.newOutputStream(archive).use { ArchiveCodec().write(project, store, it) }
            val restoredStore = FileAssetStore(directory.resolve("restored"))
            val reopened = Files.newInputStream(archive).use { ArchiveCodec().read(it, restoredStore) }
            assertEquals(project, reopened)
            // Independent streaming byte comparison: encoded original/header/float headroom are all unchanged.
            Files.newInputStream(input).use { expected -> restoredStore.openVerified(reopened.assets.single()).use { actual ->
                val a = ByteArray(65_536); val b = ByteArray(65_536)
                while (true) {
                    val n = expected.read(a)
                    if (n < 0) { assertEquals(-1, actual.read()); break }
                    var read = 0
                    while (read < n) { val count = actual.read(b, read, n - read); assertTrue(count > 0); read += count }
                    assertEquals(-1, java.util.Arrays.mismatch(a, 0, n, b, 0, n))
                }
            } }
            WavPcmPort(restoredStore).use { pcm ->
                val data = pcm.load(reopened.assets.single())
                pcm.prefetch(data, from, end)
                assertEquals(sample(from, 1), data.sample(from, 1))
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cancelledAndFailedProviderNeverPublishesPartialDataAndRetryUsesFreshOwnership() = runBlocking<Unit> {
        val frames = 19_200_000
        val asset = Asset("a".repeat(64), "flac", 100, 48_000, 2, frames.toLong(), "synthetic")
        val closed = AtomicInteger()
        val fail = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val blocked = AtomicBoolean(true)
        fun source() = object : PcmFrameSource {
            override val info = WavInfo(48_000, 2, frames.toLong(), 32, true)
            override fun close() { closed.incrementAndGet() }
            override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean): FloatArray {
                if (firstFrame > 1_000_000) {
                    entered.complete(Unit)
                    while (blocked.get()) { if (cancelled()) throw CancellationException(); Thread.sleep(1) }
                    if (fail.get()) throw java.io.IOException("injected read failure")
                }
                return FloatArray(frameCount * 2) { sample(firstFrame + it / 2, it % 2) }
            }
        }
        PcmPrefetchWorker().use { worker ->
            val data = worker.open(asset, source())
            val cancelled = async { worker.prefetch(data, 18_000_000, 18_004_000) }
            entered.await(); cancelled.cancelAndJoin()
            assertFalse(data.pages!!.isLoaded(18_000_000 / PagedPcm.PAGE_FRAMES))
            fail.set(true); blocked.set(false)
            withTimeout(5000) { while (data.pages!!.status != PcmReadStatus.FAILED) delay(1) }
            assertEquals(0f, data.sample(18_000_000, 0))
            assertFailsWith<PcmPrefetchFailure> { worker.prefetch(data, 18_000_000, 18_004_000) }
            fail.set(false)
            val retry = worker.open(asset, source())
            worker.prefetch(retry, 18_000_000, 18_004_000)
            assertEquals(sample(18_000_000, 1), retry.sample(18_000_000, 1))
            assertEquals(1, closed.get(), "A failed provider releases scratch while the retained cache keeps its typed failure")
        }
        assertEquals(2, closed.get(), "Every provider has exactly one close")
    }
}
