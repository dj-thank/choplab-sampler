package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class VocalCompRendererTest {
    @Test fun chunkPartitionsHaveIdenticalFloatBytesAndStereoSwitchesAtTheSameSongFrames() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("comp-blocks-")
        val store = FileAssetStore(directory.resolve("assets"))
        val a = put(store, "a", 4800, .25f, -.15f)
        val b = put(store, "b", 4800, .75f, -.45f)
        val project = project(a, b)
        val draft = VocalCompDraft("comp", frozenListOf(VocalCompSegment("one", "a", 0, 2400), VocalCompSegment("two", "b", 2400, 4800)))
        WavPcmPort(store).use { pcm ->
            val renderer = VocalCompRenderer(store, pcm, directory.resolve("scratch"))
            var expected: ByteArray? = null
            for (block in listOf(1, 17, 96, 192, 480, 4096)) {
                val rendered = renderer.render(project, draft, "Comp", block)
                assertEquals(4800, rendered.frames)
                val bytes = store.read(rendered)
                if (expected == null) expected = bytes else assertContentEquals(expected, bytes, "Block $block")
            }
            val audio = WavCodec.read(ByteArrayInputStream(expected!!))
            assertEquals(.25f, audio.samples[1000 * 2]); assertEquals(-.15f, audio.samples[1000 * 2 + 1])
            assertEquals(.75f, audio.samples[3500 * 2]); assertEquals(-.45f, audio.samples[3500 * 2 + 1])
            assertEquals(0f, audio.samples.first()); assertEquals(0f, audio.samples.last(), .0000001f)
            assertTrue(audio.samples.all { it.isFinite() })
        }
        assertEquals(0, Files.list(directory.resolve("scratch")).use { it.count() })
    }

    @Test fun farRangesOfPagedCandidatesReadExactSamplesAndCancelReleasesOnlyPrivateScratch() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("comp-paged-")
        val store = FileAssetStore(directory.resolve("assets"))
        val a = put(store, "a", 2_200_000, 1.25f, -.25f)
        val b = put(store, "b", 2_200_000, -.5f, .75f)
        val project = project(a, b)
        val scratch = directory.resolve("scratch")
        WavPcmPort(store).use { pcm ->
            assertNotNull(pcm.load(a).pages)
            val renderer = VocalCompRenderer(store, pcm, scratch)
            val draft = VocalCompDraft("comp", frozenListOf(VocalCompSegment("one", "a", 2_190_000, 2_194_000),
                VocalCompSegment("two", "b", 2_194_000, 2_198_000)))
            val rendered = renderer.render(project, draft, "Comp")
            val samples = store.openVerified(rendered).use { WavCodec.read(it).samples }
            assertEquals(1.25f, samples[2000]); assertEquals(-.25f, samples[2001])
            assertEquals(-.5f, samples[12000]); assertEquals(.75f, samples[12001])
            val before = store.storedBytes()
            val pending = async { renderer.render(project, VocalCompEdits.fullTake(project, "a", "long"), "Cancelled", blockFrames = 1) }
            withTimeout(5000) { while (!Files.list(scratch).use { it.findAny().isPresent }) delay(1) }
            pending.cancelAndJoin()
            assertEquals(before, store.storedBytes())
            assertEquals(0, Files.list(scratch).use { it.count() })
            assertTrue(store.verified(a) && store.verified(b))
            assertTrue(pcm.readWindow(pcm.load(a), 2_190_000, 2_190_001).contentEquals(floatArrayOf(1.25f, -.25f)), "Cancelling comp does not discard shared source leases")
            val limited = VocalCompRenderer(store, pcm, scratch, usableDiskBytes = { 64L shl 20 })
            assertEquals(VocalProblem.LIMIT, assertFailsWith<VocalEditException> { limited.render(project, draft, "No room") }.problem)
        }
    }

    @Test fun schema12RequiresEveryChoiceAndMigrates10And11WithoutDroppingLyricStructure() {
        val current = ProjectJson.encode(Project()).toString(Charsets.UTF_8)
        for (schema in listOf(10, 11)) {
            val old = requireNotNull(javaClass.getResourceAsStream("/schema$schema-empty.json")).use { it.readBytes() }
            assertEquals(Project(), ProjectJson.decode(old))
        }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(current.replace("\"vocalComps\":[],", "").toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(current.replace("\"schemaVersion\":12", "\"schemaVersion\":11").toByteArray()) }
        val a = Asset("a".repeat(64), "wav", 38444, 48_000, 2, 4800, "A")
        val b = a.copy(hash = "b".repeat(64))
        val p = project(a, b)
        val draft = VocalCompDraft("comp", frozenListOf(VocalCompSegment("one", "a", 0, 4800)))
        val rendered = a.copy(hash = "c".repeat(64), role = AssetRole.RENDERED)
        val saved = com.choplab.core.edit.Reducer.reduce(p, VocalCompEdits.apply(p, draft, rendered, p.tracks.single(), "clip")).project
        val json = ProjectJson.encode(saved).toString(Charsets.UTF_8)
        assertEquals(saved, ProjectJson.decode(json.toByteArray()))
        for (bad in listOf(json.replace("\"takeId\":\"a\"", "\"takeId\":\"missing\""),
            json.replace("\"endFrame\":4800", "\"endFrame\":4801"),
            json.replace("\"lyricLineId\":null", "\"privatePath\":null"),
            json.replace("\"startFrame\":0", "\"startFrame\":\"0\""))) {
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(bad.toByteArray()) }
        }
    }

    private fun project(a: Asset, b: Asset) = Project(assets = frozenListOf(a, b), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
        takes = frozenListOf(Take("a", "voice", a.hash, FrameRange(0, a.frames), 0), Take("b", "voice", b.hash, FrameRange(0, b.frames), 0)))
    private fun put(store: FileAssetStore, name: String, frames: Long, left: Float, right: Float): Asset {
        val pending = Files.createTempFile("comp-source-", ".wav")
        try {
            Files.newOutputStream(pending).buffered().use { output ->
                val writer = WavCodec.FloatWriter(output, frames)
                val buffer = FloatArray(8192) { if (it % 2 == 0) left else right }
                var at = 0L
                while (at < frames) { val count = minOf(4096, frames - at).toInt(); writer.write(buffer, frameCount = count); at += count }
                writer.finish()
            }
            val bytes = Files.size(pending)
            val hash = Files.newInputStream(pending).use { digest(it, bytes) }
            return Asset(hash, "wav", bytes, 48_000, 2, frames, name).also { store.adopt(it, pending) }
        } finally { Files.deleteIfExists(pending) }
    }
}
