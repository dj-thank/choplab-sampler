package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.frozen
import kotlinx.serialization.json.*

/** Google generateContent wire format. All parsing is bounded and outside the audio engine. */
internal object GeminiLyricWire {
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    const val MAX_RESPONSE_BYTES = 131_072
    private fun string(max: Int) = buildJsonObject { put("type", "string"); put("maxLength", max) }
    private fun integer(max: Int) = buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", max) }
    private fun obj(vararg fields: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(fields.toMap()))
        put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
    }
    private fun array(items: JsonElement, maximum: Int) = buildJsonObject {
        put("type", "array"); put("minItems", 1); put("maxItems", maximum); put("items", items)
    }
    private val schema = obj("title" to string(160), "language" to buildJsonObject {
        put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("ja"), JsonPrimitive("en"))))
    }, "sections" to array(obj("name" to string(80), "kind" to buildJsonObject {
        put("type", "string"); put("enum", JsonArray(LyricSectionKind.entries.map { JsonPrimitive(it.name.lowercase()) }))
    }, "bars" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 64) },
        "lines" to array(obj("text" to string(512), "reading" to string(512), "mora" to integer(512),
            "rhymeVowels" to string(512)), 16)), 8))

    fun request(input: LyricRequest): String = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text",
                "Write original lyrics following the user's explicit fields. Return only the specified lyric JSON. " +
                "Use the requested language, at most 8 sections, 16 lines per section and 64 lines total. " +
                "For Japanese, reading must use hiragana or katakana, with punctuation/spaces allowed. " +
                "mora and rhymeVowels are provisional: the app recalculates them. Do not request tools, URLs or audio. " +
                "Keep requested lines when possible. Section bars are suggestions, not measured audio timing.") }) })
        })
        put("contents", buildJsonArray { add(buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", buildJsonObject {
                put("theme", input.theme); put("mood", input.mood); put("language", input.language.code)
                put("style", input.style.name.lowercase()); put("structure", input.structure)
                put("rhyme", input.rhyme); put("keepLines", input.keepLines)
            }.toString()) }) })
        }) })
        put("generationConfig", buildJsonObject {
            put("candidateCount", 1); put("maxOutputTokens", input.maxOutputTokens)
            put("responseFormat", buildJsonObject { put("text", buildJsonObject {
                put("mimeType", "APPLICATION_JSON"); put("schema", schema)
            }) })
        })
    }.toString()

    fun decode(body: String, language: LyricLanguage): LyricProviderResult.Success {
        require(body.length <= MAX_RESPONSE_BYTES)
        val root = json.parseToJsonElement(body).jsonObject
        require(root["promptFeedback"]?.jsonObject?.get("blockReason") == null)
        val candidates = root["candidates"]!!.jsonArray
        require(candidates.size == 1)
        val candidate = candidates.single().jsonObject
        require(candidate["finishReason"]?.jsonPrimitive?.content == "STOP")
        val parts = candidate["content"]!!.jsonObject["parts"]!!.jsonArray
        require(parts.size in 1..16)
        val content = buildString {
            for (partValue in parts) {
                val part = partValue.jsonObject
                if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) continue
                require(part.keys.all { it == "text" || it == "thought" || it == "thoughtSignature" })
                append(part.text("text", 65_536))
            }
        }
        require(content.length <= 65_536)
        val proposal = json.parseToJsonElement(content).jsonObject
        proposal.keysExactly("title", "language", "sections")
        require(proposal.text("language", 2) == language.code)
        val sections = proposal["sections"]!!.jsonArray
        require(sections.size in 1..8)
        val parsed = LyricProposal(proposal.text("title", 160), language, sections.map { raw ->
            val section = raw.jsonObject
            section.keysExactly("name", "kind", "bars", "lines")
            val kind = LyricSectionKind.entries.single { it.name.lowercase() == section.text("kind", 16) }
            val lines = section["lines"]!!.jsonArray
            require(lines.size in 1..16)
            ProposalSection(section.text("name", 80), kind, section.integer("bars", 1..64).toInt(), lines.map { value ->
                val line = value.jsonObject
                line.keysExactly("text", "reading", "mora", "rhymeVowels")
                // Validate the schema, then discard provider phonetic counts in favor of deterministic local values.
                line.integer("mora", 0..512); line.text("rhymeVowels", 512)
                ProposalLine.create(line.text("text", 512), line.text("reading", 512), language)
            }.frozen())
        }.frozen())
        val usage = root["usageMetadata"]?.let { value ->
            val fields = value.jsonObject
            fun count(name: String): Long? = fields[name]?.let { fields.integer(name, 0..1_000_000_000) }
            LyricUsage(count("promptTokenCount"), count("candidatesTokenCount"), count("totalTokenCount"))
        }
        val version = root["modelVersion"]?.let { root.text("modelVersion", 128) }
        return LyricProviderResult.Success(parsed, usage, version)
    }
    private fun JsonObject.keysExactly(vararg keys: String) { require(this.keys == keys.toSet()) }
    private fun JsonObject.text(name: String, maximum: Int): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString && value.content.length <= maximum)
        return value.content
    }
    private fun JsonObject.integer(name: String, range: IntRange): Long {
        val value = getValue(name).jsonPrimitive
        require(!value.isString)
        val number = requireNotNull(value.longOrNull)
        require(number in range.first.toLong()..range.last.toLong())
        return number
    }
}
