package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class VocalPitchRendererTest {
    @Test fun originalAuditionPreservesFullAssetIdentityAndNativeSubrangeMatchesPlayback(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-original-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets"))
            WavPcmPort(store, memory = memory).use { pcm ->
                val renderer = VocalPitchRenderer(store, pcm, root.resolve("scratch"))
                for ((rate, range) in listOf(48_000 to FrameRange(0, 48_000), 44_100 to FrameRange(719, 41_123))) {
                    val source = put(store, 48_000, rate)
                    val before = project(source, range)
                    val originalBytes = store.read(source)
                    val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", settings)
                    val audition = renderer.original(before, draft, "Original audition")
                    if (rate == 48_000) assertEquals(source, audition, "Identical source bytes retain their ORIGINAL metadata")
                    else {
                        assertEquals(AssetRole.RENDERED, audition.role)
                        assertEquals(source.hash, audition.derivedFrom)
                    }
                    assertEquals(draft.frames48(source), audition.frames)
                    pcm.acquire(source).use { raw -> pcm.acquire(audition).use { normalized ->
                        memory.reserve(2 * 4096 * 8L).use {
                            val first = draft.firstFrame48(source).toInt()
                            var at = 0
                            while (at < audition.frames) {
                                val count = minOf(4096, audition.frames.toInt() - at)
                                assertContentEquals(pcm.readWindow(raw.pcm, first + at, first + at + count),
                                    pcm.readWindow(normalized.pcm, at, at + count), "Original A equals normalized playback at $at")
                                at += count
                            }
                        }
                    } }
                    assertContentEquals(originalBytes, store.read(source))
                    assertTrue(before.pitchCorrections.isEmpty())
                    assertNoScratch(root.resolve("scratch"))
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun productionWorkerApplyArchiveABAndExportRetainOriginalStereoAndUseTheSameGraph(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-archive-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets"))
            val source = put(store, 48_000)
            val before = project(source).copy(mix = MixSettings(masterGain = .8f),
                vocalComps = frozenListOf(), lyricStructure = LyricStructure("Song", com.choplab.core.ai.LyricLanguage.ENGLISH, frozenListOf()))
            val original = store.read(source)
            WavPcmPort(store, memory = memory).use { pcm ->
                val draft = VocalPitchEdits.draft(DocumentState(before, 3), "voice-clip", "correction", settings)
                val phases = mutableSetOf<PitchCorrectionPhase>()
                val renderer = VocalPitchRenderer(store, pcm, root.resolve("scratch"))
                val result = assertIs<VocalPitchRenderResult.Rendered>(renderer.render(before, draft, "Corrected") { phase, at, total ->
                    assertTrue(at in 0..total); assertEquals(48_000, total); phases += phase
                })
                assertEquals(PitchCorrectionPhase.entries.toSet(), phases)
                assertEquals(0, result.report.outputDelayFrames); assertEquals(4096, result.report.analysisWindowFrames)
                assertEquals(48_000, result.report.frames); assertTrue(result.report.correctedFrames > 40_000)
                val asset = result.asset
                assertEquals(source.hash, asset.derivedFrom); assertEquals(AssetRole.RENDERED, asset.role)
                val renderedBytes = store.read(asset)
                val output = WavCodec.read(ByteArrayInputStream(renderedBytes))
                assertEquals(WavInfo(48_000, 2, source.frames, 32, true), output.info)
                for (frame in 0 until source.frames.toInt()) assertEquals(-output.samples[frame * 2] * .5f, output.samples[frame * 2 + 1])
                assertTrue(abs(measuredFrequency(output.samples, 6000, 36_000) - 220.0) < .08)
                assertEquals(asset, assertIs<VocalPitchRenderResult.Rendered>(renderer.render(before, draft, "Corrected")).asset,
                    "Same immutable input/settings yield identical float asset bytes")
                val session = EditSession(before, 3)
                val plan = session.plan(VocalPitchEdits.apply(DocumentState(before, 3), draft, asset))
                plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
                assertEquals(1, session.undoCount)
                val corrected = session.project
                assertContentEquals(original, store.read(source))
                val archive = ByteArrayOutputStream().also { ArchiveCodec().write(corrected, store, it) }.toByteArray()
                val fresh = FileAssetStore(root.resolve("restored"))
                val restored = ArchiveCodec().read(ByteArrayInputStream(archive), fresh)
                assertEquals(corrected, restored); assertContentEquals(original, fresh.read(source))
                assertContentEquals(renderedBytes, fresh.read(asset)); assertEquals(before.takes, restored.takes)
                assertEquals(before.lyricStructure, restored.lyricStructure); assertEquals(before.mix, restored.mix)
                val ab = Reducer.reduce(restored, VocalPitchEdits.select(DocumentState(restored, 4), "correction", true)).project
                assertEquals(before.clips, ab.clips); assertTrue(ab.assets.contains(asset))
                WavPcmPort(fresh, memory = memory).use { reopened ->
                    val compiler = ProgramCompiler(reopened)
                    val program = compiler.compile(restored, PlaybackTarget.Arrangement(), 4)
                    try {
                        val count = source.frames.toInt()
                        val oracle = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(count.toLong(), 2)), count, blockFrames = 17)
                        val expected = ByteArrayOutputStream().also { WavCodec.writePcm(it, oracle, bits = 24, seed = 73) }.toByteArray()
                        val actual = ByteArrayOutputStream()
                        val receipt = StreamingWavRenderer.render(program, actual, count, bits = 24, seed = 73, blockFrames = 192,
                            prepared = { windows, render -> runBlocking { compiler.prepared(windows, render) } })
                        assertEquals(72, receipt.latencyFrames)
                        assertContentEquals(expected, actual.toByteArray(), "Archive reopen and 24-bit export must use the live arrangement graph")
                    } finally { program.releasePreparation() }
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun amountZeroProducesNoPublishedDerivedAsset(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-bypass-")
        try {
            val store = FileAssetStore(root.resolve("assets")); val source = put(store, 24_000)
            val before = project(source); val memory = PcmMemoryBudget()
            WavPcmPort(store, memory = memory).use { pcm ->
                val renderer = VocalPitchRenderer(store, pcm, root.resolve("scratch"))
                val result = assertIs<VocalPitchRenderResult.Unchanged>(renderer.render(before,
                    VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", settings.copy(amount = 0f)), "Bypass"))
                assertEquals(0, result.report.correctedFrames); assertEquals(source.byteCount, store.storedBytes())
                assertNoScratch(root.resolve("scratch")); assertEquals(0, memory.statistics().leasedAssets)
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancelAnalyzeRenderAndFailureReleaseScratchReservationAndKeepActiveOriginal(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-cancel-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets")); val source = put(store, 48_000)
            val before = project(source)
            val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", settings)
            WavPcmPort(store, memory = memory).use { pcm ->
                pcm.acquire(source).use { active ->
                    val baseline = memory.statistics().usedBytes
                    val renderer = VocalPitchRenderer(store, pcm, root.resolve("scratch"))
                    for (phase in PitchCorrectionPhase.entries) {
                        val job = launch {
                            renderer.render(before, draft, "Cancelled") { current, _, _ ->
                                if (phase == current) cancel("Cancel actual $phase work")
                            }
                        }
                        withTimeout(5000) { job.join() }; assertTrue(job.isCancelled)
                        assertNoScratch(root.resolve("scratch")); assertEquals(baseline, memory.statistics().usedBytes)
                        assertEquals(1, active.pcm.leaseCount); assertEquals(source.byteCount, store.storedBytes())
                        assertEquals(sample(500, 48_000), active.pcm.sample(500, 0))
                    }
                    assertFailsWith<IllegalStateException> { renderer.render(before, draft, "Failed") { phase, _, _ ->
                        if (phase == PitchCorrectionPhase.RENDER) error("Sink stopped")
                    } }
                    assertNoScratch(root.resolve("scratch")); assertEquals(baseline, memory.statistics().usedBytes)
                    assertTrue(store.verified(source)); assertEquals(1, active.pcm.leaseCount)
                    // The budget is an admission limit, never permission to evict an active original.
                    memory.reserve(memory.limitBytes - baseline).use {
                        assertEquals(VocalPitchProblem.LIMIT, assertFailsWith<VocalPitchException> {
                            renderer.render(before, draft, "No RAM")
                        }.problem)
                        assertFalse(active.pcm.evicted); assertEquals(1, active.pcm.leaseCount)
                    }
                    assertEquals(baseline, memory.statistics().usedBytes)
                    val noDisk = VocalPitchRenderer(store, pcm, root.resolve("scratch"), usableDiskBytes = { 64L shl 20 })
                    assertEquals(VocalPitchProblem.LIMIT, assertFailsWith<VocalPitchException> { noDisk.render(before, draft, "No disk") }.problem)
                    PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).use {
                        assertEquals(VocalPitchProblem.LIMIT, assertFailsWith<VocalPitchException> { renderer.render(before, draft, "No scratch") }.problem)
                    }
                    assertNoScratch(root.resolve("scratch")); assertEquals(source.byteCount, store.storedBytes())
                    assertIs<VocalPitchRenderResult.Rendered>(renderer.render(before, draft, "Retry"))
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun lateMixedRateRangeUsesCompilerFrameMappingAndBitExactBypassAtBothEdges(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-native-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets")); val source = put(store, 2_200_000, 44_100)
            val before = project(source, FrameRange(2_150_003, 2_190_004))
            val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", settings)
            WavPcmPort(store, memory = memory).use { pcm ->
                pcm.acquire(source).use { original ->
                    assertNotNull(original.pcm.pages)
                    val first = draft.firstFrame48(source).toInt()
                    val frames = draft.frames48(source).toInt()
                    val output = assertIs<VocalPitchRenderResult.Rendered>(VocalPitchRenderer(store, pcm, root.resolve("scratch")).render(before, draft, "Native range"))
                    assertEquals(frames.toLong(), output.asset.frames)
                    pcm.acquire(output.asset).use { corrected ->
                        memory.reserve(4096 * 8L).use {
                            for (at in listOf(0, frames - 128)) {
                                val a = pcm.readWindow(original.pcm, first + at, first + at + 128)
                                val b = pcm.readWindow(corrected.pcm, at, at + 128)
                                assertTrue(a.contentEquals(b), "Raw edge range at $at uses the same exact-adjacency ceil as playback")
                            }
                        }
                    }
                    assertTrue(store.verified(source)); assertEquals(1, original.pcm.leaseCount)
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun fourHundredSecondCorrectionIsBoundedAndReadsLateStereoWithoutLoadingWholeSource(): Unit = runBlocking {
        val root = Files.createTempDirectory("pitch-long-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets")); val source = put(store, 400 * 48_000)
            val before = project(source)
            val draft = VocalPitchEdits.draft(DocumentState(before, 0), "voice-clip", "pitch", settings)
            WavPcmPort(store, memory = memory).use { pcm ->
                pcm.acquire(source).use { active ->
                    assertNotNull(active.pcm.pages)
                    val start = System.nanoTime()
                    val result = assertIs<VocalPitchRenderResult.Rendered>(VocalPitchRenderer(store, pcm, root.resolve("scratch")).render(before, draft, "400 seconds"))
                    val elapsed = System.nanoTime() - start
                    val stats = memory.statistics()
                    assertEquals(source.frames, result.asset.frames); assertEquals(source.byteCount, result.asset.byteCount)
                    assertTrue(result.report.correctedFrames > source.frames - 10_000)
                    assertTrue(result.report.workspaceBytes < 2L * 1024 * 1024)
                    assertTrue(stats.peakBytes <= PcmResidency.bytes(source) + VocalPitchRenderer.workspaceBytes(source.frames.toInt()))
                    assertTrue(stats.peakBytes <= EngineFormat.MAX_RESIDENT_BYTES)
                    pcm.acquire(result.asset).use { correction ->
                        memory.reserve(2 * 4096 * 8L).use {
                            val late = pcm.readWindow(correction.pcm, 399 * 48_000, 399 * 48_000 + 4096)
                            assertTrue(late.all { it.isFinite() })
                            for (frame in 0 until 4096) assertEquals(-late[frame * 2] * .5f, late[frame * 2 + 1])
                            assertTrue(abs(measuredFrequency(late, 0, 4096) - 220.0) < .2)
                            val edge = pcm.readWindow(correction.pcm, correction.pcm.frameCount - 128, correction.pcm.frameCount)
                            val original = pcm.readWindow(active.pcm, active.pcm.frameCount - 128, active.pcm.frameCount)
                            assertTrue(edge.contentEquals(original))
                        }
                    }
                    assertTrue(store.verified(source)); assertNoScratch(root.resolve("scratch"))
                    println("PITCH_400S elapsedNs=$elapsed sourceBytes=${source.byteCount} outputBytes=${result.asset.byteCount} " +
                        "workspace=${result.report.workspaceBytes} workerReservation=${VocalPitchRenderer.workspaceBytes(source.frames.toInt())} " +
                        "correctionPcmPeak=${stats.peakBytes} afterABReadPcmPeak=${memory.statistics().peakBytes} pcmLimit=${stats.limitBytes} " +
                        "correctedFrames=${result.report.correctedFrames} delay=${result.report.outputDelayFrames}")
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
            println("PITCH_400S shutdownPcm=0")
        } finally { root.toFile().deleteRecursively() }
    }

    private val settings = PitchCorrectionSettings(retuneMs = 0f, vibrato = 0f)
    private fun project(source: Asset, range: FrameRange = FrameRange(0, source.frames)) = Project(assets = frozenListOf(source),
        tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
        clips = frozenListOf(Clip("voice-clip", "voice", source.hash, range)),
        takes = frozenListOf(Take("raw", "voice", source.hash, FrameRange(0, source.frames), 0)))
    private fun sample(frame: Int, rate: Int) = (.13 * sin(2 * PI * (220 * 2.0.pow(32.0 / 1200)) * frame / rate)).toFloat()
    private fun put(store: FileAssetStore, frames: Int, rate: Int = 48_000): Asset {
        val pending = Files.createTempFile(store.directory.parent, "source-", ".wav")
        try {
            Files.newOutputStream(pending).buffered().use { stream ->
                val writer = WavCodec.FloatWriter(stream, frames.toLong(), rate)
                val buffer = FloatArray(8192)
                var at = 0
                while (at < frames) {
                    val count = minOf(4096, frames - at)
                    repeat(count) { i -> val value = sample(at + i, rate); buffer[i * 2] = value; buffer[i * 2 + 1] = -value * .5f }
                    writer.write(buffer, frameCount = count); at += count
                }
                writer.finish()
            }
            val bytes = Files.size(pending)
            val hash = Files.newInputStream(pending).use { digest(it, bytes) }
            return Asset(hash, "wav", bytes, rate, 2, frames.toLong(), "Original").also { store.adopt(it, pending) }
        } finally { Files.deleteIfExists(pending) }
    }
    private fun measuredFrequency(samples: FloatArray, start: Int, frames: Int): Double {
        val crossings = (start + 1 until start + frames).filter { samples[(it - 1) * 2] <= 0 && samples[it * 2] > 0 }.map { frame ->
            val a = samples[(frame - 1) * 2]; val b = samples[frame * 2]
            frame - 1.0 - a / (b - a)
        }
        return (crossings.size - 1) * 48_000.0 / (crossings.last() - crossings.first())
    }
    private fun assertNoScratch(path: Path) = assertEquals(0L, Files.list(path).use { it.count() })
}
