package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.*
import kotlin.math.abs
import kotlin.test.*

class WavAndLegacyTest {
    @Test fun floatHeadroomChannelsAndIntegerBoundariesRoundTrip() {
        val source = floatArrayOf(-1f, 0.5f, 1.25f, -0.125f, 0f, 1f)
        val floating = ByteArrayOutputStream().also { WavCodec.writeFloat(it, source) }.toByteArray()
        val decoded = WavCodec.read(ByteArrayInputStream(floating))
        assertContentEquals(source, decoded.samples)
        assertEquals(WavInfo(48_000, 2, 3, 32, true), decoded.info)
        for (bits in listOf(16, 24)) {
            val bytes = ByteArrayOutputStream().also { WavCodec.writePcm(it, source, bits = bits, dither = false) }.toByteArray()
            val pcm = WavCodec.read(ByteArrayInputStream(bytes))
            val step = 1.0 / (if (bits == 16) 32768 else 8388608)
            source.indices.forEach { index -> assertTrue(abs(pcm.samples[index] - source[index].coerceIn(-1f, 1f)) <= step + 1e-8) }
            assertEquals(-1f, pcm.samples[0])
            assertTrue(pcm.samples[2] < 1f)
        }
        val mono24 = ByteArrayOutputStream().also { WavCodec.writePcm(it, floatArrayOf(0.25f), channels = 1, bits = 24, dither = false) }.toByteArray()
        assertEquals(48, mono24.size) // odd data byte count includes RIFF padding.
        assertEquals(0.25f, WavCodec.read(ByteArrayInputStream(mono24)).samples.single())
    }

    @Test fun seededDitherIsDeterministicUnbiasedAndOnlyAtIntegerBoundary() {
        val source = FloatArray(40_000)
        fun render(seed: Int) = ByteArrayOutputStream().also { WavCodec.writePcm(it, source, bits = 16, seed = seed) }.toByteArray()
        val first = render(71)
        assertContentEquals(first, render(71))
        assertFalse(first.contentEquals(render(72)))
        val pcm = WavCodec.read(ByteArrayInputStream(first)).samples
        val quantized = pcm.map { it * 32768f }
        assertTrue(quantized.min() >= -1f && quantized.max() <= 1f)
        assertTrue(abs(quantized.average()) < 0.02)
        assertTrue(quantized.count { it != 0f } in 8000..12000)
    }

    @Test fun malformedWavAndOversizedDecodeFailBeforeAllocation() {
        val source = Fixtures.wav()
        assertFailsWith<IllegalArgumentException> { WavCodec.read(ByteArrayInputStream(source), maxDecodedBytes = 4) }
        assertFailsWith<IllegalArgumentException> { WavCodec.read(ByteArrayInputStream(source.copyOf(source.size - 1))) }
        assertFailsWith<IllegalArgumentException> { WavCodec.read(ByteArrayInputStream(source + byteArrayOf(0))) }
        assertFailsWith<IllegalArgumentException> { WavCodec.writeFloat(ByteArrayOutputStream(), floatArrayOf(Float.NaN, 1f)) }
        val float = ByteArrayOutputStream().also { WavCodec.writeFloat(it, floatArrayOf(0f, 0f)) }.toByteArray()
        float[44] = 0; float[45] = 0; float[46] = 0x80.toByte(); float[47] = 0x7f
        assertFailsWith<IllegalArgumentException> { WavCodec.inspect(ByteArrayInputStream(float)) }
        val wrongChannels = source.copyOf().also { it[22] = 3 }
        assertFailsWith<IllegalArgumentException> { WavCodec.inspect(ByteArrayInputStream(wrongChannels)) }
    }

    @Test fun eachIndependentLegacyFixtureRescuesExactAudioAndPreservesInput() {
        for (schema in 1..7) {
            val manifest = requireNotNull(javaClass.getResourceAsStream("/legacy/schema-$schema.txt")).use { it.readBytes() }
            val channels = if (schema == 7) 2 else 1
            val samples = if (schema == 7) shortArrayOf(100, -200, 300, -400, 500, -600, 700, -800) else shortArrayOf(100, -200, 300, -400)
            val wav = Fixtures.wav(channels, samples = samples)
            val sourceBytes = if (schema == 1) wav.copyOfRange(44, wav.size) else wav
            val entry = "audio/0.${if (schema == 1) "pcm" else "wav"}"
            val archive = Fixtures.zip(listOf("project.txt" to manifest, entry to sourceBytes))
            val inputHash = sha256(archive)
            val store = FileAssetStore(Files.createTempDirectory("legacy-$schema-"))
            val result = LegacySalvage().read(ByteArrayInputStream(archive), store)
            assertEquals(schema, result.schema)
            val rescued = result.audio.single()
            assertEquals(42L, rescued.originalId)
            assertEquals(sha256(sourceBytes), rescued.sourceHash)
            assertEquals(4L, rescued.asset.frames)
            assertEquals(channels, rescued.asset.channels)
            assertEquals(sha256(wav), rescued.asset.hash)
            val restored = store.openVerified(rescued.asset).use { WavCodec.read(it) }
            assertContentEquals(samples.map { it.toFloat() / 32768f }.toFloatArray(), restored.samples)
            val project = result.newProject("rescued", "Recovered")
            assertTrue(project.pads.all { it.assetHash == null })
            assertTrue(project.patterns.single().notes.isEmpty())
            assertEquals(inputHash, sha256(archive))
        }
    }

