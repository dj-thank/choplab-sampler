package com.choplab.desktop.spotify

import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyLoopbackCallbackTest {
    @Test
    fun wrongStateDoesNotConsumeTheLaterValidCallback() {
        val callback = SpotifyLoopbackCallbackServer()
        try {
            callback.expectState("expected-state")
            val client = HttpClient.newHttpClient()

            val wrong = client.send(
                request(callback, "code=forged&state=attacker-state"),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, wrong.statusCode())
            assertEquals("no-store", wrong.headers().firstValue("Cache-Control").orElse(null))
            assertEquals("nosniff", wrong.headers().firstValue("X-Content-Type-Options").orElse(null))

            val valid = client.send(
                request(callback, "code=code-123&state=expected-state"),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(HttpURLConnection.HTTP_OK, valid.statusCode())
            assertEquals("no-store", valid.headers().firstValue("Cache-Control").orElse(null))
            assertEquals("nosniff", valid.headers().firstValue("X-Content-Type-Options").orElse(null))

            assertEquals(
                SpotifyCallbackResult(code = "code-123", state = "expected-state"),
                callback.await(Duration.ofSeconds(1)),
            )
        } finally {
            callback.close()
        }
    }

    @Test
    fun providerDenialRequiresMatchingState() {
        val callback = SpotifyLoopbackCallbackServer()
        try {
            callback.expectState("expected-state")
            val client = HttpClient.newHttpClient()

            val forgedDenial = client.send(
                request(callback, "error=access_denied&state=attacker-state"),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, forgedDenial.statusCode())

            val valid = client.send(
                request(callback, "code=code-456&state=expected-state"),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(HttpURLConnection.HTTP_OK, valid.statusCode())
            assertEquals("code-456", callback.await(Duration.ofSeconds(1)).code)
        } finally {
            callback.close()
        }
    }

    private fun request(callback: SpotifyLoopbackCallbackServer, query: String): HttpRequest =
        HttpRequest.newBuilder(URI("${callback.redirectUri}?$query")).GET().build()
}
