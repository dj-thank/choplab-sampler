package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicit schema: deterministic keys, strict types, duplicate-key and unknown-field rejection. */
object ProjectJson {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }

    fun encode(project: Project): ByteArray = encodeElement(toJson(project))
    fun decode(bytes: ByteArray): Project = fromJson(parse(bytes).obj())
    internal fun encodeElement(element: JsonElement): ByteArray = element.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    internal fun parse(bytes: ByteArray): JsonElement {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        StrictJson(text).validate()
        return json.parseToJsonElement(text)
    }

    internal fun toJson(p: Project): JsonObject = obj(
        "schemaVersion" to num(p.schemaVersion), "id" to str(p.id), "title" to str(p.title),
        "tempo" to obj("milliBpm" to num(p.tempo.milliBpm), "swingPermille" to num(p.tempo.swingPermille)),
        "assets" to arr(p.assets.map { a -> obj("hash" to str(a.hash), "extension" to str(a.extension), "byteCount" to num(a.byteCount),
            "sampleRate" to num(a.sampleRate), "channels" to num(a.channels), "frames" to num(a.frames), "name" to str(a.name),
            "role" to str(a.role.name), "required" to bool(a.required), "derivedFrom" to nullable(a.derivedFrom)) }),
        "banks" to arr(p.banks.map { b -> obj("id" to num(b.id), "name" to str(b.name), "color" to num(b.color), "role" to str(b.role)) }),
        "pads" to arr(p.pads.map { a -> obj("id" to num(a.id), "assetHash" to nullable(a.assetHash), "range" to (a.range?.let(::range) ?: JsonNull),
            "name" to str(a.name), "mode" to str(a.mode.name), "pitchSemitones" to num(a.pitchSemitones), "gain" to num(a.gain), "pan" to num(a.pan),
            "reverse" to bool(a.reverse), "chokeGroup" to num(a.chokeGroup), "attackFrames" to num(a.attackFrames),
            "releaseFrames" to num(a.releaseFrames), "loopCrossfadeFrames" to num(a.loopCrossfadeFrames), "decayFrames" to num(a.decayFrames), "sustainLevel" to num(a.sustainLevel)) }),
        "patterns" to arr(p.patterns.map { v -> obj("id" to str(v.id), "name" to str(v.name), "bars" to num(v.bars),
            "notes" to arr(v.notes.map { n -> obj("tick" to num(n.tick), "padId" to num(n.padId), "velocity" to num(n.velocity)) })) }),
        "song" to arr(p.song.map { v -> obj("patternId" to str(v.patternId), "repeats" to num(v.repeats)) }),
        "tracks" to arr(p.tracks.map { v -> obj("id" to str(v.id), "name" to str(v.name), "kind" to str(v.kind.name), "gain" to num(v.gain),
            "pan" to num(v.pan), "mute" to bool(v.mute), "solo" to bool(v.solo)) }),
        "clips" to arr(p.clips.map { v -> obj("id" to str(v.id), "trackId" to str(v.trackId), "assetHash" to str(v.assetHash), "range" to range(v.range), "startTick" to num(v.startTick), "timelineStartFrame" to (v.timelineStartFrame?.let(::num) ?: JsonNull), "gain" to num(v.gain), "pan" to num(v.pan)) }),
        "lyrics" to arr(p.lyrics.map { v -> obj("id" to str(v.id), "text" to str(v.text), "startTick" to num(v.startTick), "endTick" to num(v.endTick),
            "words" to arr(v.words.map { w -> obj("text" to str(w.text), "startTick" to num(w.startTick), "endTick" to num(w.endTick)) })) }),
        "takes" to arr(p.takes.map { v -> obj("id" to str(v.id), "trackId" to str(v.trackId), "assetHash" to str(v.assetHash), "range" to range(v.range),
            "timelineStartFrame" to num(v.timelineStartFrame), "compensationFrames" to num(v.compensationFrames)) }),
        "source" to (p.source?.let { s -> obj("assetHash" to str(s.assetHash), "range" to range(s.range), "markers" to arr(s.markers.map(::num))) } ?: JsonNull),
    )

    internal fun fromJson(p: JsonObject): Project {
        p.fields("schemaVersion", "id", "title", "tempo", "assets", "banks", "pads", "patterns", "song", "tracks", "clips", "lyrics", "takes", "source")
        require(p.int("schemaVersion") == 10) { "Unsupported project schema" }
        val tempo = p.getValue("tempo").obj().fields("milliBpm", "swingPermille")
        return Project(
            id = p.string("id"), title = p.string("title"), tempo = Tempo(tempo.int("milliBpm"), tempo.int("swingPermille")),
            assets = p.list("assets", ProjectLimits.MAX_ASSETS) { e ->
                val a = e.obj().fields("hash", "extension", "byteCount", "sampleRate", "channels", "frames", "name", "role", "required", "derivedFrom")
                Asset(a.string("hash"), a.string("extension"), a.long("byteCount"), a.int("sampleRate"), a.int("channels"), a.long("frames"),
                    a.string("name"), AssetRole.valueOf(a.string("role")), a.boolean("required"), a.optionalString("derivedFrom"))
            },
            banks = p.list("banks", 8) { e -> e.obj().fields("id", "name", "color", "role").let { Bank(it.int("id"), it.string("name"), it.int("color"), it.string("role")) } },
            pads = p.list("pads", 128) { e ->
                val a = e.obj().fields("id", "assetHash", "range", "name", "mode", "pitchSemitones", "gain", "pan", "reverse", "chokeGroup", "attackFrames", "releaseFrames", "loopCrossfadeFrames", "decayFrames", "sustainLevel")
                Pad(a.int("id"), a.optionalString("assetHash"), a.getValue("range").let { if (it == JsonNull) null else readRange(it) }, a.string("name"),
                    PlayMode.valueOf(a.string("mode")), a.double("pitchSemitones"), a.float("gain"), a.float("pan"), a.boolean("reverse"),
                    a.int("chokeGroup"), a.int("attackFrames"), a.int("releaseFrames"), a.int("loopCrossfadeFrames"), a.int("decayFrames"), a.float("sustainLevel"))
            },
            patterns = p.list("patterns", 128) { e ->
                val a = e.obj().fields("id", "name", "bars", "notes")
                Pattern(a.string("id"), a.string("name"), a.int("bars"), a.list("notes", 4096) { n -> n.obj().fields("tick", "padId", "velocity").let { Note(it.int("tick"), it.int("padId"), it.float("velocity")) } })
            },
            song = p.list("song", 1024) { e -> e.obj().fields("patternId", "repeats").let { SongSection(it.string("patternId"), it.int("repeats")) } },
            tracks = p.list("tracks", 64) { e ->
                val a = e.obj().fields("id", "name", "kind", "gain", "pan", "mute", "solo")
                Track(a.string("id"), a.string("name"), TrackKind.valueOf(a.string("kind")), a.float("gain"), a.float("pan"), a.boolean("mute"), a.boolean("solo"))
            },
            clips = p.list("clips", 4096) { e ->
                val a = e.obj()
                val required = setOf("id", "trackId", "assetHash", "range", "startTick")
                require(a.keys.containsAll(required) && (a.keys - required).all { it in setOf("timelineStartFrame", "gain", "pan") })
                val frame = a["timelineStartFrame"].let { if (it == null || it == JsonNull) null else it.strictLong() }
                Clip(a.string("id"), a.string("trackId"), a.string("assetHash"), readRange(a.getValue("range")), a.long("startTick"), frame,
                    if ("gain" in a) a.float("gain") else 1f, if ("pan" in a) a.float("pan") else 0f)
            },
            lyrics = p.list("lyrics", 4096) { e ->
                val a = e.obj().fields("id", "text", "startTick", "endTick", "words")
                LyricLine(a.string("id"), a.string("text"), a.long("startTick"), a.long("endTick"), a.list("words", 256) { n ->
                    n.obj().fields("text", "startTick", "endTick").let { LyricWord(it.string("text"), it.long("startTick"), it.long("endTick")) }
                })
            },
            takes = p.list("takes", 1024) { e ->
                val a = e.obj().fields("id", "trackId", "assetHash", "range", "timelineStartFrame", "compensationFrames")
                Take(a.string("id"), a.string("trackId"), a.string("assetHash"), readRange(a.getValue("range")), a.long("timelineStartFrame"), a.int("compensationFrames"))
            },
            source = p.getValue("source").let { e -> if (e == JsonNull) null else {
                val a = e.obj().fields("assetHash", "range", "markers")
                Source(a.string("assetHash"), readRange(a.getValue("range")), a.list("markers", 127) { it.strictLong() })
            } },
        )
    }
    private fun range(r: FrameRange) = obj("start" to num(r.start), "end" to num(r.end))
    private fun readRange(e: JsonElement): FrameRange = e.obj().fields("start", "end").let { FrameRange(it.long("start"), it.long("end")) }
}

