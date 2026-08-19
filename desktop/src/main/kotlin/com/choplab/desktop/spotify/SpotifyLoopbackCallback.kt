package com.choplab.desktop.spotify

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class SpotifyCallbackResult(
    val code: String,
    val state: String,
)

/** One-shot loopback receiver. It never logs or persists the authorization code. */
class SpotifyLoopbackCallbackServer : AutoCloseable {
    private val result = CompletableFuture<SpotifyCallbackResult>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/callback") { exchange -> handle(exchange) }
        server.start()
    }

    val redirectUri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/callback")

    fun await(expectedState: String, timeout: Duration = Duration.ofMinutes(3)): SpotifyCallbackResult {
        return try {
            val received = result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            check(received.state == expectedState) { "Spotify OAuth state did not match" }
            received
        } finally {
            close()
        }
    }

    override fun close() {
        server.stop(0)
        result.cancel(false)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val error = query["error"]
            if (error != null) {
                result.completeExceptionally(IllegalStateException("Spotify authorization failed: $error"))
                respond(exchange, "Spotify authorization was denied. You can close this window.")
                return
            }
            val code = query["code"]
            val state = query["state"]
            require(!code.isNullOrBlank() && !state.isNullOrBlank()) {
                "Spotify callback did not contain code and state"
            }
            result.complete(SpotifyCallbackResult(code, state))
            respond(exchange, "Spotify authorization succeeded. You can close this window.")
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            respond(exchange, "Spotify authorization failed. You can close this window.")
        }
    }

    private fun respond(exchange: HttpExchange, message: String) {
        val bytes = "<html><body>${message.htmlEscape()}</body></html>"
            .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun String.htmlEscape(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery.split('&')
            .filter { it.isNotBlank() }
            .associate { part ->
                val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
}
