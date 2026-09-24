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
    @Test fun activeWaiterReceivesCodeWithoutResettingBrowserResponse() {
        repeat(3) {
            val callback=SpotifyLoopbackCallbackServer()
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                callback.expectState("expected-state")
                val result=executor.submit<SpotifyCallbackResult> { callback.await(Duration.ofSeconds(5)) }
                val response=HttpClient.newHttpClient().send(request(callback,"code=code-123&state=expected-state"),HttpResponse.BodyHandlers.ofString())
                assertEquals(200,response.statusCode())
                kotlin.test.assertTrue(response.body().contains("ChopLabへ戻ってください"))
                assertEquals("code-123",result.get(5,java.util.concurrent.TimeUnit.SECONDS).code)
            } finally {callback.close();executor.shutdownNow()}
        }
    }

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

    @Test
    fun matchingProviderDenialUsesTheDedicatedFailureType() {
        val callback = SpotifyLoopbackCallbackServer()
        try {
            callback.expectState("expected-state")
            val response = HttpClient.newHttpClient().send(
                request(callback, "error=access_denied&state=expected-state"),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode())
            assertFailsWith<SpotifyAuthorizationDeniedException> {
                callback.await(Duration.ofSeconds(1))
            }
        } finally {
            callback.close()
        }
    }

    private fun request(callback: SpotifyLoopbackCallbackServer, query: String): HttpRequest =
        HttpRequest.newBuilder(URI("${callback.redirectUri}?$query")).GET().build()
}