internal fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
internal fun arr(values: List<JsonElement>) = JsonArray(values)
internal fun str(value: String) = JsonPrimitive(value)
internal fun num(value: Number) = JsonPrimitive(value)
internal fun bool(value: Boolean) = JsonPrimitive(value)
internal fun nullable(value: String?): JsonElement = value?.let(::str) ?: JsonNull
internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected object")
internal fun JsonObject.fields(vararg names: String): JsonObject { require(keys == names.toSet()) { "Missing or unknown JSON field" }; return this }
private fun JsonElement.primitive(): JsonPrimitive = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected primitive")
internal fun JsonObject.string(key: String): String = getValue(key).primitive().let { require(it.isString); it.content }
internal fun JsonObject.optionalString(key: String): String? = if (getValue(key) == JsonNull) null else string(key)
internal fun JsonElement.strictLong(): Long = primitive().let { require(!it.isString); requireNotNull(it.longOrNull) }
internal fun JsonObject.long(key: String): Long = getValue(key).strictLong()
internal fun JsonObject.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
internal fun JsonObject.double(key: String): Double = getValue(key).primitive().let { require(!it.isString); requireNotNull(it.doubleOrNull).also { n -> require(n.isFinite()) } }
internal fun JsonObject.float(key: String): Float = double(key).toFloat().also { require(it.isFinite()) }
internal fun JsonObject.boolean(key: String): Boolean = getValue(key).primitive().let { require(!it.isString); requireNotNull(it.booleanOrNull) }
internal fun <T> JsonObject.list(key: String, maximum: Int, read: (JsonElement) -> T): FrozenList<T> {
    val array = getValue(key) as? JsonArray ?: throw IllegalArgumentException("Expected array")
    require(array.size <= maximum)
    return array.map(read).frozen()
}

