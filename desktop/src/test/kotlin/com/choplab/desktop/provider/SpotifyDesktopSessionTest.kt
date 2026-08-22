package com.choplab.desktop.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyDesktopSessionTest {
    @Test
    fun currentPlaybackDescriptionUsesTrackAndFirstArtistWithoutAudioBytes() {
        val body = """
            {"is_playing":true,"item":{"album":{"name":"Wrong Album Name","artists":[{"name":"Album Artist"}]},"artists":[{"name":"Sample Artist"}],"name":"Blue Room","duration_ms":180000}}
        """.trimIndent()

        assertEquals("Spotify再生中: Sample Artist — Blue Room", SpotifyPlaybackJson.describe(body))
    }

    @Test
    fun missingItemDoesNotInventTrackMetadata() {
        assertEquals("Spotify現在再生情報を取得しました", SpotifyPlaybackJson.describe("{\"is_playing\":false}"))
    }

    @Test
    fun savedLibraryKeepsOnlyArtistAndTrackMetadata() {
        val body = """{"items":[{"track":{"name":"Blue Room","artists":[{"name":"Sample Artist"}],"href":"https://api.spotify.com/v1/tracks/1"}}]}"""
        assertEquals(listOf("Sample Artist — Blue Room"), SpotifyPlaybackJson.savedTracks(body))
    }

    @Test
    fun providerErrorsGiveActionableSafeGuidance() {
        assertEquals(
            "現在再生: 認証期限が切れました。連携解除後に再ログインしてください",
            SpotifyErrorGuidance.forStatus(401, "現在再生"),
        )
        assertEquals(
            "再開: 操作できるSpotify Connect再生デバイスが見つかりません。Spotifyアプリで再生を開始してください",
            SpotifyErrorGuidance.forStatus(404, "再開"),
        )
    }
}
