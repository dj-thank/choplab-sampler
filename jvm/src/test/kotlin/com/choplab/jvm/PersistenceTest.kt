package com.choplab.jvm

import com.choplab.core.model.*
import java.io.*
import java.nio.file.*
import java.util.zip.*
import kotlin.test.*

internal object Fixtures {
    /** Independently encoded RIFF PCM16, no call to the codec under test. */
    fun wav(channels: Int = 2, rate: Int = 48_000, samples: ShortArray = shortArrayOf(1000, -2000, 3000, -4000, 5000, -6000, 7000, -8000)): ByteArray {
        require(samples.size % channels == 0)
        val bytes = ByteArray(44 + samples.size * 2)
        fun word(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value shr 8).toByte() }
        fun int(at: Int, value: Int) { word(at, value); word(at + 2, value shr 16) }
        "RIFF".toByteArray().copyInto(bytes); int(4, bytes.size - 8); "WAVEfmt ".toByteArray().copyInto(bytes, 8)
        int(16, 16); word(20, 1); word(22, channels); int(24, rate); int(28, rate * channels * 2); word(32, channels * 2); word(34, 16)
        "data".toByteArray().copyInto(bytes, 36); int(40, samples.size * 2)
        samples.forEachIndexed { index, sample -> word(44 + index * 2, sample.toInt()) }
        return bytes
    }
    fun asset(bytes: ByteArray, name: String = "fixture.wav"): Asset {
        val info = WavCodec.inspect(ByteArrayInputStream(bytes))
        return Asset(sha256(bytes), "wav", bytes.size.toLong(), info.sampleRate, info.channels, info.frames, name)
    }
    fun project(asset: Asset): Project = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, asset.frames)),
        pads = (0..127).map { if (it == 0) Pad(0, asset.hash, FrameRange(0, asset.frames), decayFrames = 24, sustainLevel = 0.6f) else Pad(it) }.frozen(),
        patterns = frozenListOf(Pattern("pattern-1", notes = frozenListOf(Note(0, 0)))))
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }
    }.toByteArray()
    fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        buildList { while (true) { val entry = zip.nextEntry ?: break; add(entry.name to zip.readBytes()); zip.closeEntry() } }
    }
}

class PersistenceTest {
    private fun directory(): Path = Files.createTempDirectory("choplab-test-")
    private fun archive(project: Project, store: FileAssetStore): ByteArray = ByteArrayOutputStream().also { ArchiveCodec().write(project, store, it) }.toByteArray()
    private fun read(bytes: ByteArray, store: FileAssetStore = FileAssetStore(directory())) = ArchiveCodec().read(ByteArrayInputStream(bytes), store)

    @Test fun jsonMatchesIndependentGoldenAndRejectsAmbiguousInput() {
        val golden = requireNotNull(javaClass.getResourceAsStream("/schema10-empty.json")).use { it.readBytes() }
        assertContentEquals(golden, ProjectJson.encode(Project()))
        assertEquals(Project(), ProjectJson.decode(golden))
        val text = golden.toString(Charsets.UTF_8)
        for (mutated in listOf(
            text.replace("\"schemaVersion\":10", "\"schemaVersion\":9"),
            text.replace("\"schemaVersion\":10", "\"schemaVersion\":10,\"schemaVersion\":10"),
            text.replace("\"schemaVersion\":10", "\"schemaVersion\":10,\"\\u0073chemaVersion\":10"),
            text.replace("\"schemaVersion\":10", "\"schemaVersion\":\"10\""),
            text.replace("\"id\":\"untitled\"", "\"id\":\"untitled\",\"privatePath\":\"secret\""),
            text.replace("\"gain\":1.0", "\"gain\":1e999"),
        )) assertFailsWith<IllegalArgumentException> { ProjectJson.decode(mutated.toByteArray()) }
        assertFailsWith<Exception> { ProjectJson.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(("[".repeat(33) + "0" + "]".repeat(33)).toByteArray()) }
    }