/** JSON libraries commonly accept repeated object keys; reject them before tree construction. */
private class StrictJson(private val text: String) {
    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private var cursor = 0
    private var tokens = 0
    fun validate() { value(0); whitespace(); require(cursor == text.length) }
    private fun whitespace() { while (cursor < text.length && text[cursor] in " \r\n\t") cursor++ }
    private fun take(c: Char) { whitespace(); require(cursor < text.length && text[cursor++] == c) }
    private fun value(depth: Int) {
        require(depth <= 32 && ++tokens <= 300_000)
        whitespace(); require(cursor < text.length)
        when (text[cursor]) {
            '{' -> {
                cursor++; whitespace()
                val keys = mutableSetOf<String>()
                if (cursor < text.length && text[cursor] == '}') { cursor++; return }
                while (true) {
                    whitespace(); val key = string(); require(keys.add(key)) { "Duplicate JSON key" }
                    take(':'); value(depth + 1); whitespace()
                    require(cursor < text.length)
                    if (text[cursor] == '}') { cursor++; break }
                    take(',')
                }
            }
            '[' -> {
                cursor++; whitespace()
                if (cursor < text.length && text[cursor] == ']') { cursor++; return }
                while (true) {
                    value(depth + 1); whitespace(); require(cursor < text.length)
                    if (text[cursor] == ']') { cursor++; break }
                    take(',')
                }
            }
            '"' -> string()
            else -> {
                val start = cursor
                while (cursor < text.length && text[cursor] !in ",]} \r\n\t") cursor++
                require(cursor > start && cursor - start <= 64)
                val token = text.substring(start, cursor)
                require(token == "null" || token == "true" || token == "false" || numberPattern.matches(token))
            }
        }
    }
    private fun string(): String {
        val start = cursor
        require(cursor < text.length && text[cursor++] == '"')
        while (cursor < text.length) {
            val ch = text[cursor++]
            require(ch >= ' ')
            if (ch == '\\') { require(cursor < text.length); cursor++ }
            else if (ch == '"') {
                require(cursor - start <= 32_768)
                return Json.parseToJsonElement(text.substring(start, cursor)).jsonPrimitive.content
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }
}
