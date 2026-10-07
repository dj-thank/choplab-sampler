package com.choplab.sampler.source

import kotlinx.serialization.json.*

/** Optional, versioned sidecar. Old audio-only ZIPs remain readable. */
internal object LibraryBundleMetadata {
    const val ENTRY = "choplab-library-v1.json"
    const val MAX_BYTES = 262_144
    data class Record(val title: String, val metadata: AudioLibraryMetadata)
    private val fields = setOf("id", "title", "artist", "album", "trackNumber", "discNumber")

    fun encode(items: List<AudioLibraryItem>): ByteArray = buildJsonObject {
        put("version", 1)
        putJsonArray("items") {
            items.forEach { item -> add(buildJsonObject {
                put("id", item.id); put("title", item.title)
                put("artist", item.artist); put("album", item.album)
                put("trackNumber", item.trackNumber?.let(::JsonPrimitive) ?: JsonNull)
                put("discNumber", item.discNumber?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        }
    }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): Map<String, Record> {
        require(bytes.size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = Json.parseToJsonElement(text).jsonObject
        // Version 1 uses the compact canonical form emitted above; duplicate keys cannot be hidden.
        require(root.toString() == text)
        require(root.keys == setOf("version", "items"))
        val version = root.getValue("version").jsonPrimitive
        require(!version.isString && version.intOrNull == 1)
        val records = root.getValue("items").jsonArray
        require(records.size in 1..32)
        val result = linkedMapOf<String, Record>()
        records.forEach { element ->
            val item = element.jsonObject
            require(item.keys == fields)
            fun text(key: String): String = item.getValue(key).jsonPrimitive.let {
                require(it.isString)
                it.content.also { value -> require(value.length <= 240 && value.none { c -> c.code < 32 }) }
            }
            fun number(key: String): Int? = item.getValue(key).let {
                if (it == JsonNull) null else it.jsonPrimitive.let { value ->
                    require(!value.isString)
                    requireNotNull(value.intOrNull).also { number -> require(number in 1..9999) }
                }
            }
            val id = text("id").also { require(Regex("[a-f0-9]{64}").matches(it)) }
            val title = text("title").also { require(it.isNotBlank()) }
            require(result.put(id, Record(title, AudioLibraryMetadata(text("artist"), text("album"),
                number("trackNumber"), number("discNumber")))) == null)
        }
        return result
    }
}