    @Test fun fullDocumentRoundTripIncludesEnvelopesSongLyricsClipsAndTakes() {
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes)
        val project = Fixtures.project(asset).copy(
            song = frozenListOf(SongSection("pattern-1", 3)),
            tracks = frozenListOf(Track("vocal", "Vocal", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "vocal", asset.hash, FrameRange(0, 4), 960)),
            lyrics = frozenListOf(LyricLine("line", "歌詞", 0, 1920, frozenListOf(LyricWord("歌詞", 0, 960)))),
            takes = frozenListOf(Take("take", "vocal", asset.hash, FrameRange(0, 4), 48_000, -240)),
        )
        assertEquals(project, ProjectJson.decode(ProjectJson.encode(project)))
        assertFalse(ProjectJson.encode(project).toString(Charsets.UTF_8).contains("path", ignoreCase = true))
    }

    @Test fun humanTitlePunctuationSurvivesWithoutSerializingHostLocations() {
        val title = "Artist: Song / Verse\\Chorus"
        val location = com.choplab.core.Location("host-only-private-handle")
        val project = Project(title = title)
        val encoded = ProjectJson.encode(project)
        assertEquals(title, ProjectJson.decode(encoded).title)
        assertFalse(encoded.toString(Charsets.UTF_8).contains(location.handle))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("\"handle\""))
    }

    @Test fun archiveIsDeterministicAndOriginalSourceBytesSurvive() {
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val project = Fixtures.project(asset)
        val store = FileAssetStore(directory()); store.publish(asset, ByteArrayInputStream(bytes))
        val first = archive(project, store); val second = archive(project, store)
        assertContentEquals(first, second)
        val entries = Fixtures.unzip(first)
        assertEquals(listOf("project.json", asset.entryName), entries.map { it.first })
        assertContentEquals(bytes, entries[1].second)
        val destination = FileAssetStore(directory())
        assertEquals(project, read(first, destination))
        // Re-import into an existing store still verifies incoming bytes, not only its old copy.
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { read(Fixtures.zip(listOf(entries[0], asset.entryName to corrupt)), destination) }
    }

    @Test fun archiveRejectsTraversalCaseCollisionUnknownMissingOrderAndHash() {
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val project = Fixtures.project(asset)
        val manifest = "project.json" to ProjectJson.encode(project)
        val sound = asset.entryName to bytes
        for (entries in listOf(
            listOf(sound, manifest), listOf(manifest), listOf(manifest, "../outside.wav" to bytes),
            listOf(manifest, "assets\\bad.wav" to bytes), listOf(manifest, "PROJECT.JSON" to manifest.second),
            listOf(manifest, "other.txt" to bytes),
            listOf(manifest, asset.entryName to bytes.copyOf(bytes.size - 1)),
        )) assertFailsWith<IllegalArgumentException> { read(Fixtures.zip(entries)) }
        val secondBytes = Fixtures.wav(samples = shortArrayOf(1, -2, 3, -4))
        val second = Fixtures.asset(secondBytes, "second.wav")
        val all = listOf(asset to bytes, second to secondBytes).sortedByDescending { it.first.entryName }
        val twoProject = Project(assets = all.map { it.first }.frozen())
        assertFailsWith<IllegalArgumentException> { read(Fixtures.zip(listOf("project.json" to ProjectJson.encode(twoProject)) + all.map { it.first.entryName to it.second })) }
    }

    @Test fun expandedBombIsBoundedBeforeAssetPublicationAndCancellationIsRetryable() {
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val project = Fixtures.project(asset)
        val bomb = Fixtures.zip(listOf("project.json" to ProjectJson.encode(project), asset.entryName to ByteArray(1_000_000)))
        val store = FileAssetStore(directory())
        assertFailsWith<IllegalArgumentException> { read(bomb, store) }
        assertFalse(store.verified(asset))
        assertEquals(0L, Files.list(store.directory).use { it.count() })
        val valid = Fixtures.zip(listOf("project.json" to ProjectJson.encode(project), asset.entryName to bytes))
        assertFailsWith<IllegalArgumentException> { ArchiveCodec().read(ByteArrayInputStream(valid), store) { true } }
        assertEquals(project, read(valid, store))
        assertFailsWith<IllegalArgumentException> { ArchiveCodec(ArchiveLimits(maxAssetBytes = 16)).read(ByteArrayInputStream(valid), FileAssetStore(directory())) }
        assertFailsWith<IllegalArgumentException> { ArchiveCodec(ArchiveLimits(maxArchiveBytes = 20)).read(ByteArrayInputStream(valid), FileAssetStore(directory())) }
    }

    @Test fun wrongMetadataUnknownCodecAndHashCannotBePublished() {
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val store = FileAssetStore(directory())
        assertFailsWith<IllegalArgumentException> { store.publish(asset.copy(channels = 1), ByteArrayInputStream(bytes)) }
        assertFailsWith<IllegalArgumentException> { store.publish(asset.copy(hash = "f".repeat(64)), ByteArrayInputStream(bytes)) }
        assertFailsWith<IllegalArgumentException> { store.publish(asset.copy(extension = "mp3"), ByteArrayInputStream(bytes)) }
        assertEquals(0L, Files.list(store.directory).use { it.count() })
        store.publish(asset, ByteArrayInputStream(bytes))
        Files.write(store.directory.resolve("${asset.hash}.wav"), byteArrayOf(1))
        assertFalse(store.verified(asset))
        assertFailsWith<IllegalArgumentException> { store.publish(asset, ByteArrayInputStream(bytes)) }
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(store.directory.resolve("${asset.hash}.wav")))
    }

    @Test fun autosaveCommitsAssetFirstKeepsThreeGenerationsAndFallsBackOnCorruption() {
        val root = directory(); val assets = FileAssetStore(root.resolve("assets")); val autosave = AutosaveStore(root.resolve("documents"), assets)
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val project = Fixtures.project(asset)
        assertFailsWith<IllegalArgumentException> { autosave.save(project, 1) }
        assertNull(autosave.recover())
        assertTrue(autosave.save(project, 1, mapOf(asset.hash to bytes)))
        repeat(3) { assertTrue(autosave.save(project.copy(title = "Generation ${it + 2}"), it + 2L)) }
        assertFalse(autosave.save(project, 3))
        assertEquals(3L, Files.list(autosave.directory).use { it.count() })
        assertEquals(4L, autosave.recover()!!.revision)
        assertTrue(asset.hash in autosave.protectedAssets(setOf("u".repeat(64)), setOf("j".repeat(64))))
        assertTrue("u".repeat(64) in autosave.protectedAssets(setOf("u".repeat(64))))
        Files.writeString(autosave.directory.resolve("autosave.0.json"), "corrupt")
        assertEquals(3L, autosave.recover()!!.revision)
    }

    @Test fun interruptedAutosaveKeepsPriorDocumentAndPublishedAssetForRetry() {
        val root = directory(); val assets = FileAssetStore(root.resolve("assets"))
        val initial = AutosaveStore(root.resolve("documents"), assets)
        initial.save(Project(), 1)
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); val project = Fixtures.project(asset)
        val failing = AutosaveStore(initial.directory, assets) { throw IOException("simulated power loss before rename") }
        assertFailsWith<IOException> { failing.save(project, 2, mapOf(asset.hash to bytes)) }
        assertEquals(Project(), initial.recover()!!.project)
        assertTrue(assets.verified(asset))
        assertEquals(1L, Files.list(initial.directory).use { it.count() })
        assertTrue(initial.save(project, 2))
        assertEquals(project, initial.recover()!!.project)
    }

    @Test fun unavailableNewestAssetRestoresOlderDocument() {
        val root = directory(); val assets = FileAssetStore(root.resolve("assets")); val autosave = AutosaveStore(root.resolve("documents"), assets)
        autosave.save(Project(), 1)
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes)
        autosave.save(Fixtures.project(asset), 2, mapOf(asset.hash to bytes))
        Files.write(assets.directory.resolve("${asset.hash}.wav"), byteArrayOf(0))
        assertEquals(1L, autosave.recover()!!.revision)
    }

    @Test fun cancelledAtomicOutputLeavesTheExistingFileAndNoPendingBytes() {
        val root = directory(); val target = root.resolve("mix.wav")
        Files.writeString(target, "original")
        assertFailsWith<IllegalArgumentException> { atomicOutput(target, { true }) { it.write("cancelled".toByteArray()) } }
        assertEquals("original", Files.readString(target))
        assertEquals(1L, Files.list(root).use { it.count() })
    }
}
