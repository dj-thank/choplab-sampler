package com.choplab.jvm

import com.choplab.core.ai.*
import com.choplab.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class LyricStructurePersistenceTest {
    private fun structured(): Project {
        val placement = LyricProposal("川の歌", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("一番", LyricSectionKind.VERSE, 2,
            frozenListOf(ProposalLine.create("川", "かわ", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
        return Project(lyrics = placement.lines.map { it.copy(words = frozenListOf(LyricWord("川", 0, 1920, WordTimingOrigin.RETURNED))) }.frozen(),
            lyricStructure = placement.structure)
    }

    @Test fun schema11ArchiveAutosaveAndFreshStoreKeepStructureAndWordProvenance() {
        val project = structured()
        val directory = Files.createTempDirectory("lyric-structure-")
        val assets = FileAssetStore(directory.resolve("assets"))
        val archive = ByteArrayOutputStream().also { ArchiveCodec().write(project, assets, it) }.toByteArray()
        val fresh = FileAssetStore(directory.resolve("fresh"))
        assertEquals(project, ArchiveCodec().read(ByteArrayInputStream(archive), fresh))
        val autosave = AutosaveStore(directory.resolve("autosave"), assets)
        assertTrue(autosave.save(project, 4))
        assertEquals(project, AutosaveStore(directory.resolve("autosave"), fresh).recover()!!.project)
        for (origin in WordTimingOrigin.entries) {
            val current = project.copy(lyrics = project.lyrics.map { it.copy(words = it.words.map { w -> w.copy(timingOrigin = origin) }.frozen()) }.frozen())
            assertEquals(current, ProjectJson.decode(ProjectJson.encode(current)))
        }
        assertFailsWith<IllegalArgumentException> { ArchiveCodec().read(ByteArrayInputStream(archive), fresh) { true } }
    }

    @Test fun schema10MigratesWithoutRewritingOriginalAudioOrTheStoredEnvelope() {
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        val project = Fixtures.project(asset).copy(lyrics = frozenListOf(LyricLine("old", "Old words", 0, 1920,
            frozenListOf(LyricWord("Old", 0, 960)))))
        val oldDocument = ProjectJson.encode(project).toString(Charsets.UTF_8)
            .replace("\"schemaVersion\":11", "\"schemaVersion\":10")
            .replace("\"lyricStructure\":null,", "")
            .replace(",\"timingOrigin\":\"MANUAL\"", "").toByteArray()
        val archive = Fixtures.zip(listOf("project.json" to oldDocument, asset.entryName to bytes))
        val directory = Files.createTempDirectory("lyric-migration-")
        val store = FileAssetStore(directory.resolve("assets"))
        val restored = ArchiveCodec().read(ByteArrayInputStream(archive), store)
        assertEquals(project, restored)
        assertEquals(11, restored.schemaVersion)
        assertEquals(WordTimingOrigin.MANUAL, restored.lyrics.single().words.single().timingOrigin)
        assertContentEquals(bytes, store.openVerified(asset).use { it.readBytes() })
        val autoDirectory = Files.createDirectory(directory.resolve("autosave"))
        val envelope = ProjectJson.encodeElement(obj("generation" to num(0), "revision" to num(8), "documentHash" to str(sha256(oldDocument)),
            "project" to ProjectJson.parse(oldDocument)))
        val oldSlot = autoDirectory.resolve("autosave.0.json")
        Files.write(oldSlot, envelope)
        val autosave = AutosaveStore(autoDirectory, store)
        assertEquals(project, autosave.recover()!!.project)
        assertTrue(autosave.save(project, 9))
        assertContentEquals(envelope, Files.readAllBytes(oldSlot))
        assertEquals(9L, autosave.recover()!!.revision)
        val rewritten = ByteArrayOutputStream().also { ArchiveCodec().write(restored, store, it) }.toByteArray()
        assertContentEquals(bytes, Fixtures.unzip(rewritten).single { it.first == asset.entryName }.second)
    }

    @Test fun schema11RejectsUnknownEnumsForgedMetricsMissingStructureAndReadLimits() {
        val json = ProjectJson.encode(structured()).toString(Charsets.UTF_8)
        for (bad in listOf(
            json.replace("\"RETURNED\"", "\"PRECISE\""), json.replace("\"JAPANESE\"", "\"UNKNOWN\""),
            json.replace("\"VERSE\"", "\"UNKNOWN\""), json.replace("\"mora\":2", "\"mora\":20"),
            json.replace("\"lineId\":\"line-0\"", "\"lineId\":\"missing\""),
            json.replace("\"schemaVersion\":11", "\"schemaVersion\":10"),
            json.replace("\"reading\":\"かわ\"", "\"reading\":\"${"あ".repeat(513)}\""),
            json.replace("\"lyricStructure\":", "\"unknownStructure\":"),
        )) assertFailsWith<IllegalArgumentException> { ProjectJson.decode(bad.toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ByteArray(ProjectJson.MAX_BYTES + 1)) }
    }
}
