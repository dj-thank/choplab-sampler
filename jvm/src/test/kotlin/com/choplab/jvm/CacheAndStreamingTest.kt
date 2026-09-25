package com.choplab.jvm

import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.test.*

class CacheAndStreamingTest {
    private fun metadata(letter: String, frames: Long = 7) = Asset(letter.repeat(64), "wav", 44 + frames * 4, 48_000, 2, frames, "$letter.wav")

    @Test fun concurrentIdenticalRequestsDecodeOnceAndReuseAcrossCompiles(): Unit = runBlocking {
        PcmAssetCache().use { cache ->
            val asset = metadata("a")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val values = List(20) { async {
                cache.get(asset) { count.incrementAndGet(); entered.complete(Unit); release.await(); PcmAsset.fromInterleaved(FloatArray(14)) }
            } }
            entered.await(); yield(); release.complete(Unit)
            val decoded = values.awaitAll()
            assertEquals(1, count.get())
            decoded.forEach { assertSame(decoded.first(), it) }
            assertSame(decoded.first(), cache.get(asset.copy(name = "renamed.wav")) { error("Labels cannot invalidate content identity") })
            assertEquals(56L, cache.statistics().retainedBytes)
        }

        val root = Files.createTempDirectory("pcm-compile-cache-")
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes)
        val store = FileAssetStore(root); store.publish(asset, ByteArrayInputStream(bytes))
        WavPcmPort(store).use { pcm ->
            val compiler = ProgramCompiler(pcm)
            val project = Fixtures.project(asset)
            val first = compiler.compile(project, "pattern-1", 1)
            val second = compiler.compile(project.copy(title = "renamed"), "pattern-1", 2)
            assertSame(first.pad(0)!!.asset, second.pad(0)!!.asset)
            assertEquals(asset.frames * 8, pcm.cache.statistics().retainedBytes)
            assertFailsWith<IllegalArgumentException> { pcm.load(asset.copy(frames = asset.frames + 1)) }
        }
    }

    @Test fun lruEvictionDoesNotMutateProgramsAndFailedDecodeIsNotCached() = runBlocking {
        PcmAssetCache(maxBytes = 112).use { cache ->
            val counts = mutableMapOf<String, Int>()
            suspend fun load(letter: String) = cache.get(metadata(letter)) {
                synchronized(counts) { counts[letter] = (counts[letter] ?: 0) + 1 }
                PcmAsset.fromInterleaved(FloatArray(14) { 0.25f })
            }
            val heldByProgram = load("a")
            load("b"); load("a"); load("c") // b is least recently used.
            assertEquals(112L, cache.statistics().retainedBytes)
            load("b")
            assertEquals(2, counts["b"])
            assertEquals(0.25f, heldByProgram.sample(0, 0))
            assertTrue(cache.statistics().retainedBytes <= 112)
            var attempts = 0
            assertFailsWith<IOException> { cache.get(metadata("f")) { attempts++; throw IOException("decode failed") } }
            val repaired = cache.get(metadata("f")) { attempts++; PcmAsset.fromInterleaved(FloatArray(14)) }
            assertEquals(2, attempts); assertEquals(7, repaired.frameCount)
            assertFailsWith<IllegalArgumentException> { cache.get(metadata("d")) { PcmAsset.fromInterleaved(FloatArray(12)) } }
            assertEquals(7, cache.get(metadata("d")) { PcmAsset.fromInterleaved(FloatArray(14)) }.frameCount)
        }
    }

    @Test fun cancellingOneWaiterPreservesOtherWaitersAndAllCancelledLoadIsDiscarded() = runBlocking {
        PcmAssetCache().use { cache ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            suspend fun request() = cache.get(metadata("a")) { count.incrementAndGet(); entered.complete(Unit); release.await(); PcmAsset.fromInterleaved(FloatArray(14)) }
            val first = async { request() }; entered.await()
            val second = async { request() }; yield()
            first.cancelAndJoin(); release.complete(Unit)
            assertEquals(7, second.await().frameCount); assertEquals(1, count.get())

            val slowEntered = CompletableDeferred<Unit>()
            val cancelled = async { cache.get(metadata("b")) { slowEntered.complete(Unit); awaitCancellation() } }
            slowEntered.await(); cancelled.cancelAndJoin()
            val retry = cache.get(metadata("b")) { PcmAsset.fromInterleaved(FloatArray(14)) }
            assertEquals(7, retry.frameCount)
        }
    }

    @Test fun singleDecodeSlotBoundsDifferentIdentityConcurrency() = runBlocking {
        PcmAssetCache().use { cache ->
            val active = AtomicInteger(); val maximum = AtomicInteger()
            List(8) { index -> async {
                cache.get(metadata(index.toString())) {
                    val running = active.incrementAndGet(); maximum.accumulateAndGet(running, ::maxOf)
                    try { delay(5); PcmAsset.fromInterleaved(FloatArray(14)) } finally { active.decrementAndGet() }
                }
            } }.awaitAll()
            assertEquals(1, maximum.get())
        }
    }

    @Test fun cancelledLoadCanBeRetriedAtThePendingCapacityLimit() = runBlocking {
        PcmAssetCache(maximumPending = 1).use { cache ->
            val entered = CompletableDeferred<Unit>(); val oldDecoderExit = CompletableDeferred<Unit>()
            val old = async { cache.get(metadata("a")) {
                entered.complete(Unit)
                withContext(NonCancellable) { oldDecoderExit.await() }
                PcmAsset.fromInterleaved(FloatArray(14) { 0.1f })
            } }
            entered.await(); old.cancelAndJoin()
            val retry = async { cache.get(metadata("a")) { PcmAsset.fromInterleaved(FloatArray(14) { 0.2f }) } }
            yield(); oldDecoderExit.complete(Unit)
            assertEquals(0.2f, retry.await().sample(0, 0))
        }
    }

    private fun program(): EngineProgram {
        val pcm = PcmAsset.fromInterleaved(FloatArray(4096) { i -> (0.18 * sin(i / 2.0 * 0.057) * if (i % 2 == 0) 1.0 else -0.4).toFloat() })
        return EngineProgram(listOf(com.choplab.engine.Pad(0, pcm)), com.choplab.engine.Pattern(3840, listOf(SequenceNote(0, 0), SequenceNote(90, 0, 0.7f), SequenceNote(240, 0, 0.8f))), Tempo(147_125))
    }

    @Test fun streamingWavIsBitExactWithArrayReferenceForEveryBlockSize() {
        val program = program(); val frames = 9137; val tail = 192
        for (bits in listOf(16, 24)) {
            val referencePcm = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(frames.toLong(), 2)), frames, tail, blockFrames = 17)
            val reference = ByteArrayOutputStream().also { WavCodec.writePcm(it, referencePcm, bits = bits, seed = 47) }.toByteArray()
            for (block in listOf(1, 17, 96, 192, 480, 4096)) {
                val output = ByteArrayOutputStream()
                val stats = StreamingWavRenderer.render(program, output, frames, tail, bits, 47, block)
                assertContentEquals(reference, output.toByteArray(), "bits=$bits, block=$block")
                assertEquals(72, stats.latencyFrames)
                assertEquals((frames + tail).toLong(), stats.outputFrames)
                assertEquals(block * 8, stats.floatBufferBytes)
            }
        }
    }

    @Test fun longExportHasConstantBuffersAndExactFrameCount() {
        class CountingOutput : OutputStream() {
            var count = 0L; var largestWrite = 0
            override fun write(value: Int) { count++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { count += length; largestWrite = maxOf(largestWrite, length) }
        }
        val output = CountingOutput()
        val frames = 2_000_000
        val stats = StreamingWavRenderer.render(EngineProgram.EMPTY, output, frames, tailFrames = 192, blockFrames = 480)
        assertEquals(44L + (frames + 192L) * 6, output.count)
        assertEquals(480 * 8, stats.floatBufferBytes)
        assertEquals(480 * 6, stats.quantizerBufferBytes)
        assertTrue(output.largestWrite <= 480 * 6)
        assertEquals(frames + 192L + 72, stats.renderedFrames)
    }

    @Test fun cancelledLongExportPreservesDestinationAndRemovesPendingFile() {
        val directory = Files.createTempDirectory("stream-cancel-"); val target = directory.resolve("mix.wav")
        Files.writeString(target, "original")
        var blocks = 0
        assertFailsWith<CancellationException> {
            atomicOutput(target) { output ->
                StreamingWavRenderer.render(program(), output, 30_000_000, cancelled = { ++blocks > 40 })
            }
        }
        assertEquals(41, blocks)
        assertEquals("original", Files.readString(target))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test fun detachedEngineAcknowledgesSuccessfullyAppliedLateCommands() = runBlocking {
        val port = DetachedEnginePort(ProgramCompiler(object : com.choplab.core.PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = error("No assets in this fixture")
        }))
        assertTrue(port.apply(EngineCommand.SwapProgram(0, 1, EngineProgram.EMPTY)))
        assertTrue(port.snapshot().frame > 0)
        assertTrue(port.apply(EngineCommand.Stop(0, 2)))
    }
}
