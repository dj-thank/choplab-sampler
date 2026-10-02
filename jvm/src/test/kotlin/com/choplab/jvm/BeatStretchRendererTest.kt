package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.test.*

class BeatStretchRendererTest {
    @Test fun nativeRangesABBypassOriginalBytesAndArchivePlaybackExportUseTheSameResult(): Unit = runBlocking {
        val root = Files.createTempDirectory("stretch-roundtrip-"); val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets"))
            WavPcmPort(store, memory = memory).use { pcm ->
                val worker = BeatStretchRenderer(store, pcm, root.resolve("scratch"))
                for (rate in listOf(44_100, 48_000, 96_000)) {
                    val source = put(store, rate); val original = store.read(source)
                    val project = project(source, FrameRange(13, source.frames - 19))
                    val doc = DocumentState(project, 9); val draft = BeatStretchEdits.draft(doc, target, 120_000)
                    val normalized = worker.original(project, draft, "A")
                    pcm.acquire(source).use { raw -> pcm.acquire(normalized).use { a ->
                        memory.reserve(2L * 4096 * 8).use {
                            val first = stretchFirstFrame(source, draft.sourceRange).toInt()
                            assertContentEquals(pcm.readWindow(raw.pcm, first, first + 4096), pcm.readWindow(a.pcm, 0, 4096))
                        }
                    } }
                    val result = worker.render(project, draft, "Stretched")
                    assertEquals(stretchFrames(source, draft.sourceRange, 120_000, 150_000), result.frames)
                    assertEquals(source.hash, result.derivedFrom); assertContentEquals(original, store.read(source))
                    assertEquals(result, worker.render(project, draft, "Stretched"), "Deterministic float bytes")
                    val rendered = WavCodec.read(ByteArrayInputStream(store.read(result)))
                    assertTrue(rendered.samples.any { abs(it) > 1f }, "Float headroom is retained")
                    for (i in 0 until rendered.info.frames.toInt()) assertEquals(-.5f * rendered.samples[i * 2], rendered.samples[i * 2 + 1], 1e-6f)
                    val after = Reducer.reduce(project, BeatStretchEdits.apply(doc, draft, result)).project
                    val archive = ByteArrayOutputStream().also { ArchiveCodec().write(after, store, it) }.toByteArray()
                    val fresh = FileAssetStore(root.resolve("restored-$rate"))
                    val restored = ArchiveCodec().read(ByteArrayInputStream(archive), fresh)
                    assertEquals(after, restored); assertContentEquals(original, fresh.read(source)); assertContentEquals(store.read(result), fresh.read(result))
                    WavPcmPort(fresh, memory = memory).use { reopened ->
                        val compiler = ProgramCompiler(reopened)
                        val program = compiler.compile(restored, PlaybackTarget.Arrangement(), 10)
                        try {
                            val frames = result.frames.toInt()
                            val oracle = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(frames.toLong(), 2)), frames, blockFrames = 17)
                            val expected = ByteArrayOutputStream().also { WavCodec.writePcm(it, oracle, bits = 24, seed = 73) }.toByteArray()
                            val exported = ByteArrayOutputStream()
                            StreamingWavRenderer.render(program, exported, frames, bits = 24, seed = 73, blockFrames = 192,
                                prepared = { windows, render -> runBlocking { compiler.prepared(windows, render) } })
                            assertContentEquals(expected, exported.toByteArray())
                        } finally { program.releasePreparation() }
                    }
                    val bypassProject = project.copy(tempo = Tempo(120_000))
                    val bypass = BeatStretchEdits.draft(DocumentState(bypassProject, 0), target, 120_000)
                    val bytesBefore = store.storedBytes()
                    assertEquals(source, worker.render(bypassProject, bypass, "Bypass")); assertEquals(bytesBefore, store.storedBytes())
                    assertNoScratch(root.resolve("scratch"))
                }
            }
            assertEquals(0, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationFailureBusyAndSharedRamDiskStoreAdmissionKeepTheActiveOriginal(): Unit = runBlocking {
        val root = Files.createTempDirectory("stretch-cancel-"); val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets")); val source = put(store, 48_000)
            val project = project(source); val draft = BeatStretchEdits.draft(DocumentState(project, 0), target, 120_000)
            WavPcmPort(store, memory = memory).use { pcm -> pcm.acquire(source).use { active ->
                val baseline = memory.statistics().usedBytes
                val worker = BeatStretchRenderer(store, pcm, root.resolve("scratch"))
                val entered = CountDownLatch(1); val released = CountDownLatch(1)
                val pending = launch(Dispatchers.Default) { worker.render(project, draft, "Cancel") { _, _ -> entered.countDown(); released.await(5, TimeUnit.SECONDS) } }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                    pending.cancel()
                    assertEquals(StretchProblem.BUSY, assertFailsWith<StretchException> { worker.render(project, draft, "Busy") }.problem)
                    assertTrue(memory.statistics().usedBytes >= baseline + BeatStretchRenderer.WORKSPACE_BYTES)
                } finally { released.countDown(); withTimeout(5000) { pending.join() } }
                assertNoScratch(root.resolve("scratch")); assertEquals(baseline, memory.statistics().usedBytes)
                assertEquals(StretchProblem.FAILED, assertFailsWith<StretchException> { worker.render(project, draft, "Fail") { _, _ -> error("Writer failed") } }.problem)
                assertNoScratch(root.resolve("scratch")); assertEquals(source.byteCount, store.storedBytes())
                memory.reserve(memory.limitBytes - baseline).use {
                    assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { worker.render(project, draft, "No RAM") }.problem)
                    assertFalse(active.pcm.evicted)
                }
                val disk = BeatStretchRenderer(store, pcm, root.resolve("disk"), usableDiskBytes = { 0 })
                assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { disk.render(project, draft, "No disk") }.problem)
                val quotaStore = FileAssetStore(root.resolve("quota"), maxStoredBytes = source.byteCount)
                quotaStore.publish(source, ByteArrayInputStream(store.read(source)))
                val quota = BeatStretchRenderer(quotaStore, pcm, root.resolve("quota-scratch"))
                assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { quota.render(project, draft, "Quota") }.problem)
                val scratch = PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES)
                try { assertEquals(StretchProblem.LIMIT, assertFailsWith<StretchException> { worker.render(project, draft, "No scratch") }.problem) }
                finally { scratch.close() }
                assertTrue(store.verified(source)); assertEquals(1, active.pcm.leaseCount)
            } }
            assertEquals(0, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun fourHundredSecondsStreamThroughTheSharedBudgetWithoutResidentDecode(): Unit = runBlocking {
        val root = Files.createTempDirectory("stretch-long-")
        val memory = PcmMemoryBudget(24L * 1024 * 1024)
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder()
        try {
            val store = FileAssetStore(root.resolve("assets"), decoder = decoder)
            val bytes = byteArrayOf(7)
            val source = Asset(sha256(bytes), "flac", 1, 48_000, 2, 400L * 48_000, "Long original")
            store.publish(source, ByteArrayInputStream(bytes))
            WavPcmPort(store, decoder = decoder, memory = memory).use { pcm ->
                val project = project(source).copy(tempo = Tempo(160_000))
                val draft = BeatStretchEdits.draft(DocumentState(project, 0), target, 120_000)
                val worker = BeatStretchRenderer(store, pcm, root.resolve("scratch"))
                val result = worker.render(project, draft, "Long stretch")
                assertEquals(300L * 48_000, result.frames); assertEquals(0, decoder.residentDecodes.get())
                assertTrue(memory.statistics().peakBytes <= memory.limitBytes)
                assertContentEquals(bytes, store.read(source)); assertNoScratch(root.resolve("scratch"))
                println("BEAT_STRETCH_LONG inputSeconds=400 outputSeconds=300 blockMax=4096 workspaceBytes=${BeatStretchRenderer.WORKSPACE_BYTES} peak=${memory.statistics().peakBytes} limit=${memory.limitBytes} residentDecodes=0")
            }
            assertEquals(0, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    private val target = StretchTarget(StretchKind.CLIP, "clip")
    private fun project(source: Asset, range: FrameRange = FrameRange(0, source.frames)) = Project(assets = frozenListOf(source),
        tracks = frozenListOf(Track("track", "Beat", TrackKind.BANK)), clips = frozenListOf(Clip("clip", "track", source.hash, range)), tempo = Tempo(150_000))
    private fun put(store: FileAssetStore, rate: Int): Asset {
        val samples = FloatArray(rate * 2) { (1.2 * sin(2 * PI * 220 * (it / 2) / rate) * if (it % 2 == 0) 1.0 else -.5).toFloat() }
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples, sampleRate = rate) }.toByteArray()
        return Asset(sha256(bytes), "wav", bytes.size.toLong(), rate, 2, rate.toLong(), "Original $rate").also { store.publish(it, ByteArrayInputStream(bytes)) }
    }
    private fun assertNoScratch(path: Path) { if (Files.exists(path)) Files.list(path).use { assertEquals(0, it.count()) } }
}
