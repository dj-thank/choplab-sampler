package com.choplab.sampler.source

import kotlinx.serialization.json.*

/** Bounded metadata parsing. Pagination links are never followed as arbitrary URLs. */
object SpotifyCatalogJson {
    fun page(body: String, request: SpotifyCatalogRequest): SpotifyCatalogPage {
        require(body.length <= 2_000_000)
        val root = Json.parseToJsonElement(body).jsonObject
        val kind = when (request.route) {
            SpotifyCatalogRoute.FAVORITES, SpotifyCatalogRoute.ALBUM_TRACKS -> SpotifyCatalogKind.TRACK
            SpotifyCatalogRoute.SAVED_ALBUMS, SpotifyCatalogRoute.ARTIST_ALBUMS -> SpotifyCatalogKind.ALBUM
            SpotifyCatalogRoute.SEARCH -> request.kind
        }
        val paging = if (request.route == SpotifyCatalogRoute.SEARCH) root.getValue(when (kind) {
            SpotifyCatalogKind.TRACK -> "tracks"; SpotifyCatalogKind.ALBUM -> "albums"; SpotifyCatalogKind.ARTIST -> "artists"
        }).jsonObject else root
        val raw = paging.getValue("items").jsonArray
        val entries = raw.take(request.pageSize).mapNotNull { item -> runCatching {
            val wrapper = item.jsonObject
            val value = when (request.route) {
                SpotifyCatalogRoute.FAVORITES -> wrapper.getValue("track").jsonObject
                SpotifyCatalogRoute.SAVED_ALBUMS -> wrapper.getValue("album").jsonObject
                else -> wrapper
            }
            fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty().filter { it.code >= 32 }.take(240)
            val id = text("id").also { require(Regex("[A-Za-z0-9]{22}").matches(it)) }
            val title = text("name").also { require(it.isNotBlank()) }
            val artist = if (kind == SpotifyCatalogKind.ARTIST) title else value["artists"]?.jsonArray.orEmpty()
                .joinToString(" ") { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty() }.filter { it.code >= 32 }.take(240)
            val album = (value["album"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty().take(240)
                .ifEmpty { if (request.route == SpotifyCatalogRoute.ALBUM_TRACKS) request.title else "" }
            val track = if (kind == SpotifyCatalogKind.TRACK) SourceTrack(title, artist,
                "https://open.spotify.com/track/$id", (value["duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 1000, album,
                value["track_number"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..9999 },
                value["disc_number"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..9999 }) else null
            SpotifyCatalogEntry(id, title, artist, kind, track)
        }.getOrNull() }.distinctBy { it.kind to it.id }
        return SpotifyCatalogPage(request, entries,
            raw.isNotEmpty() && paging["next"] != null && paging["next"] !is JsonNull && request.offset + request.pageSize <= request.maximumOffset,
            paging["total"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }, minOf(raw.size, request.pageSize) - entries.size)
    }
}
