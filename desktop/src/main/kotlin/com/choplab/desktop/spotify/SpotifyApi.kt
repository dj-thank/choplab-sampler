package com.choplab.desktop.spotify

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SpotifyApiRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String>,
    val body: String? = null,
)

object SpotifyApiRequestBuilder {
    fun searchTracks(accessToken: String, query: String, limit: Int = 20): SpotifyApiRequest {
        require(query.isNotBlank()) { "Spotify search query must not be blank" }
        require(limit in 1..50) { "Spotify search limit must be between 1 and 50" }
        val uri = URI(
            "https://api.spotify.com/v1/search?" +
                "q=${encode(query)}&type=track&limit=$limit",
        )
        return authorized("GET", uri, accessToken)
    }

    fun currentPlayback(accessToken: String): SpotifyApiRequest =
        authorized("GET", URI("https://api.spotify.com/v1/me/player"), accessToken)

    fun savedTracks(accessToken: String, limit: Int = 20): SpotifyApiRequest {
        require(limit in 1..50) { "Spotify saved-track limit must be between 1 and 50" }
        return authorized("GET", URI("https://api.spotify.com/v1/me/tracks?limit=$limit"), accessToken)
    }

    fun pausePlayback(accessToken: String): SpotifyApiRequest =
        authorized("PUT", URI("https://api.spotify.com/v1/me/player/pause"), accessToken)

    fun resumePlayback(accessToken: String): SpotifyApiRequest =
        authorized("PUT", URI("https://api.spotify.com/v1/me/player/play"), accessToken)

    private fun authorized(method: String, uri: URI, accessToken: String): SpotifyApiRequest {
        require(accessToken.isNotBlank()) { "Spotify access token must not be blank" }
        return SpotifyApiRequest(
            method = method,
            uri = uri,
            headers = mapOf("Authorization" to "Bearer $accessToken"),
        )
    }
}

data class SpotifyApiResponse(
    val statusCode: Int,
    val body: String,
)

fun interface SpotifyApiTransport {
    fun send(request: SpotifyApiRequest): SpotifyApiResponse
}

class JdkSpotifyApiTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : SpotifyApiTransport {
    override fun send(request: SpotifyApiRequest): SpotifyApiResponse {
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val bodyPublisher = request.body?.let(HttpRequest.BodyPublishers::ofString)
            ?: HttpRequest.BodyPublishers.noBody()
        when (request.method) {
            "GET" -> builder.GET()
            "PUT" -> builder.PUT(bodyPublisher)
            "POST" -> builder.POST(bodyPublisher)
            else -> error("Unsupported Spotify method: ${request.method}")
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return SpotifyApiResponse(response.statusCode(), response.body())
    }
}

class SpotifyApi(
    private val transport: SpotifyApiTransport = JdkSpotifyApiTransport(),
) {
    fun searchTracks(accessToken: String, query: String, limit: Int = 20): SpotifyApiResponse =
        transport.send(SpotifyApiRequestBuilder.searchTracks(accessToken, query, limit))

    fun currentPlayback(accessToken: String): SpotifyApiResponse =
        transport.send(SpotifyApiRequestBuilder.currentPlayback(accessToken))

    fun savedTracks(accessToken: String, limit: Int = 20): SpotifyApiResponse =
        transport.send(SpotifyApiRequestBuilder.savedTracks(accessToken, limit))

    fun pausePlayback(accessToken: String): SpotifyApiResponse =
        transport.send(SpotifyApiRequestBuilder.pausePlayback(accessToken))

    fun resumePlayback(accessToken: String): SpotifyApiResponse =
        transport.send(SpotifyApiRequestBuilder.resumePlayback(accessToken))
}
