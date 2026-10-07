package com.choplab.desktop.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyApiTest {
    @Test fun catalogRequestsUseTypedBoundedEndpointsAndDoNotFollowProviderLinks() {
        val id = "0123456789012345678901"
        for ((route, path) in listOf(
            com.choplab.sampler.source.SpotifyCatalogRoute.FAVORITES to "/v1/me/tracks",
            com.choplab.sampler.source.SpotifyCatalogRoute.SAVED_ALBUMS to "/v1/me/albums",
            com.choplab.sampler.source.SpotifyCatalogRoute.ARTIST_ALBUMS to "/v1/artists/$id/albums",
            com.choplab.sampler.source.SpotifyCatalogRoute.ALBUM_TRACKS to "/v1/albums/$id/tracks")) {
            val request = SpotifyApiRequestBuilder.catalogPage("token", com.choplab.sampler.source.SpotifyCatalogRequest(route, id = id, offset = 20))
            assertEquals(path, request.uri.path)
            assertTrue(request.uri.query.contains("limit=" + if (route == com.choplab.sampler.source.SpotifyCatalogRoute.ARTIST_ALBUMS) 10 else 20))
            assertTrue(request.uri.query.contains("offset=20"))
        }
        assertFailsWith<IllegalArgumentException> {
            SpotifyApiRequestBuilder.catalogPage("token", com.choplab.sampler.source.SpotifyCatalogRequest(
                com.choplab.sampler.source.SpotifyCatalogRoute.ALBUM_TRACKS, id = "../../other"))
        }
    }
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
