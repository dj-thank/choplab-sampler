package com.choplab.core.inception

import com.choplab.core.model.frozen
import kotlinx.serialization.json.*

/** Explicit, strict, versioned format. No reflection, model/credential/session state or music schema. */
object InceptionJson {
    fun encode(value: InceptionWorkspace): String = buildJsonObject {
        put("version", value.version); put("id", value.id); put("title", value.title); put("revision", value.revision)
        put("focus", buildJsonObject { put("center", value.focus.center); put("feeling", value.focus.feeling); put("direction", value.focus.direction); put("unresolved", value.focus.unresolved) })
        put("materials", JsonArray(value.materials.map { m -> buildJsonObject {
            put("id", m.id); put("original", m.original); put("correction", m.correction?.let(::JsonPrimitive) ?: JsonNull)
            put("kind", m.kind.name); put("audioRef", m.audioRef?.let(::JsonPrimitive) ?: JsonNull)
            put("classification", m.classification.name)
            put("correctionHistory", strings(m.correctionHistory))
        } }))
        put("concepts", JsonArray(value.concepts.map(::concept)))
        put("decisions", JsonArray(value.decisions.map { d -> buildJsonObject {
            put("conceptId", d.conceptId); put("kind", d.kind.name); put("atRevision", d.atRevision); put("snapshot", concept(d.snapshot))
            put("reason", d.reason)
        } }))
        put("relations", JsonArray(value.relations.map { r -> buildJsonObject {
            put("id", r.id); put("from", r.from); put("to", r.to); put("kind", r.kind.name); put("explanation", r.explanation)
        } }))
        put("hypotheses", JsonArray(value.hypotheses.map { h -> buildJsonObject {
            put("id", h.id); put("statement", h.statement); put("evidence", strings(h.evidence)); put("confirmed", h.confirmed)
        } }))
    }.toString().also { require(it.encodeToByteArray().size <= InceptionLimits.MAX_BYTES) }

    fun decode(raw: String): InceptionWorkspace {
        val root = parse(raw)
        root.exact("version", "id", "title", "revision", "materials", "concepts", "decisions", "relations", "hypotheses", "focus")
        if (root.number("version") != InceptionLimits.VERSION.toLong()) throw InceptionException(InceptionProblem.UNKNOWN_VERSION)
        val focus = root.getValue("focus").jsonObject
        focus.exact("center", "feeling", "direction", "unresolved")
        return InceptionWorkspace(root.string("id"), root.string("title"), root.number("revision"),
            root.array("materials", 128).map { value -> value.jsonObject.let { m ->
                m.exact("id", "original", "correction", "kind", "audioRef", "classification", "correctionHistory")
                InceptionMaterial(m.string("id"), m.string("original"), m.nullableString("correction"), MaterialKind.valueOf(m.string("kind")), m.nullableString("audioRef"), MaterialClassification.valueOf(m.string("classification")), m.stringList("correctionHistory", 32).frozen())
            } }.frozen(), root.array("concepts", 3).map { readConcept(it.jsonObject) }.frozen(),
            root.array("decisions", 256).map { value -> value.jsonObject.let { d ->
                d.exact("conceptId", "kind", "atRevision", "snapshot", "reason")
                InceptionDecision(d.string("conceptId"), DecisionKind.valueOf(d.string("kind")), d.number("atRevision"), readConcept(d.getValue("snapshot").jsonObject), d.string("reason"))
            } }.frozen(), root.array("relations", 256).map { value -> value.jsonObject.let { r ->
                r.exact("id", "from", "to", "kind", "explanation")
                InceptionRelation(r.string("id"), r.string("from"), r.string("to"), RelationKind.valueOf(r.string("kind")), r.string("explanation"))
            } }.frozen(), root.array("hypotheses", 64).map { value -> value.jsonObject.let { h ->
                h.exact("id", "statement", "evidence", "confirmed")
                val confirmed = h.getValue("confirmed").jsonPrimitive
                require(!confirmed.isString)
                InceptionHypothesis(h.string("id"), h.string("statement"), h.stringList("evidence", 32).frozen(), requireNotNull(confirmed.booleanOrNull))
            } }.frozen(), focus = InceptionFocus(focus.string("center"), focus.string("feeling"), focus.string("direction"), focus.string("unresolved")))
    }
    fun concept(c: InceptionConcept) = buildJsonObject {
        put("id", c.id); put("title", c.title); put("statement", c.statement); put("evidence", strings(c.evidence)); put("origin", c.origin.name)
    }
    fun readConcept(c: JsonObject): InceptionConcept {
        c.exact("id", "title", "statement", "evidence", "origin")
        return InceptionConcept(c.string("id"), c.string("title"), c.string("statement"), c.stringList("evidence", 32).frozen(), ConceptOrigin.valueOf(c.string("origin")))
    }
    fun parse(raw: String): JsonObject {
        require(raw.length <= InceptionLimits.MAX_BYTES && raw.encodeToByteArray().size <= InceptionLimits.MAX_BYTES)
        InceptionStrictJson(raw).validate()
        return Json.parseToJsonElement(raw).jsonObject
    }
    fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
internal fun JsonObject.exact(vararg expected: String) { require(keys == expected.toSet()) }
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.nullableString(key: String): String? = if (getValue(key) == JsonNull) null else string(key)
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); requireNotNull(it.longOrNull) }
internal fun JsonObject.array(key: String, max: Int): JsonArray = getValue(key).jsonArray.also { require(it.size <= max) }
internal fun JsonObject.stringList(key: String, max: Int) = array(key, max).map { it.jsonPrimitive.let { p -> require(p.isString); p.content } }

/** Bounded lexical validation also rejects duplicate keys (including escaped aliases). */
private class InceptionStrictJson(private val text: String) {
    private var at = 0
    private var tokens = 0
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    fun validate() { value(0); space(); require(at == text.length) }
    private fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
    private fun take(c: Char) { space(); require(at < text.length && text[at++] == c) }
    private fun value(depth: Int) {
        require(depth <= 16 && ++tokens <= 32_000); space(); require(at < text.length)
        when (text[at]) {
            '{' -> {
                at++; space(); val keys = mutableSetOf<String>()
                if (at < text.length && text[at] == '}') { at++; return }
                while (true) { space(); require(keys.add(string())); take(':'); value(depth + 1); space()
                    require(at < text.length); if (text[at] == '}') { at++; break }; take(',') }
            }
            '[' -> {
                at++; space(); if (at < text.length && text[at] == ']') { at++; return }
                while (true) { value(depth + 1); space(); require(at < text.length)
                    if (text[at] == ']') { at++; break }; take(',') }
            }
            '"' -> string()
            else -> { val start = at; while (at < text.length && text[at] !in ",]} \r\n\t") at++
                require(at > start && at - start <= 64); val token = text.substring(start, at)
                require(token in listOf("true", "false", "null") || number.matches(token)) }
        }
    }
    private fun string(): String {
        val start = at; require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            val c = text[at++]; require(c >= ' ')
            if (c == '\\') { require(at < text.length); at++ }
            else if (c == '"') { require(at - start <= 65_536); return Json.parseToJsonElement(text.substring(start, at)).jsonPrimitive.content }
        }
        error("Invalid JSON string")
    }
}
