package com.choplab.jvm

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlinx.serialization.json.*
import kotlin.test.*

class BeatStretchPersistenceTest {
    @Test fun schemasTenThroughFourteenMigrateWithoutAcceptingFutureFieldsAndFifteenIsStrict() {
        for (schema in 10..14) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/schema$schema-empty.json")).use { it.readBytes() }
            assertEquals(Project(), ProjectJson.decode(bytes))
            val original = ProjectJson.parse(bytes).jsonObject
            assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(original + ("beatStretches" to JsonArray(emptyList()))))) }
        }
        val current = ProjectJson.parse(ProjectJson.encode(Project())).jsonObject
        assertEquals(15, current.getValue("schemaVersion").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(current - "beatStretches"))) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(current + ("unknown" to JsonPrimitive(1))))) }
    }
    @Test fun recipeRoundtripBindsEveryRangeBpmVersionAndDerivedHash() {
        val original = Asset("a".repeat(64), "wav", 80_044, 48_000, 2, 10_000, "Original")
        val before = Project(assets = frozenListOf(original), pads = (0..127).map { if (it == 0) Pad(0, original.hash, FrameRange(1, 9_999)) else Pad(it) }.frozen(), tempo = Tempo(150_000))
        val draft = BeatStretchEdits.draft(DocumentState(before, 0), StretchTarget(StretchKind.PAD, "0"), 120_000)
        val frames = stretchFrames(original, draft.sourceRange, 120_000, 150_000)
        val rendered = Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Stretched", AssetRole.RENDERED, derivedFrom = original.hash)
        val after = Reducer.reduce(before, BeatStretchEdits.apply(DocumentState(before, 0), draft, rendered)).project
        assertEquals(after, ProjectJson.decode(ProjectJson.encode(after)))
        val root = ProjectJson.parse(ProjectJson.encode(after)).jsonObject
        val recipe = root.getValue("beatStretches").jsonArray.single().jsonObject
        fun reject(candidate: JsonObject) = assertFailsWith<IllegalArgumentException> {
            ProjectJson.decode(ProjectJson.encodeElement(JsonObject(root + ("beatStretches" to JsonArray(listOf(candidate))))))
        }
        recipe.keys.forEach { reject(JsonObject(recipe - it)) }
        reject(JsonObject(recipe + ("extra" to JsonPrimitive(1))))
        reject(JsonObject(recipe + ("algorithmVersion" to JsonPrimitive(2))))
        reject(JsonObject(recipe + ("targetMilliBpm" to JsonPrimitive(120_000))))
        reject(JsonObject(recipe + ("sourceMilliBpm" to JsonPrimitive("120000"))))
        reject(JsonObject(recipe + ("sourceAssetHash" to JsonPrimitive("c".repeat(64)))))
        reject(JsonObject(recipe + ("renderedAssetHash" to JsonPrimitive(original.hash))))
        reject(JsonObject(recipe + ("kind" to JsonPrimitive("OTHER"))))
        reject(JsonObject(recipe + ("id" to JsonPrimitive("00"))))
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(root + ("beatStretches" to JsonArray(listOf(recipe, recipe)))))) }
        assertFailsWith<IllegalArgumentException> { ProjectJson.decode(ProjectJson.encodeElement(JsonObject(root + ("schemaVersion" to JsonPrimitive(14))))) }
    }
}
