package com.choplab.sampler.source

import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import kotlinx.serialization.json.*

object SourceRecipes {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val bareYoutubeHost = Regex("^(?:(?:www\\.|m\\.|music\\.)?youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE)
    fun isUrlInput(input: String): Boolean = input.startsWith("https://", ignoreCase = true) ||
        input.startsWith("http://", ignoreCase = true) || bareYoutubeHost.containsMatchIn(input)

    fun youtubeUrl(input: String): String {
        val trimmed = input.trim()
        val uri = URI(if (bareYoutubeHost.containsMatchIn(trimmed)) "https://$trimmed" else trimmed)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443)) { "YouTubeのHTTPS URLを貼り付けてください" }
        val id = when (uri.host?.lowercase()) {
            "youtu.be" -> uri.path.trim('/').takeIf { '/' !in it }
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').map { it.split('=', limit=2) }
                    .firstOrNull { it.firstOrNull() == "v" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
                uri.path.startsWith("/shorts/") -> uri.path.removePrefix("/shorts/").trim('/')
                else -> null
            }
            else -> null
        }
        require(id != null && videoId.matches(id)) { "動画1本のYouTube URLを使用してください" }
        return "https://www.youtube.com/watch?v=$id"
    }
    fun commonArguments(): List<String> = listOf("--ignore-config", "--no-playlist", "--no-warnings", "--no-cache-dir", "--socket-timeout", "20", "--retries", "1")
    fun searchArguments(query: String): List<String> {
        require(query.isNotBlank() && query.length <= 240 && query.none { it.code < 32 }) { "曲名を240文字以内で入力してください" }
        return commonArguments() + listOf("--flat-playlist", "--dump-single-json", "--skip-download", "ytsearch5:$query")
    }
    fun infoArguments(url: String): List<String> = commonArguments() + listOf("--dump-single-json", "--skip-download", youtubeUrl(url))
    fun downloadArguments(url: String, template: String): List<String> = commonArguments() + listOf(
        "--no-mtime", "--max-filesize", "256M", "-f", "bestaudio/best", "-x", "--audio-format", "wav", "-o", template, youtubeUrl(url))

    private fun objectFrom(body: String): JsonObject {
        require(body.length <= 2_000_000) { "応答が大きすぎます" }
        return Json.parseToJsonElement(body).jsonObject
    }
    private fun source(obj: JsonObject): YoutubeSource? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!videoId.matches(id)) return null
        if (obj["is_live"]?.jsonPrimitive?.booleanOrNull == true) return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.take(240) ?: return null
        val author = (obj["uploader"] ?: obj["channel"])?.jsonPrimitive?.contentOrNull.orEmpty().take(160)
        val duration = obj["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return YoutubeSource(id,title,author,duration)
    }
    fun parseCandidates(body: String): List<YoutubeSource> {
        val root = objectFrom(body)
        return root["entries"]?.jsonArray?.take(5)?.mapNotNull { runCatching { source(it.jsonObject) }.getOrNull() }
            ?: listOfNotNull(source(root))
    }
    fun parseInfo(body: String): YoutubeSource = requireNotNull(source(objectFrom(body))) { "通常の動画音源を取得できません" }.also {
        require(it.durationSeconds.isFinite() && it.durationSeconds in 0.01..600.0) { "取り込みには10分以内の通常動画を使用してください" }
    }
    fun spotifyHasNext(body: String): Boolean = !objectFrom(body)["next"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
    fun parseSpotifySearch(body: String): List<SourceTrack> {
        val items=objectFrom(body).getValue("tracks").jsonObject.getValue("items").jsonArray.take(10)
        return parseSpotifyTracks(JsonObject(mapOf("items" to JsonArray(items.map {
            JsonObject(mapOf("track" to it))
        }))).toString()).distinctBy { it.spotifyUrl }
    }
    fun parseSpotifyTracks(body: String): List<SourceTrack> = objectFrom(body)["items"]?.jsonArray.orEmpty().mapNotNull { item ->
        runCatching {
            val track = item.jsonObject["track"]?.jsonObject ?: return@runCatching null
            val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            if (!Regex("[A-Za-z0-9]{22}").matches(id)) return@runCatching null
            SourceTrack(track.getValue("name").jsonPrimitive.content.take(240),
                track["artists"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() }.take(240),
                "https://open.spotify.com/track/$id", (track["duration_ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0)/1000.0)
        }.getOrNull()
    }
    /** Favor deterministic title/artist/duration agreement; do not silently select an unrelated recording. */
    fun automaticFavorite(track: SourceTrack, candidates: List<YoutubeSource>): YoutubeSource? {
        if (track.artist.isBlank() || !track.durationSeconds.isFinite() || track.durationSeconds !in 0.01..600.0) return null
        return candidates.distinctBy { it.id }
            .filter { matchingFavorite(track, listOf(it)) != null }
            .sortedWith(compareBy<YoutubeSource> {
                if (it.durationSeconds > 0 && it.durationSeconds.isFinite()) kotlin.math.abs(it.durationSeconds - track.durationSeconds) else Double.MAX_VALUE
            }.thenBy { it.id })
            .firstOrNull()
    }

    fun matchingFavorite(track: SourceTrack, candidates: List<YoutubeSource>): YoutubeSource? {
        fun words(value: String): Set<String> = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
            .replace(Regex("""\bpt\.?\s*(\d+)"""), "part $1")
            .replace(Regex("""\bpart(\d+)"""), "part $1")
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 || it.toIntOrNull() != null }.toSet()
        val title = words(track.title)
        val artist = words(track.artist)
        if (title.isEmpty()) return null
        return candidates.distinctBy { it.id }.filter { candidate ->
            val text = words(candidate.title + " " + candidate.author)
            val titleMatch = title.intersect(text).size.toDouble()/title.size
            val artistMatch = artist.isEmpty() || artist.any { it in text }
            val durationMatch = track.durationSeconds <= 0 || candidate.durationSeconds <= 0 ||
                kotlin.math.abs(track.durationSeconds-candidate.durationSeconds) <= maxOf(12.0, track.durationSeconds*0.08)
            val variants=setOf("cover","remix","live","slowed","sped","karaoke","reaction","tutorial","カバー")
            val unexpectedVariant=variants.any { it in words(candidate.title) && it !in title }
            titleMatch == 1.0 && artistMatch && durationMatch && !unexpectedVariant
        }.singleOrNull()
    }
}
