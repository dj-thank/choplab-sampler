package com.choplab.jvm

import com.choplab.core.persistence.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class MixerPersistenceTest {
    @Test fun schema13IsStrictAndMigrates10Through12ToExactlyBypassedUnroutedMusic() {
        for (schema in 10..12) {
            val before = requireNotNull(javaClass.getResourceAsStream("/schema$schema-empty.json")).use { it.readBytes() }
            val migrated = ProjectJson.decode(before)
            assertEquals(Project(), migrated)
            assertEquals(ProjectLimits.SCHEMA, migrated.schemaVersion)
            assertTrue(migrated.banks.all { it.trackId == null })
        }
        val encoded = ProjectJson.encode(Project())
        val root = ProjectJson.parse(encoded).jsonObject
        for (field in listOf("mix", "vocalComps", "lyricStructure")) {
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(root - field))) }
        }
        for (mutant in listOf(
            encoded.toString(Charsets.UTF_8).replace("\"schemaVersion\":${ProjectLimits.SCHEMA}", "\"schemaVersion\":12"),
            encoded.toString(Charsets.UTF_8).replace("\"feedback\":0.25", "\"feedback\":1.0"),
            encoded.toString(Charsets.UTF_8).replace("\"ratio\":4.0", "\"ratio\":\"4\""),
            encoded.toString(Charsets.UTF_8).replace("\"trackId\":null", "\"trackId\":\"missing\""),
            encoded.toString(Charsets.UTF_8).replace("\"masterGain\":1.0", "\"masterGain\":1.0,\"unknown\":0"),
        )) assertFailsWith<IllegalArgumentException> { ProjectJson.decode(mutant.toByteArray()) }
    }

    @Test fun fullMixerArchiveRetainsOriginalBytesFlowStructureAndVocalChoicesInAFreshStore(): Unit = runBlocking {
        val directory = Files.createTempDirectory("mixer-archive-")
        try {
            val store = FileAssetStore(directory.resolve("before"))
            val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes); store.write(asset, bytes)
            val renderedBytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(asset.frames.toInt() * 2) { .03125f }) }.toByteArray()
            val rendered = Fixtures.asset(renderedBytes).copy(role = AssetRole.RENDERED, derivedFrom = asset.hash)
            store.write(rendered, renderedBytes)
            val insert = MixInsert(MixEq(12f, -8f, 3f), MixFilter(MixFilterMode.HIGH_PASS, 73f), MixCompressor(true, -27f, 3.5f, .7f, 800f, 4f))
            val reading = LyricReading.create("line", "川", "かわ", com.choplab.core.ai.LyricLanguage.JAPANESE)
            val p = Fixtures.project(asset).copy(
                assets = frozenListOf(asset, rendered),
                vocalComps = frozenListOf(VocalComp("comp", rendered.hash, frozenListOf(VocalCompSegment("choice", "take", 0, asset.frames, "line")))) ,
                banks = (0..7).map { Bank(it, trackId = if (it == 0) "bank" else null) }.frozen(),
                tracks = frozenListOf(Track("bank", "A", TrackKind.BANK, .4f, -.2f, fx = TrackFx(insert, .8f, .6f)), Track("vocal", "声", TrackKind.VOCAL)),
                takes = frozenListOf(Take("take", "vocal", asset.hash, FrameRange(0, asset.frames), 0)),
                lyrics = frozenListOf(LyricLine("line", "川", 0, 960, frozenListOf(LyricWord("川", 0, 960, WordTimingOrigin.ESTIMATED)))),
                lyricStructure = LyricStructure("歌", com.choplab.core.ai.LyricLanguage.JAPANESE,
                    frozenListOf(LyricSection("一番", com.choplab.core.ai.LyricSectionKind.VERSE, 1, frozenListOf(reading)))),
                mix = MixSettings(MixDelay(true, 96000, .6f, 1.2f), MixReverb(true, 3f, .95f, .6f), insert, .8f),
            )
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(p, store, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("after"))
            val restored = ArchiveCodec().read(ByteArrayInputStream(archive), fresh)
            assertEquals(p, restored)
            assertContentEquals(bytes, fresh.read(asset))
            assertContentEquals(renderedBytes, fresh.read(rendered)); assertEquals(p.vocalComps, restored.vocalComps)
            assertEquals(p.takes, restored.takes); assertEquals(p.lyricStructure, restored.lyricStructure)
            // A schema12 reader cannot silently accept and erase these persisted effects.
            val current = ProjectJson.encode(restored).toString(Charsets.UTF_8)
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(current.replace("\"schemaVersion\":${ProjectLimits.SCHEMA}", "\"schemaVersion\":12").toByteArray()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
