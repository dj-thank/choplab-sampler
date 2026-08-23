package com.choplab.desktop.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyApiTest {
    @Test
    fun searchRequestRejectsTheCurrentDevelopmentModeLimitOverTen() {
        assertFailsWith<IllegalArgumentException> {
            SpotifyApiRequestBuilder.searchTracks("access-token", "query", 11)
        }
    }

    @Test
    fun searchRequestUsesMetadataEndpointAndBearerToken() {
        val request = SpotifyApiRequestBuilder.searchTracks("access-token", "Aimer / カタオモイ", 10)

        assertEquals("GET", request.method)
        assertEquals("api.spotify.com", request.uri.host)
        assertTrue(request.uri.path == "/v1/search")
        assertTrue(request.uri.query.contains("type=track"))
        assertTrue(request.uri.query.contains("limit=10"))
        assertTrue(request.headers["Authorization"] == "Bearer access-token")
        assertFalse(request.uri.toString().contains("mp3"))
    }

    @Test
    fun playbackControlRequestDoesNotExposeAnAudioPayload() {
        val request = SpotifyApiRequestBuilder.pausePlayback("access-token")

        assertEquals("PUT", request.method)
        assertEquals("/v1/me/player/pause", request.uri.path)
        assertEquals(null, request.body)
        assertEquals("Bearer access-token", request.headers["Authorization"])
    }

    @Test
    fun savedTracksRequestsMetadataOnlyLibraryEndpoint() {
        val request = SpotifyApiRequestBuilder.savedTracks("access-token", 20)

        assertEquals("GET", request.method)
        assertEquals("https://api.spotify.com/v1/me/tracks?limit=20", request.uri.toString())
        assertEquals(null, request.body)
        assertFalse(request.uri.toString().contains("audio", ignoreCase = true))
        assertFalse(request.uri.toString().contains("download", ignoreCase = true))
    }
}
