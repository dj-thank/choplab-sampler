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
import java.util.concurrent.atomic.AtomicBoolean

data class SpotifyCallbackResult(
    val code: String,
    val state: String,
)

interface SpotifyAuthorizationCallback : AutoCloseable {
    val redirectUri: URI
    fun expectState(state: String)
    fun await(timeout: Duration = Duration.ofMinutes(3)): SpotifyCallbackResult
    fun cancel()
}

fun interface SpotifyAuthorizationCallbackFactory {
    fun create(): SpotifyAuthorizationCallback
}

/** One-shot loopback receiver. It never logs or persists the authorization code. */
class SpotifyLoopbackCallbackServer : SpotifyAuthorizationCallback {
    private val result = CompletableFuture<SpotifyCallbackResult>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val closed = AtomicBoolean(false)
    @Volatile private var expectedState: String? = null

    init {
        server.createContext("/callback") { exchange -> handle(exchange) }
        server.start()
    }

    override val redirectUri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/callback")

    /** Must be installed before opening the provider authorization page. */
    override fun expectState(state: String) {
        require(state.isNotBlank()) { "Spotify OAuth state must not be blank" }
        check(!closed.get()) { "Spotify callback receiver is closed" }
        check(!result.isDone) { "Spotify callback was already completed" }
        expectedState = state
    }

    override fun await(timeout: Duration): SpotifyCallbackResult {
        check(expectedState != null) { "Spotify OAuth state was not configured" }
        return try {
            result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        result.cancel(false)
    }

    override fun cancel() = close()

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, "Only GET callbacks are accepted.")
                return
            }
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val receivedState = query["state"]
            val requiredState = expectedState
            if (requiredState == null || receivedState.isNullOrBlank() || receivedState != requiredState) {
                // A malformed or locally forged callback must not consume the one valid result.
                respond(exchange, 400, "Spotify authorization state was invalid. Return to the original browser tab.")
                return
            }

            val error = query["error"]
            if (error != null) {
                result.completeExceptionally(IllegalStateException("Spotify authorization failed: $error"))
                respond(exchange, 200, "Spotify authorization was denied. You can close this window.")
                return
            }
            val code = query["code"]
            require(!code.isNullOrBlank()) { "Spotify callback did not contain an authorization code" }
            result.complete(SpotifyCallbackResult(code, receivedState))
            respond(exchange, 200, "Spotify authorization succeeded. You can close this window.")
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            respond(exchange, 400, "Spotify authorization failed. You can close this window.")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = "<html><body>${message.htmlEscape()}</body></html>"
            .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
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
