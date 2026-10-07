package com.choplab.sampler.source

enum class SpotifyCatalogKind { TRACK, ARTIST, ALBUM }
enum class SpotifyCatalogRoute { FAVORITES, SAVED_ALBUMS, SEARCH, ARTIST_ALBUMS, ALBUM_TRACKS }
data class SpotifyCatalogRequest(val route: SpotifyCatalogRoute, val kind: SpotifyCatalogKind = SpotifyCatalogKind.TRACK,
    val id: String = "", val title: String = "", val query: String = "", val offset: Int = 0) {
    val pageSize: Int get() = if (route in listOf(SpotifyCatalogRoute.SEARCH, SpotifyCatalogRoute.ARTIST_ALBUMS)) 10 else 20
    val maximumOffset: Int get() = if (route == SpotifyCatalogRoute.SEARCH) 1000 else 1999
}
data class SpotifyCatalogEntry(val id: String, val title: String, val artist: String,
    val kind: SpotifyCatalogKind, val track: SourceTrack? = null)
data class SpotifyCatalogPage(val request: SpotifyCatalogRequest, val entries: List<SpotifyCatalogEntry>,
    val hasMore: Boolean, val total: Int?)
