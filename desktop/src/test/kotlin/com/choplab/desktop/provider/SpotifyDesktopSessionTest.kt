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
}