    @Test fun unverifiedLegacySchemasAndChannelMismatchAreRejected() {
        val original = requireNotNull(javaClass.getResourceAsStream("/legacy/schema-7.txt")).use { it.readBytes() }.toString(Charsets.UTF_8)
        for (schema in listOf(0, 8, 9, 10)) {
            val manifest = original.replace("CHOPLAB_PROJECT\t7", "CHOPLAB_PROJECT\t$schema").toByteArray()
            assertFailsWith<IllegalArgumentException> { LegacySalvage().read(ByteArrayInputStream(Fixtures.zip(listOf("project.txt" to manifest))), FileAssetStore(Files.createTempDirectory("legacy-reject-"))) }
        }
        val mono = Fixtures.wav(1, samples = shortArrayOf(100, -200, 300, -400))
        assertFailsWith<IllegalArgumentException> {
            LegacySalvage().read(ByteArrayInputStream(Fixtures.zip(listOf("project.txt" to original.toByteArray(), "audio/0.wav" to mono))), FileAssetStore(Files.createTempDirectory("legacy-mismatch-")))
        }
    }

    @Test fun filePortsImportChopAssignSequenceSaveReopenExportWithoutOutputDevice() = runBlocking {
        val root = Files.createTempDirectory("choplab-e2e-")
        val input = root.resolve("fixture.wav")
        val pcm = ShortArray(960) { if (it % 2 == 0) 8000 else -4000 }
        val original = Fixtures.wav(samples = pcm); Files.write(input, original)
        val assets = FileAssetStore(root.resolve("assets"))
        val resolve: (Location) -> Path = { root.resolve(it.handle) }
        val compiler = ProgramCompiler(WavPcmPort(assets))
        val engine = DetachedEnginePort(compiler)
        val services = Services(assets, WavImportPort(assets, resolve), FileProjectPort(assets, resolve), WavExportPort(compiler, resolve), engine)
        val studio = Studio(this, services)
        suspend fun finishWork() = withTimeout(10_000) { while (studio.work.value.jobId != null) delay(5) }
        assertTrue(studio.dispatch(Action.Import(Location("fixture.wav"))).accepted); finishWork()
        assertNotNull(studio.document.value.project.source)
        assertTrue(studio.dispatch(Action.Edit(Intent.EqualChop(2))).accepted)
        assertTrue(studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
        assertTrue(studio.dispatch(Action.Edit(Intent.SetNote("pattern-1", Note(0, 0), true))).accepted)
        val project = studio.document.value.project
        assertTrue(studio.document.value.audiblePending)
        assertTrue(studio.dispatch(Action.Save(Location("music.choplab"))).accepted); finishWork()
        studio.dispatch(Action.New(Project(id = "temporary")))
        studio.dispatch(Action.Open(Location("music.choplab"))); finishWork()
        assertEquals(project, studio.document.value.project)
        studio.dispatch(Action.Export(ExportRequest(Location("mix.wav"), frames = 960, tailFrames = 192, seed = 37))); finishWork()
        val exported = Files.newInputStream(root.resolve("mix.wav")).use { WavCodec.read(it) }
        assertEquals(WavInfo(48_000, 2, 1152, 24, false), exported.info)
        assertTrue(exported.samples.any { abs(it) > 0.01 })
        assertContentEquals(original, Files.readAllBytes(input))
        assertTrue(studio.dispatch(Action.Close).accepted)
    }

    @Test fun timelineContentCannotBeSilentlyOmittedFromPatternExport(): Unit = runBlocking {
        val root = Files.createTempDirectory("timeline-export-guard-"); val output = root.resolve("mix.wav")
        Files.writeString(output, "existing")
        val asset = Fixtures.asset(Fixtures.wav())
        val track = Track("vocal", "Vocal", TrackKind.VOCAL, gain = 0.5f)
        val projects = listOf(
            Fixtures.project(asset).copy(tracks = frozenListOf(track)),
            Fixtures.project(asset).copy(tracks = frozenListOf(track), clips = frozenListOf(Clip("clip", "vocal", asset.hash, FrameRange(0, asset.frames), 960))),
            Fixtures.project(asset).copy(tracks = frozenListOf(track), takes = frozenListOf(Take("take", "vocal", asset.hash, FrameRange(0, asset.frames), 0))),
        )
        var loads = 0
        val exporter = WavExportPort(ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset): com.choplab.engine.PcmAsset { loads++; error("Guard must run before decoding") }
        })) { output }
        for (project in projects) assertFailsWith<IllegalArgumentException> {
            exporter.export(project, "pattern-1", ExportRequest(Location("output"), 960))
        }
        assertEquals(0, loads)
        assertEquals("existing", Files.readString(output))
        assertEquals(1L, Files.list(root).use { it.count() })
    }
}
