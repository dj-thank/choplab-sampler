package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class ArrangementIntegrationTest {
    private data class Fixture(val project: Project, val store: FileAssetStore, val pcm: WavPcmPort)
    private fun fixture(rate: Int = 48_000, frames: Int = 480): Fixture {
        val bytes = Fixtures.wav(rate = rate, samples = ShortArray(frames * 2) { i -> (6000 * sin((i / 2) * 0.073)).toInt().toShort() })
        val asset = Fixtures.asset(bytes)
        val store = FileAssetStore(Files.createTempDirectory("arrangement-pcm-")); store.publish(asset, ByteArrayInputStream(bytes))
        val track = Track("t", "Source", TrackKind.SOURCE)
        val clip = Clip("c", "t", asset.hash, FrameRange(0, frames.toLong()), timelineStartFrame = 73)
        return Fixture(Fixtures.project(asset).copy(tracks = frozenListOf(track), clips = frozenListOf(clip)), store, WavPcmPort(store))
    }

    @Test fun frameGainPanCodecAndPreviousSchema10ClipDefaultsRoundTrip() {
        val fixture = fixture()
        fixture.pcm.use {
            val p = fixture.project.copy(clips = fixture.project.clips.map { it.copy(timelineStartFrame = 7, gain = 0.25f, pan = -0.5f) }.frozen())
            assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
            val old = fixture.project.copy(clips = fixture.project.clips.map { it.copy(timelineStartFrame = null) }.frozen())
            val encoded = ProjectJson.encode(old).toString(Charsets.UTF_8)
            val previous = encoded.replace(",\"timelineStartFrame\":null,\"gain\":1.0,\"pan\":0.0", "")
            assertNotEquals(encoded, previous)
            assertEquals(old, ProjectJson.decode(previous.toByteArray()))
            assertFailsWith<IllegalArgumentException> { p.clips.first().copy(timelineStartFrame = ProjectLimits.MAX_TIMELINE_FRAMES + 1) }
            assertFailsWith<IllegalArgumentException> { p.clips.first().copy(gain = Float.NaN) }
        }
    }

    @Test fun arrangementSaveReopenExportUsesSameGraphAndKeepsInactivePatternSilent(): Unit = runBlocking {
        val fixture = fixture()
        fixture.pcm.use { pcm ->
            val compiler = ProgramCompiler(pcm); val p = fixture.project
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(p, fixture.store, it) }.toByteArray()
            val restored = ArchiveCodec().read(ByteArrayInputStream(archive), fixture.store)
            assertEquals(p, restored)
            val target = PlaybackTarget.Arrangement()
            val program = compiler.compile(restored, target, 1)
            assertNull(program.pattern); assertNotNull(program.pad(0))
            val variants = listOf(1, 17, 480, 4096).map { block -> ByteArrayOutputStream().also { StreamingWavRenderer.render(program, it, 800, 192, blockFrames = block) }.toByteArray() }
            variants.drop(1).forEach { assertContentEquals(variants.first(), it) }
            val root = Files.createTempDirectory("arrangement-export-"); val output = root.resolve("mix.wav")
            val exporter = WavExportPort(compiler) { output }
            exporter.export(restored, target, ExportRequest(Location("output"), 800, 192))
            assertContentEquals(variants.first(), Files.readAllBytes(output))
            val decoded = Files.newInputStream(output).use { WavCodec.read(it) }
            assertTrue(decoded.samples.take(73 * 2).all { abs(it) < 0.000001f }) // Stored PAD pattern has a note at zero.
            assertTrue(decoded.samples.any { abs(it) > 0.05f })
            assertFailsWith<IllegalArgumentException> { exporter.export(restored, "pattern-1", ExportRequest(Location("output"), 800)) }
        }
    }

    @Test fun mixedRateSplitIsSampleIdenticalToUnsplitAndTrackMutePreservesDuration(): Unit = runBlocking {
        val fixture = fixture(44_100, 441)
        fixture.pcm.use { pcm ->
            val p = fixture.project; val asset = p.assets.first(); val compiler = ProgramCompiler(pcm)
            val whole = p.copy(clips = frozenListOf(p.clips.first().copy(timelineStartFrame = 0)))
            val boundary = ProgramCompiler.sourceFrameTo48k(220, asset.sampleRate)
            val split = p.copy(clips = frozenListOf(
                Clip("a", "t", asset.hash, FrameRange(0, 220), timelineStartFrame = 0),
                Clip("b", "t", asset.hash, FrameRange(220, 441), timelineStartFrame = boundary),
            ))
            fun render(program: com.choplab.engine.EngineProgram) = ByteArrayOutputStream().also { StreamingWavRenderer.render(program, it, 480, 192) }.toByteArray()
            assertContentEquals(render(compiler.compile(whole, PlaybackTarget.Arrangement(), 1)), render(compiler.compile(split, PlaybackTarget.Arrangement(), 2)))
            val muted = compiler.compile(split.copy(tracks = split.tracks.map { it.copy(mute = true) }.frozen()), PlaybackTarget.Arrangement(), 3)
            assertEquals(480L, muted.arrangement!!.durationFrames); assertEquals(0, muted.arrangement!!.clipCount)
            val silent = WavCodec.read(ByteArrayInputStream(render(muted)))
            assertTrue(silent.samples.all { abs(it) < 0.000001f })
            val mixed = whole.copy(tracks = whole.tracks.map { it.copy(gain = 0.5f) }.frozen(), clips = whole.clips.map { it.copy(gain = 0.5f, pan = 1f) }.frozen())
            val mixedAudio = WavCodec.read(ByteArrayInputStream(render(compiler.compile(mixed, PlaybackTarget.Arrangement(), 4)))).samples
            val originalAudio = WavCodec.read(ByteArrayInputStream(render(compiler.compile(whole, PlaybackTarget.Arrangement(), 5)))).samples
            assertTrue(mixedAudio.indices.filter { it % 2 == 0 }.all { abs(mixedAudio[it]) < 0.000001f })
            val mixedPeak = mixedAudio.indices.filter { it % 2 == 1 }.maxOf { abs(mixedAudio[it]) }
            val originalPeak = originalAudio.indices.filter { it % 2 == 1 }.maxOf { abs(originalAudio[it]) }
            assertEquals(0.25f, mixedPeak / originalPeak, 0.001f)
        }
    }

    @Test fun studioPauseSeekResumeProjectsTheEngineTransportFrame(): Unit = runBlocking {
        val fixture = fixture()
        fixture.pcm.use { pcm ->
            val compiler = ProgramCompiler(pcm)
            val engine = DetachedEnginePort(compiler)
            val studio = Studio(this, Services(fixture.store, object : ImportPort { override suspend fun import(location: Location) = fixture.project.assets.first() },
                object : ProjectPort { override suspend fun save(project: Project, revision: Long, location: Location) = Unit; override suspend fun open(location: Location) = fixture.project },
                WavExportPort(compiler) { error("No export in this test") }, engine), fixture.project)
            assertTrue(studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
            assertTrue(studio.dispatch(Action.Play).accepted)
            assertTrue(studio.dispatch(Action.Pause).accepted)
            assertTrue(studio.dispatch(Action.Seek(47)).accepted)
            assertFalse(studio.transport.value.playing)
            assertTrue(studio.transport.value.sequencePaused)
            assertEquals(47L, studio.transport.value.sequenceFrame)
            assertTrue(studio.dispatch(Action.Resume).accepted)
            assertTrue(studio.transport.value.playing)
            assertFalse(studio.transport.value.sequencePaused)
            assertEquals(48L, studio.transport.value.sequenceFrame)
            assertTrue(studio.dispatch(Action.Close).accepted)
        }
    }

    @Test fun thirtyMinuteExportBoundaryBuildsAnExactHeaderWithoutAllocatingTheSong() {
        val maximum = ProjectLimits.MAX_TIMELINE_FRAMES.toInt()
        val request = ExportRequest(Location("maximum"), maximum, tailFrames = 480_000)
        val output = ByteArrayOutputStream()
        val writer = WavCodec.PcmWriter(output, request.frames.toLong() + request.tailFrames, bits = 24, bufferFrames = 480)
        val header = output.toByteArray()
        val dataBytes = (maximum.toLong() + 480_000) * 2 * 3
        assertEquals(44, header.size)
        assertEquals(dataBytes, header.u32(40)); assertEquals(dataBytes + 36, header.u32(4))
        assertEquals(2880, writer.bufferBytes)
        assertEquals(dataBytes + 44, WavCodec.MAX_EXPORT_WAV_BYTES)
        assertTrue(dataBytes + 36 < 0x1_0000_0000L)
        assertFailsWith<IllegalArgumentException> { ExportRequest(Location("too-long"), maximum + 1) }
        assertFailsWith<IllegalArgumentException> { WavCodec.PcmWriter(ByteArrayOutputStream(), maximum + 480_001L) }
        val cancelled = ByteArrayOutputStream()
        assertFailsWith<CancellationException> { StreamingWavRenderer.render(com.choplab.engine.EngineProgram.EMPTY, cancelled, maximum, 480_000, cancelled = { true }) }
        assertEquals(44, cancelled.size()) // Header/preflight only: zero audio frames rendered.
        assertFailsWith<IllegalArgumentException> { StreamingWavRenderer.render(com.choplab.engine.EngineProgram.EMPTY, ByteArrayOutputStream(), maximum + 1) }
    }

    @Test fun previousSchema10AutosaveHashIsVerifiedBeforeNewClipDefaultsAreMaterialized() {
        val fixture = fixture()
        fixture.pcm.use {
            val project = fixture.project.copy(clips = fixture.project.clips.map { it.copy(timelineStartFrame = null) }.frozen())
            val oldDocument = ProjectJson.encode(project).toString(Charsets.UTF_8)
                .replace(",\"timelineStartFrame\":null,\"gain\":1.0,\"pan\":0.0", "").toByteArray()
            val envelope = ProjectJson.encodeElement(obj("generation" to num(0), "revision" to num(1),
                "documentHash" to str(sha256(oldDocument)), "project" to ProjectJson.parse(oldDocument)))
            val directory = Files.createTempDirectory("old-clip-autosave-")
            val slot = directory.resolve("autosave.0.json")
            Files.write(slot, envelope)
            val store = AutosaveStore(directory, fixture.store)
            assertEquals(project, assertNotNull(store.recover()).project)
            Files.writeString(slot, envelope.toString(Charsets.UTF_8).replace("\"title\":\"Untitled\"", "\"title\":\"Tampered\""))
            assertNull(store.recover())
        }
    }
}
