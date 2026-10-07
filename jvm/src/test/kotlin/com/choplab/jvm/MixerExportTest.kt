package com.choplab.jvm

import com.choplab.core.persistence.*
import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class MixerExportTest {
    private class CountedPcm(val port: WavPcmPort) : PrefetchPcmPort by port {
        var acquired = 0
        override suspend fun acquire(asset: Asset): PcmLease { acquired++; return port.acquire(asset) }
    }
    private class Fixture(val root: Path, val project: Project, val store: FileAssetStore, val pcm: CountedPcm) : AutoCloseable {
        val compiler = ProgramCompiler(pcm)
        val output = root.resolve("stems.zip")
        val exporter = FileStemExportPort(compiler) { output }
        override fun close() { pcm.port.close(); root.toFile().deleteRecursively() }
    }
    private suspend fun fixture(level: Float = .05f): Fixture {
        val root = Files.createTempDirectory("mixer-export-")
        val store = FileAssetStore(root.resolve("assets"))
        val bytes = ByteArrayOutputStream().also { output ->
            WavCodec.writeFloat(output, FloatArray(2048 * 2) { i ->
                if (i % 2 == 0) level * cos((i / 2) * .031).toFloat() else -level * .23f * sin((i / 2) * .043).toFloat()
            })
        }.toByteArray()
        val asset = Fixtures.asset(bytes); store.write(asset, bytes)
        val track = Track("first", "Left / right", TrackKind.SOURCE, .8f, -.2f,
            fx = TrackFx(MixInsert(MixEq(2f, -3f, 4f), MixFilter(MixFilterMode.LOW_PASS, 14000f), MixCompressor(true, -24f)), .5f, .3f))
        val second = Track("second", "Second", TrackKind.VOCAL, .5f, .3f,
            fx = TrackFx(MixInsert(MixEq(-1f, 2f, 0f)), .2f, .5f))
        val p = Fixtures.project(asset).copy(tracks = frozenListOf(track, second), clips = frozenListOf(
            Clip("one", track.id, asset.hash, FrameRange(0, asset.frames), timelineStartFrame = 0),
            Clip("two", second.id, asset.hash, FrameRange(0, asset.frames), timelineStartFrame = 257)),
            mix = MixSettings(MixDelay(true, 137, .3f, .4f), MixReverb(true, .1f, .2f, .2f),
                MixInsert(compressor = MixCompressor(true, -30f, 3f)), .7f))
        return Fixture(root, p, store, CountedPcm(WavPcmPort(store)))
    }
    private fun entries(path: Path): Map<String, ByteArray> = Fixtures.unzip(Files.readAllBytes(path)).toMap()
    private fun decode(bytes: ByteArray) = WavCodec.read(ByteArrayInputStream(bytes)).samples
    private fun noPending(root: Path) = Files.list(root).use { paths ->
        assertTrue(paths.noneMatch { it.fileName.toString().endsWith(".pending") })
    }

    @Test fun sequentialProductionStemsShareOnePreparationAndTheirSumMatchesThePreMasterGraph(): Unit = runBlocking {
        fixture().use { f ->
            val before = f.store.read(f.project.assets.first())
            val progress = mutableListOf<StemExportProgress>()
            val start = System.nanoTime()
            val receipt = f.exporter.export(f.project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 4096), progress::add)
            val elapsed = System.nanoTime() - start
            assertEquals(1, f.pcm.acquired, "Each pass reuses the one prepared PCM lease")
            assertEquals(listOf("first", "second", "delay-return", "reverb-return"), receipt.files.map { it.busId })
            assertEquals(StemOutputPoint.POST_FADER_POST_INSERT_PRE_MASTER, receipt.outputPoint)
            val zip = entries(f.output)
            assertEquals((listOf("manifest.json") + receipt.files.map { it.fileName }).toSet(), zip.keys)
            assertTrue(receipt.files.all { it.fileName.matches(Regex("stem-[0-9]{2}\\.wav")) })
            val manifest = ProjectJson.parse(zip.getValue("manifest.json")).jsonObject
            assertEquals(receipt.frames, manifest.getValue("frames").jsonPrimitive.long)
            assertEquals(receipt.outputPoint.name, manifest.getValue("point").jsonPrimitive.content)
            assertEquals(4, manifest.getValue("files").jsonArray.size)
            assertEquals(StemExportPhase.COMPLETE, progress.last().phase)
            assertTrue(progress.zipWithNext().all { (a, b) -> b.completedStems >= a.completedStems })
            for (index in 0..3) {
                val pass = progress.filter { it.phase == StemExportPhase.RENDERING && it.completedStems == index }
                assertEquals(0, pass.first().renderedFrames); assertEquals(receipt.frames, pass.last().renderedFrames)
                assertTrue(pass.zipWithNext().all { (a, b) -> a.renderedFrames < b.renderedFrames })
            }
            val stems = receipt.files.map { file ->
                val bytes = zip.getValue(file.fileName)
                val info = WavCodec.inspect(ByteArrayInputStream(bytes))
                assertEquals(WavInfo(48_000, 2, receipt.frames, 32, true), info)
                assertEquals(44L + receipt.frames * 8, bytes.size.toLong())
                decode(bytes)
            }
            val masterBypassed = f.project.copy(mix = f.project.mix.copy(master = MixInsert(), masterGain = 1f))
            val program = f.compiler.compile(masterBypassed, PlaybackTarget.Arrangement(), 1)
            try {
                assertEquals(4096L + program.mixer.tailFrames, receipt.frames)
                val oracle = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(4096, 2)),
                    4096, program.mixer.tailFrames, 17)
                for (sample in oracle.indices) assertEquals(oracle[sample].toDouble(), stems.sumOf { it[sample].toDouble() }, 3e-8)
                assertTrue(stems[2].any { it != 0f }); assertTrue(stems[3].any { it != 0f })
                assertTrue(stems.all { stem -> stem.takeLast(480 * 2).all { it == 0f } })
            } finally { program.releasePreparation() }
            val peak = PcmMemoryBudget.shared.statistics()
            assertTrue(peak.peakBytes <= peak.limitBytes)
            println("MIXER_STEM_EXPORT passes=${receipt.files.size} framesPerPass=${receipt.frames} elapsedNs=$elapsed " +
                "globalPeak=${peak.peakBytes} globalLimit=${peak.limitBytes} privateDecodedStemScratch=0")
            assertContentEquals(before, f.store.read(f.project.assets.first())); noPending(f.root)
        }
    }

    @Test fun integerStemsUseOneSeededBoundaryAndAreIndependentOfRenderBlockSize(): Unit = runBlocking {
        fixture().use { f ->
            val program = f.compiler.compile(f.project, PlaybackTarget.Arrangement(), 1)
            try {
                val floating = ByteArrayOutputStream()
                StreamingStemRenderer.render(program, listOf(0 to floating), 3000, 200, blockFrames = 17)
                val samples = decode(floating.toByteArray())
                for (format in listOf(StemSampleFormat.PCM16, StemSampleFormat.PCM24)) {
                    val oracle = ByteArrayOutputStream().also { WavCodec.writePcm(it, samples, bits = format.bits, seed = 997) }.toByteArray()
                    for (block in listOf(1, 192, 4096)) {
                        val result = ByteArrayOutputStream().also {
                            StreamingStemRenderer.render(program, listOf(0 to it), 3000, 200, format, seed = 997, blockFrames = block)
                        }.toByteArray()
                        assertContentEquals(oracle, result, "$format block=$block")
                    }
                }
                val exact = f.exporter.export(f.project, PlaybackTarget.Arrangement(),
                    StemExportRequest(Location("zip"), 3000, 200, tailMode = ExportTailMode.EXACT))
                assertEquals(3200, exact.frames)
            } finally { program.releasePreparation() }
        }
    }

    @Test fun selectedTrackStemsKeepTheCompiledMuteSoloAndPanRules(): Unit = runBlocking {
        fixture().use { f ->
            for (project in listOf(f.project,
                f.project.copy(tracks = f.project.tracks.mapIndexed { index, track -> track.copy(mute = index == 0) }.frozen()),
                f.project.copy(tracks = f.project.tracks.mapIndexed { index, track -> track.copy(solo = index == 0) }.frozen()))) {
                val program = f.compiler.compile(project, PlaybackTarget.Arrangement(), 1)
                try {
                    val buses = (0 until MixerProgram.MAX_BUSES).filter { program.mixer.busId(it) != null } +
                        listOf(MixerProgram.DELAY_RETURN, MixerProgram.REVERB_RETURN)
                    val reference = buses.map { it to ByteArrayOutputStream() }
                    StreamingStemRenderer.render(program, reference, 4096, program.mixer.tailFrames, blockFrames = 17)
                    for ((bus, expected) in reference) {
                        val actual = ByteArrayOutputStream()
                        StreamingStemRenderer.render(program, listOf(bus to actual), 4096, program.mixer.tailFrames, blockFrames = 192)
                        assertContentEquals(expected.toByteArray(), actual.toByteArray(), "compiled bus=$bus")
                    }
                } finally { program.releasePreparation() }
            }
        }
    }

    @Test fun productionWavUsesTheSameMasterGraphTailAndSingleIntegerBoundary(): Unit = runBlocking {
        fixture().use { f ->
            val program = f.compiler.compile(f.project, PlaybackTarget.Arrangement(), 1)
            try {
                val path = f.root.resolve("mix.wav")
                val exporter = WavExportPort(f.compiler) { path }
                for ((bits, mode, explicitTail) in listOf(Triple(24, ExportTailMode.INCLUDE_GRAPH_TAIL, 0), Triple(16, ExportTailMode.EXACT, 192))) {
                    val tail = if (mode == ExportTailMode.EXACT) explicitTail else program.mixer.tailFrames
                    val samples = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1), EngineCommand.Stop(4096, 2)), 4096, tail, 17)
                    val expected = ByteArrayOutputStream().also { WavCodec.writePcm(it, samples, bits = bits, seed = 765) }.toByteArray()
                    val receipt = exporter.export(f.project, PlaybackTarget.Arrangement(), ExportRequest(Location("wav"), 4096, explicitTail, bits, 765, mode))
                    assertEquals(4096L + tail, receipt.frames)
                    assertContentEquals(expected, Files.readAllBytes(path))
                    assertTrue(samples.all { abs(it) <= MasterLimiter.CEILING.toFloat() })
                }
            } finally { program.releasePreparation() }
        }
    }

    @Test fun liveDriverMeterKeepsItsBusIdentityWhenAProgramChangesBusOrder(): Unit = runBlocking {
        fixture().use { f ->
            val project = f.project.copy(tracks = f.project.tracks.map { if (it.id == "first") it.copy(kind = TrackKind.BANK) else it }.frozen(),
                banks = f.project.banks.map { if (it.id == 0) it.copy(trackId = "first") else it }.frozen(),
                pads = f.project.pads.map { if (it.id == 0) it.copy(mode = PlayMode.LOOP) else it }.frozen())
            val driver = StreamingEnginePort(f.compiler, { object : AudioSink {
                override val encoding = SinkEncoding.FLOAT32
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int { Thread.sleep(1); return length }
                override fun close() = Unit
            } })
            try {
                withTimeout(10_000) { while (driver.status.value.phase != DriverPhase.ATTACHED) delay(1) }
                val program = driver.prepare(project, "pattern-1", 1)
                try { assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program))) }
                finally { program.releasePreparation() }
                assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
                val meter = MixerSnapshot()
                withTimeout(10_000) { while (!driver.copyMixerReadout(meter) || meter.peak[0] == 0f) delay(1) }
                assertEquals("first", meter.program.busId(0))
                val before = meter.frame
                val reordered = MixerProgram(listOf(TrackFx(), project.tracks.first().fx), project.mix, listOf("inserted", "first"))
                // An already sounding PAD still belongs to its original bus, even if its slot moves.
                assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 3, EngineProgram(revision = 2, mixer = reordered))))
                withTimeout(10_000) { while (!driver.copyMixerReadout(meter) || meter.program !== reordered || meter.peak[2] == 0f) delay(1) }
                assertTrue(meter.frame > before); assertEquals(0f, meter.peak[0]); assertTrue(meter.rms[2] > 0f)
            } finally { driver.close() }
        }
    }

    @Test fun preMasterHeadroomRefusalAndCancellationNeverReplaceAnExistingArchive(): Unit = runBlocking {
        fixture(8f).use { f ->
            val project = f.project.copy(tracks = f.project.tracks.map { it.copy(fx = TrackFx(), gain = 1f, pan = 0f) }.frozen(), mix = MixSettings())
            val previous = "previous export".toByteArray()
            Files.write(f.output, previous)
            for (format in listOf(StemSampleFormat.PCM16, StemSampleFormat.PCM24)) {
                val failure = assertFailsWith<StemHeadroomExceeded> {
                    f.exporter.export(project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 4096, format = format))
                }
                assertEquals(0, failure.bus); assertTrue(failure.peak > 1f)
                assertContentEquals(previous, Files.readAllBytes(f.output)); noPending(f.root)
            }
            val floating = f.exporter.export(project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 4096))
            val output = entries(f.output)
            assertTrue(decode(output.getValue(floating.files.first().fileName)).maxOf { abs(it) } > 1f)
            Files.write(f.output, previous)
            assertFailsWith<CancellationException> {
                f.exporter.export(f.project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 4096)) {
                    if (it.completedStems == 1 && it.renderedFrames > 0) throw CancellationException("second pass cancelled")
                }
            }
            assertContentEquals(previous, Files.readAllBytes(f.output)); noPending(f.root)
            assertEquals(0, PcmMemoryBudget.shared.statistics().leasedAssets)
        }
    }

    @Test fun latePagedStemReadsArePreparedAndDecodeFailureCannotPublishSilence(): Unit = runBlocking {
        val root = Files.createTempDirectory("mixer-long-stems-")
        val decoder = GlobalPcmBudgetTest.SyntheticDecoder()
        val store = FileAssetStore(root.resolve("assets"), decoder = decoder)
        val encoded = byteArrayOf(1)
        val asset = Asset(sha256(encoded), "flac", 1, 48_000, 2, 400L * 48_000, "fixture")
        store.publish(asset, ByteArrayInputStream(encoded))
        val late = 375L * 48_000
        val project = Project(assets = frozenListOf(asset), tracks = frozenListOf(Track("late", "Late", TrackKind.SOURCE)),
            clips = frozenListOf(Clip("late", "late", asset.hash, FrameRange(late, late + 9000), timelineStartFrame = 0)))
        val destination = root.resolve("stems.zip")
        WavPcmPort(store, decoder = decoder).use { pcm ->
            val compiler = ProgramCompiler(pcm)
            val exporter = FileStemExportPort(compiler) { destination }
            try {
                val receipt = exporter.export(project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 9000))
                val samples = decode(entries(destination).getValue(receipt.files.single().fileName))
                for (i in samples.indices) assertEquals(GlobalPcmBudgetTest.SyntheticDecoder.sample((late + i / 2).toInt(), i % 2, 1), samples[i])
                assertEquals(1, decoder.opens.get()); assertEquals(0, decoder.residentDecodes.get())
                val previous = Files.readAllBytes(destination)
                val failing = object : PrefetchPcmPort by pcm {
                    override suspend fun <T> prepared(windows: List<PcmWindow>, render: () -> T): T {
                        error("decoder unavailable before render")
                    }
                }
                assertFailsWith<IllegalStateException> {
                    FileStemExportPort(ProgramCompiler(failing)) { destination }.export(project, PlaybackTarget.Arrangement(), StemExportRequest(Location("zip"), 9000))
                }
                assertContentEquals(previous, Files.readAllBytes(destination)); noPending(root)
                assertContentEquals(encoded, store.read(asset))
            } finally { /* pcm.use owns active worker/cache disposal. */ }
        }
        assertEquals(decoder.opens.get(), decoder.closes.get())
        root.toFile().deleteRecursively()
    }
}
