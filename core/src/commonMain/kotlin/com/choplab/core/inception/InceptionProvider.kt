package com.choplab.core.inception

import com.choplab.core.ai.GoogleLyricAttempt
import com.choplab.core.ai.SessionApiKey
import com.choplab.core.model.*
import kotlinx.serialization.json.*

class InceptionRequest(val id: String, val revision: Long, val materials: FrozenList<InceptionMaterial>, val different: Boolean = false) {
    init { requireId(id); require(revision >= 0); require(materials.size in 1..32)
        require(materials.map { it.id }.distinct().size == materials.size)
        require(materials.sumOf { it.currentText.encodeToByteArray().size } <= 16_384) }
    override fun toString() = "InceptionRequest(id=$id, revision=$revision, materials=${materials.size})"
}
class InceptionResponse(val requestId: String, val revision: Long, val concepts: FrozenList<InceptionConcept>,
    val relations: FrozenList<InceptionRelation> = frozenListOf(), val questions: FrozenList<String> = frozenListOf()) {
    fun validate(request: InceptionRequest) {
        require(requestId == request.id && revision == request.revision && concepts.size <= 3)
        require(concepts.map { it.id }.distinct().size == concepts.size)
        val selected = request.materials.map { it.id }.toSet()
        require(concepts.all { it.origin == ConceptOrigin.PROVIDER && selected.containsAll(it.evidence) })
        require(relations.size <= 16 && relations.map { it.id }.distinct().size == relations.size)
        require(relations.all { it.from in selected && it.to in selected })
        require(questions.size <= 1); questions.forEach { inceptionText(it, 512); require(!it.contains('\n')) }
    }
    override fun toString() = "InceptionResponse(concepts=${concepts.size})"
}
sealed interface InceptionProviderResult {
    class Success(val response: InceptionResponse) : InceptionProviderResult
    data class Failure(val problem: InceptionProblem, val costUnknown: Boolean = false) : InceptionProviderResult
}
interface InceptionProvider {
    suspend fun propose(request: InceptionRequest, model: String, key: SessionApiKey, attempt: GoogleLyricAttempt): InceptionProviderResult
    fun close()
}
/** Provider text protocol: no unselected material, project identity, audio, persistent preference or prompt history. */
object InceptionWire {
    fun input(r: InceptionRequest) = buildJsonObject {
        put("requestId", r.id); put("revision", r.revision); put("different", r.different)
        put("materials", JsonArray(r.materials.map { m -> buildJsonObject { put("id", m.id); put("text", m.currentText); put("classification", m.classification.name) } }))
    }
    fun decode(raw: String, request: InceptionRequest): InceptionResponse {
        val root = InceptionJson.parse(raw)
        root.exact("requestId", "revision", "concepts", "relations", "questions")
        val response = InceptionResponse(root.string("requestId"), root.number("revision"),
            root.array("concepts", 3).map { value -> value.jsonObject.let { c ->
                c.exact("id", "title", "statement", "evidence")
                InceptionConcept(c.string("id"), c.string("title"), c.string("statement"), c.stringList("evidence", 32).frozen(), ConceptOrigin.PROVIDER)
            } }.frozen(), root.array("relations", 16).map { value -> value.jsonObject.let { r ->
                r.exact("id", "from", "to", "kind", "explanation")
                InceptionRelation(r.string("id"), r.string("from"), r.string("to"), RelationKind.valueOf(r.string("kind")), r.string("explanation"))
            } }.frozen(), root.stringList("questions", 1).frozen())
        response.validate(request)
        return response
    }
    val schema: JsonObject = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("required", InceptionJson.strings(listOf("requestId", "revision", "concepts", "relations", "questions")))
        put("properties", buildJsonObject {
            put("requestId", stringSchema(64)); put("revision", buildJsonObject { put("type", "integer"); put("minimum", 0) })
            put("questions", buildJsonObject { put("type", "array"); put("maxItems", 1); put("items", stringSchema(512)) })
            put("relations", buildJsonObject { put("type", "array"); put("maxItems", 16)
                put("items", buildJsonObject { put("type", "object"); put("additionalProperties", false)
                    put("required", InceptionJson.strings(listOf("id", "from", "to", "kind", "explanation")))
                    put("properties", buildJsonObject {
                        put("id", stringSchema(64)); put("from", stringSchema(64)); put("to", stringSchema(64)); put("explanation", stringSchema(1024))
                        put("kind", buildJsonObject { put("type", "string"); put("enum", InceptionJson.strings(RelationKind.entries.map { it.name })) })
                    })
                })
            })
            put("concepts", buildJsonObject { put("type", "array"); put("minItems", 0); put("maxItems", 3)
                put("items", buildJsonObject { put("type", "object"); put("additionalProperties", false)
                    put("required", InceptionJson.strings(listOf("id", "title", "statement", "evidence")))
                    put("properties", buildJsonObject {
                        put("id", stringSchema(64)); put("title", stringSchema(160)); put("statement", stringSchema(4096))
                        put("evidence", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 32); put("items", stringSchema(64)) })
                    })
                })
            })
        })
    }
    private fun stringSchema(maximum: Int) = buildJsonObject { put("type", "string"); put("maxLength", maximum) }
}

enum class InceptionVoiceMode { QUIET, JAM }
/** Actual ASR availability is a host capability. Unavailable hosts never return a fake successful transcript. */
interface InceptionVoice {
    val available: Boolean
    /** Explicit utterance boundary: returns only after the user finishes a bounded capture. */
    suspend fun utterance(): InceptionMaterial
    fun stop()
}
object UnavailableInceptionVoice : InceptionVoice {
    override val available = false
    override suspend fun utterance(): InceptionMaterial = throw InceptionException(InceptionProblem.VOICE_UNAVAILABLE)
    override fun stop() {}
}
