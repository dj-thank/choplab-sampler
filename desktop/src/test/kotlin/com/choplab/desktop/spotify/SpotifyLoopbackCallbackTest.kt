package com.choplab.desktop.spotify

import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpotifyLoopbackCallbackTest {
    @Test
    fun callbackRejectsAStateThatDoesNotMatchTheAuthorizationAttempt() {
        val callback = SpotifyLoopbackCallbackServer()
        try {
            val request = HttpRequest.newBuilder(
                URI("${callback.redirectUri}?code=code-123&state=attacker-state"),
            ).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode())
            assertFailsWith<IllegalStateException> {
                callback.await("expected-state", Duration.ofSeconds(1))
            }
        } finally {
            callback.close()
        }
    }
}
