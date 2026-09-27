package com.choplab.jvm.separation

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.edit.Intent
import com.choplab.core.separation.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.FloatBuffer
import java.nio.file.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.test.*

class FourStemServiceTest {
    private val available = SeparationMemory(8L shl 30, 4L shl 30, false)
    @Test fun freshAdmissionReceiptTracksTheSecondPreflightAndDiscardsAPreviousObservationOnRetry() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-memory-")
        val memory = PcmMemoryBudget(); val assets = FileAssetStore(directory.resolve("assets")); val pcm = WavPcmPort(assets, memory = memory)
        val native = GainFactory()
        val first = SeparationMemoryReceipt(SeparationMemorySource.MAC_FREE_AND_FILE_BACKED,
            16L shl 30, 3L shl 30, false, 1_800_000_000_000L)
        val second = first.copy(lowMemory = true, measuredAtEpochMillis = first.measuredAtEpochMillis + 20)
        val observations = ArrayDeque(listOf(
            SeparationMemory(first.totalBytes, first.availableBytes, first.lowMemory, first),
            SeparationMemory(second.totalBytes, second.availableBytes, second.lowMemory, second),
            SeparationMemory(0, 0, false),
        ))
        val service = FourStemService(assets, pcm, directory.resolve("scratch"), native) { observations.removeFirst() }
        try {
            val original = source(directory, assets)
            assertEquals(SeparationProblem.LOW_MEMORY, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
            assertEquals(second, service.memoryReceipt()); assertEquals(0, native.opens.get())
            assertEquals(SeparationProblem.RAM_UNAVAILABLE, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
            assertNull(service.memoryReceipt()); assertTrue(observations.isEmpty())
            assertEquals(original.byteCount, assets.storedBytes()); assertTrue(assets.verified(original))
            assertEquals(0L, Files.list(directory.resolve("scratch")).use { it.count() })
            // Existing Java native readback helpers keep their three-argument constructor.
            assertNotNull(SeparationMemory::class.java.getConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        } finally { service.close(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes)
    }
    private class GainFactory : FourStemSessionFactory {
        val opens = AtomicInteger(); val closes = AtomicInteger(); val cancels = AtomicInteger(); val calls = AtomicInteger()
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var invalid = false
        override fun open(memory: PcmMemoryBudget, available: SeparationMemory, allowDownload: Boolean, check: () -> Unit): FourStemInference {
            opens.incrementAndGet()
            return object : FourStemInference {
                override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                    calls.incrementAndGet()
                    runBlocking { memory.reserve(FourStemSpec.NATIVE_IO_PCM_BYTES) }.use {
                        val output = FloatArray(FourStemSpec.FRAMES * 8) { index ->
                            if (invalid) Float.NaN else channelMajor[index % (FourStemSpec.FRAMES * 2)] * (index / (FourStemSpec.FRAMES * 2) + 1) * .1f
                        }
                        entered?.complete(Unit)
                        release?.let { runBlocking { it.await() } } // A native call may return late after cancel.
                        consume(FloatBuffer.wrap(output))
                    }
                }
                override fun cancel() { cancels.incrementAndGet() }
                override fun close() { closes.incrementAndGet() }
            }
        }
    }
    /** Fixture creation is streamed too, so the paged-source test does not construct a full-duration array. */
    private fun source(directory: Path, assets: FileAssetStore, frames: Int = 48_000, rate: Int = 48_000, silent: Boolean = false): Asset {
        val temporary = directory.resolve("original.wav")
        val hash = MessageDigest.getInstance("SHA-256")
        FileOutputStream(temporary.toFile()).use { stream ->
            val writer = WavCodec.FloatWriter(DigestOutputStream(stream, hash), frames.toLong(), rate)
            val block = FloatArray(4096 * 2)
            var at = 0
            while (at < frames) {
                val count = minOf(4096, frames - at)
                for (frame in 0 until count) {
                    block[frame * 2] = if (silent) 0f else (.12 * cos(2 * PI * 220 * (at + frame) / rate)).toFloat()
                    block[frame * 2 + 1] = if (silent) 0f else (-.04 * cos(2 * PI * 330 * (at + frame) / rate)).toFloat()
                }
                writer.write(block, frameCount = count); at += count
            }
            writer.finish()
        }
        val asset = Asset(hash.digest().hex(), "wav", Files.size(temporary), rate, 2, frames.toLong(), "Original.wav")
        assets.adopt(asset, temporary)
        return asset
    }

    @Test fun workerToOneExplicitEditUndoAllPresetsEngineExportAndFreshArchiveAutosave() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-production-")
        val locations = mutableMapOf<String, Path>()
        val backend = EditorBackend.create(directory.resolve("profile"), { StreamingEnginePort(it, { error("No device") }) }, { assets, compiler ->
            HostFileServices(WavImportPort(assets, { locations.getValue(it.handle) }), FileProjectPort(assets, { locations.getValue(it.handle) }), WavExportPort(compiler, { locations.getValue(it.handle) }))
        })
        val pcm = WavPcmPort(backend.assets)
        val native = GainFactory()
        val service = FourStemService(backend.assets, pcm, directory.resolve("scratch"), native) { available }
        try {
            val original = source(directory, backend.assets)
            val bytes = backend.assets.read(original)
            val before = Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, original.frames), pitchSemitones = 3.0),
                tempo = Tempo(97_125, 710), lyrics = frozenListOf(LyricLine("line", "Test line", 0, 3840)))
            assertTrue(backend.studio.dispatch(Action.New(before)).accepted)
            val revision = backend.studio.document.value.revision
            val progress = mutableListOf<SeparationProgress>()
            val prepared = assertIs<SeparationResult.Success<PreparedFourStems>>(service.prepare(original, progress = progress::add)).value
            assertEquals(before, backend.studio.document.value.project); assertEquals(revision, backend.studio.document.value.revision)
            assertEquals(StemPart.entries, prepared.stems.map { it.part }); assertEquals(44_100L, prepared.stems.first().asset.frames)
            assertEquals(0L, progress.first().completedFrames); assertEquals(44_100L, progress.last().completedFrames)
            assertTrue(prepared.stems.all { backend.assets.containsVerified(it.asset) })
            val exports = mutableMapOf<StemMix, FloatArray>()
            for (mix in StemMix.entries) {
                val currentRevision = backend.studio.document.value.revision
                val intent = assertIs<SeparationResult.Success<Intent.SetArrangement>>(prepared.placement(before, 0, mix, "stems")).value
                assertTrue(backend.studio.dispatch(Action.Edit(intent, expectedRevision = currentRevision)).accepted)
                val after = backend.studio.document.value.project
                assertEquals(before.source, after.source); assertEquals(before.lyrics, after.lyrics); assertEquals(5, after.assets.size)
                assertTrue(backend.studio.dispatch(Action.Undo).accepted); assertEquals(before, backend.studio.document.value.project)
                assertTrue(backend.studio.dispatch(Action.Redo).accepted); assertEquals(after, backend.studio.document.value.project)
                assertFalse(backend.studio.dispatch(Action.Edit(intent, expectedRevision = currentRevision)).accepted)
                assertEquals(after, backend.studio.document.value.project)
                val output = directory.resolve("${mix.name}.wav"); locations["export"] = output
                assertTrue(backend.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
                assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(Location("export"), 48_000, tailFrames = 0, bits = 24))).accepted)
                withTimeout(15_000) { while (backend.studio.work.value.jobId != null) delay(5) }
                val rendered = Files.newInputStream(output).use(WavCodec::read)
                assertEquals(24, rendered.info.bits); assertEquals(48_000L, rendered.info.frames)
                assertTrue(rendered.samples.filterIndexed { index, _ -> index % 2 == 0 }.maxOf { abs(it) } > .04)
                exports[mix] = rendered.samples
                if (mix != StemMix.entries.last()) { assertTrue(backend.studio.dispatch(Action.Undo).accepted); assertEquals(before, backend.studio.document.value.project) }
            }
            val all = exports.getValue(StemMix.ALL); val vocal = exports.getValue(StemMix.ACAPELLA); val instrumental = exports.getValue(StemMix.INSTRUMENTAL)
            assertTrue(all.indices.maxOf { abs(all[it] - vocal[it] - instrumental[it]) } < 7e-7, "The same EngineCore sums all heads and both mute presets")
            val after = backend.studio.document.value.project
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(after, backend.assets, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            assertEquals(after, ArchiveCodec().read(ByteArrayInputStream(archive), fresh))
            for (asset in after.assets) assertContentEquals(backend.assets.read(asset), fresh.read(asset))
            backend.flushAutosave()
            assertEquals(after, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
            assertContentEquals(bytes, backend.assets.read(original)); assertEquals(1, native.opens.get()); assertEquals(1, native.closes.get())
            assertEquals(0L, Files.list(directory.resolve("scratch")).use { it.count() })
        } finally { service.close(); pcm.close(); backend.shutdown(); directory.toFile().deleteRecursively() }
    }

    @Test fun cancelKeepsTheReservationUntilLateNativeExitRejectsOverlapAndReleasesOnce() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-cancel-")
        val memory = PcmMemoryBudget(); val assets = FileAssetStore(directory.resolve("assets")); val pcm = WavPcmPort(assets, memory = memory)
        val native = GainFactory().apply { entered = CompletableDeferred(); release = CompletableDeferred() }
        val service = FourStemService(assets, pcm, directory.resolve("scratch"), native) { available }
        try {
            val original = source(directory, assets)
            val pending = async { service.prepare(original) }
            withTimeout(10_000) { native.entered!!.await() }
            val retained = memory.statistics().usedBytes
            assertTrue(retained >= FourStemSpec.PIPELINE_PCM_BYTES + FourStemSpec.NATIVE_IO_PCM_BYTES)
            service.cancel()
            assertEquals(SeparationProblem.BUSY, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
            assertEquals(retained, memory.statistics().usedBytes); assertEquals(0, native.closes.get())
            native.release!!.complete(Unit)
            assertEquals(SeparationProblem.CANCELLED, assertIs<SeparationResult.Failure>(withTimeout(10_000) { pending.await() }).failure.problem)
            assertEquals(1, native.cancels.get()); assertEquals(1, native.closes.get())
            assertEquals(original.byteCount, assets.storedBytes()); assertEquals(0L, Files.list(directory.resolve("scratch")).use { it.count() })
            service.close(); service.close()
            assertEquals(SeparationProblem.CLOSED, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
        } finally { native.release!!.complete(Unit); service.close(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes); assertEquals(1, native.closes.get())
    }

    @Test fun memoryRamQuotaAndMalformedOutputRemainTypedWithoutReplacingTheOriginal() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-refusal-")
        val memory = PcmMemoryBudget(32L shl 20); val assets = FileAssetStore(directory.resolve("assets")); val pcm = WavPcmPort(assets, memory = memory)
        val native = GainFactory(); var ram = available
        val service = FourStemService(assets, pcm, directory.resolve("scratch"), native) { ram }
        try {
            val original = source(directory, assets)
            ram = SeparationMemory(0, 0, false)
            assertEquals(SeparationProblem.RAM_UNAVAILABLE, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
            assertEquals(0, native.opens.get())
            ram = available
            assertEquals(SeparationProblem.PCM_LIMIT, assertIs<SeparationResult.Failure>(service.prepare(original)).failure.problem)
            assertEquals(1, native.closes.get()); assertTrue(assets.verified(original)); assertEquals(original.byteCount, assets.storedBytes())
            val limitedAssets = FileAssetStore(directory.resolve("limited"), original.byteCount + 10)
            val limitedOriginal = source(directory, limitedAssets)
            val limitedPcm = WavPcmPort(limitedAssets)
            val limited = FourStemService(limitedAssets, limitedPcm, directory.resolve("limited-scratch"), native) { available }
            try { assertEquals(SeparationProblem.NO_SPACE, assertIs<SeparationResult.Failure>(limited.prepare(limitedOriginal)).failure.problem) }
            finally { limited.close(); limitedPcm.close() }
            val amplePcm = WavPcmPort(assets)
            val broken = FourStemService(assets, amplePcm, directory.resolve("broken-scratch"), native.apply { invalid = true }) { available }
            try { assertEquals(SeparationProblem.INVALID_OUTPUT, assertIs<SeparationResult.Failure>(broken.prepare(original)).failure.problem) }
            finally { broken.close(); amplePcm.close() }
            assertTrue(assets.verified(original)); assertEquals(original.byteCount, assets.storedBytes())
        } finally { service.close(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes)
    }

    @Test fun pagedLongSourceStreamsEveryHeadWithBoundedPcmAndReleasesTheWorkerBudget() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-long-")
        val memory = PcmMemoryBudget(); val assets = FileAssetStore(directory.resolve("assets")); val pcm = WavPcmPort(assets, memory = memory)
        val native = GainFactory(); val service = FourStemService(assets, pcm, directory.resolve("scratch"), native) { available }
        try {
            val original = source(directory, assets, frames = 2_110_000)
            assertFalse(PcmResidency.resident(original))
            val prepared = assertIs<SeparationResult.Success<PreparedFourStems>>(service.prepare(original)).value
            val frames = (original.frames * 44_100 + 47_999) / 48_000
            assertTrue(native.calls.get() >= 7)
            assertTrue(prepared.stems.all { it.asset.frames == frames && assets.verified(it.asset) })
            assertTrue(assets.verified(original)); assertEquals(0L, Files.list(directory.resolve("scratch")).use { it.count() })
            assertEquals(PcmResidency.bytes(original), memory.statistics().usedBytes, "Only the shared idle source cache survives a preparation")
            assertTrue(memory.statistics().peakBytes < 80L * 1024 * 1024, "PCM peak is one model I/O and overlap window, not four complete stems")
            println("FOUR_STEM_SHARED_PCM sourceFrames=${original.frames} outputFrames=$frames chunks=${native.calls.get()} peakBytes=${memory.statistics().peakBytes} limitBytes=${memory.limitBytes}")
        } finally { service.close(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes)
    }

    @Test fun identicalSilentHeadsReuseContentWithoutAnInvalidSelfDerivedAsset() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("four-stem-silent-")
        val assets = FileAssetStore(directory.resolve("assets")); val pcm = WavPcmPort(assets)
        val service = FourStemService(assets, pcm, directory.resolve("scratch"), GainFactory()) { available }
        try {
            val original = source(directory, assets, 44_100, 44_100, silent = true)
            val prepared = assertIs<SeparationResult.Success<PreparedFourStems>>(service.prepare(original)).value
            assertTrue(prepared.stems.all { it.asset.hash == original.hash })
            val before = Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, original.frames)))
            val edit = assertIs<SeparationResult.Success<Intent.SetArrangement>>(prepared.placement(before, 0, StemMix.ALL, "stems")).value
            val after = com.choplab.core.edit.Reducer.reduce(before, edit).project
            assertEquals(4, after.clips.size); assertEquals(frozenListOf(original), after.assets)
            assertEquals(original.byteCount, assets.storedBytes())
        } finally { service.close(); pcm.close(); directory.toFile().deleteRecursively() }
    }
}
