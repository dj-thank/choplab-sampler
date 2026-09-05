package com.choplab.sampler.source

/** Source identity is distinct from Spotify metadata used to find it. */
data class AudioLibraryItem(val id: String, val title: String, val origin: String, val bytes: Long)
data class SourceTrack(val title: String, val artist: String, val spotifyUrl: String, val durationSeconds: Double = 0.0) {
    val query: String get() = "$artist $title".trim()
}
data class YoutubeSource(val id: String, val title: String, val author: String, val durationSeconds: Double = 0.0) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}
enum class SourceSection { LIBRARY, YOUTUBE, SPOTIFY }
data class AudioSourceState(
    val section: SourceSection = SourceSection.LIBRARY,
    val query: String = "",
    val library: List<AudioLibraryItem> = emptyList(),
    val candidates: List<YoutubeSource> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
    val pendingUseId: String? = null,
)

data class SpotifyImportState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val configured: Boolean = false,
    val message: String = "Spotifyにログインすると、お気に入りをタップして取り込めます",
    val tracks: List<SourceTrack> = emptyList(),
    val hasMore: Boolean = false,
)
