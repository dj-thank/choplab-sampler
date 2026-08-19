package com.choplab.desktop.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyApiTest {
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
}
