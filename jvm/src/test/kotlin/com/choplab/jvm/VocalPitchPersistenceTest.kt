package com.choplab.jvm

import com.choplab.core.DocumentState
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.core.vocal.VocalPitchEdits
import com.choplab.engine.PitchCorrectionSettings
import com.choplab.engine.PitchScale
import kotlinx.serialization.json.*
import kotlin.test.*

class VocalPitchPersistenceTest {
    @Test fun schemas10Through13MigrateToEmptyCorrectionsWithoutRelaxingTheirOriginalShape() {
        for (schema in 10..13) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/schema$schema-empty.json")).use { it.readBytes() }
            val migrated = ProjectJson.decode(bytes)
            assertEquals(Project(), migrated); assertTrue(migrated.pitchCorrections.isEmpty())
            val old = ProjectJson.parse(bytes).jsonObject
            assertFailsWith<IllegalArgumentException> {
                ProjectJson.decode(ProjectJson.encodeElement(JsonObject(old + ("pitchCorrections" to JsonArray(emptyList())))))
            }
        }
        val current = ProjectJson.parse(ProjectJson.encode(Project())).jsonObject
        assertEquals(14, current.getValue("schemaVersion").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(current - "pitchCorrections"))) }
        val old13 = requireNotNull(javaClass.getResourceAsStream("/schema13-empty.json")).use { ProjectJson.parse(it.readBytes()).jsonObject }
        assertEquals(Project(), ProjectJson.decode(ProjectJson.encodeElement(old13)))
    }

    @Test fun everyCorrectionParameterIsStrictAndBadProvenanceCannotSilentlyBecomeAPlayableClip() {
        val source = Asset("a".repeat(64), "wav", 96_044, 96_000, 2, 12_000, "Original")
        val before = Project(assets = frozenListOf(source), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", source.hash, FrameRange(13, 11_999))))
        val settings = PitchCorrectionSettings(9, PitchScale.NATURAL_MINOR, .63f, 91f, .82f)
        val draft = VocalPitchEdits.draft(DocumentState(before, 5), "clip", "pitch", settings)
        val frames = draft.frames48(source)
        val rendered = Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Corrected", AssetRole.RENDERED, derivedFrom = source.hash)
        val corrected = Reducer.reduce(before, VocalPitchEdits.apply(DocumentState(before, 5), draft, rendered)).project
        val encoded = ProjectJson.encode(corrected)
        assertEquals(corrected, ProjectJson.decode(encoded))
        val root = ProjectJson.parse(encoded).jsonObject
        val correction = root.getValue("pitchCorrections").jsonArray.single().jsonObject
        fun reject(value: JsonObject) {
            val mutated = JsonObject(root + ("pitchCorrections" to JsonArray(listOf(value))))
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(mutated)) }
        }
        for (key in correction.keys) reject(JsonObject(correction - key))
        reject(JsonObject(correction + ("unknown" to JsonPrimitive(1))))
        reject(JsonObject(correction + ("algorithmVersion" to JsonPrimitive(2))))
        reject(JsonObject(correction + ("algorithmVersion" to JsonPrimitive("1"))))
        reject(JsonObject(correction + ("sourceAssetHash" to JsonPrimitive("c".repeat(64)))))
        reject(JsonObject(correction + ("renderedAssetHash" to JsonPrimitive(source.hash))))
        val originalSettings = correction.getValue("settings").jsonObject
        for ((key, value) in listOf("key" to JsonPrimitive(12), "scale" to JsonPrimitive("UNKNOWN"),
            "amount" to JsonPrimitive(-.1), "vibrato" to JsonPrimitive(1.1), "retuneMs" to JsonPrimitive(501), "amount" to JsonPrimitive("0.63"))) {
            reject(JsonObject(correction + ("settings" to JsonObject(originalSettings + (key to value)))))
        }
        val duplicated = JsonObject(root + ("pitchCorrections" to JsonArray(listOf(correction, correction))))
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(duplicated)) }
        val forgedOldSchema = JsonObject(root + ("schemaVersion" to JsonPrimitive(13)))
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(forgedOldSchema)) }
    }
}
