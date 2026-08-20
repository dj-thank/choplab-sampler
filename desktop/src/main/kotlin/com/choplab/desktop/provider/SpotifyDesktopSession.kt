package com.choplab.desktop.provider

import com.choplab.desktop.spotify.JdkSpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyApi
import com.choplab.desktop.spotify.SpotifyLoopbackCallbackServer
import com.choplab.desktop.spotify.SpotifyTokens
import com.choplab.desktop.spotify.newSpotifyAuthorizationAttempt
import java.awt.Desktop
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Memory-only Spotify metadata/control session.
 *
 * This provider never exposes Spotify audio bytes to the sampler. Audio sources
 * remain local WAV or an explicitly started local recording.
 */
class SpotifyDesktopSession(
    private val onStatus: (String) -> Unit,
    private val clientId: String = System.getenv("CHOPLAB_SPOTIFY_CLIENT_ID").orEmpty(),
    private val tokenClient: JdkSpotifyTokenClient = JdkSpotifyTokenClient(),
    private val api: SpotifyApi = SpotifyApi(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Spotify-OAuth").apply { isDaemon = true }
    }
    @Volatile private var credentials: Credentials? = null

    val connected: Boolean
        get() = credentials != null

    fun login() {
        if (clientId.isBlank()) {
            onStatus("Spotify連携にはCHOPLAB_SPOTIFY_CLIENT_IDを設定してください")
            return
        }
        submit("Spotifyログイン") {
            SpotifyLoopbackCallbackServer().use { callback ->
                val attempt = newSpotifyAuthorizationAttempt(
                    clientId = clientId,
                    redirectUri = callback.redirectUri,
                    scopes = listOf("user-read-playback-state", "user-modify-playback-state"),
                )
                check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "既定ブラウザーを開けません"
                }
                Desktop.getDesktop().browse(attempt.request.toUri())
                onStatus("ブラウザーでSpotify連携を許可してください")
                val received = callback.await(attempt.state)
                val tokens = tokenClient.exchangeCode(
                    clientId,
                    received.code,
                    callback.redirectUri,
                    attempt.verifier,
                )
                credentials = Credentials(tokens, Instant.now())
                onStatus("Spotifyと連携しました。音声取込はローカル素材または録音を使用します")
            }
        }
    }

    fun showCurrentPlayback() = withAccessToken("Spotify現在再生") { token ->
        val response = api.currentPlayback(token)
        when (response.statusCode) {
            204 -> onStatus("Spotifyで現在再生中の曲はありません")
            in 200..299 -> onStatus(SpotifyPlaybackJson.describe(response.body))
            else -> error("Spotify現在再生API: HTTP ${response.statusCode}")
        }
    }

    fun pause() = withAccessToken("Spotify一時停止") { token ->
        val response = api.pausePlayback(token)
        check(response.statusCode in 200..299) { "Spotify一時停止API: HTTP ${response.statusCode}" }
        onStatus("Spotify再生を一時停止しました")
    }

    fun resume() = withAccessToken("Spotify再開") { token ->
        val response = api.resumePlayback(token)
        check(response.statusCode in 200..299) { "Spotify再開API: HTTP ${response.statusCode}" }
        onStatus("Spotify再生を再開しました")
    }

    fun disconnect() {
        credentials = null
        onStatus("Spotify連携をこの端末のメモリから解除しました")
    }

    private fun withAccessToken(label: String, action: (String) -> Unit) {
        submit(label) {
            val current = refreshIfNeeded(credentials ?: error("先にSpotifyへログインしてください"))
            credentials = current
            action(current.tokens.accessToken)
        }
    }

    private fun refreshIfNeeded(current: Credentials): Credentials {
        val refreshAt = current.acquiredAt.plusSeconds((current.tokens.expiresInSeconds - 60L).coerceAtLeast(0L))
        if (Instant.now().isBefore(refreshAt)) return current
        val refreshToken = current.tokens.refreshToken ?: error("Spotifyログインの有効期限が切れました。再ログインしてください")
        val refreshed = tokenClient.refresh(clientId, refreshToken)
        return Credentials(
            tokens = refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken),
            acquiredAt = Instant.now(),
        )
    }

    private fun submit(label: String, action: () -> Unit) {
        executor.submit {
            runCatching(action).onFailure { error ->
                onStatus("$label 失敗: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        credentials = null
        executor.shutdownNow()
    }

    private data class Credentials(val tokens: SpotifyTokens, val acquiredAt: Instant)
}

internal object SpotifyPlaybackJson {
    fun describe(body: String): String {
        val root = runCatching { JsonValueReader(body).read() as? Map<*, *> }.getOrNull()
            ?: return "Spotify現在再生情報を取得しました"
        val item = root["item"] as? Map<*, *> ?: return "Spotify現在再生情報を取得しました"
        val name = item["name"] as? String ?: return "Spotify現在再生情報を取得しました"
        val artist = (item["artists"] as? List<*>)
            ?.firstOrNull()
            ?.let { it as? Map<*, *> }
            ?.get("name") as? String
        return if (artist.isNullOrBlank()) "Spotify再生中: $name" else "Spotify再生中: $artist — $name"
    }

    /** Small fail-closed JSON reader for provider responses; it does not retain the body. */
    private class JsonValueReader(private val source: String) {
        private var offset = 0

        fun read(): Any? {
            val value = readValue()
            skipWhitespace()
            require(offset == source.length) { "Unexpected JSON suffix" }
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            require(offset < source.length) { "Unexpected end of JSON" }
            return when (source[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (consume('}')) return result
            while (true) {
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (consume(']')) return result
            while (true) {
                result += readValue()
                skipWhitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun readString(): String {
            skipWhitespace()
            expect('"')
            return buildString {
                while (true) {
                    require(offset < source.length) { "Unterminated JSON string" }
                    val character = source[offset++]
                    when (character) {
                        '"' -> return@buildString
                        '\\' -> {
                            require(offset < source.length) { "Unterminated JSON escape" }
                            when (val escaped = source[offset++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000C')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    require(offset + 4 <= source.length) { "Invalid Unicode escape" }
                                    append(source.substring(offset, offset + 4).toInt(16).toChar())
                                    offset += 4
                                }
                                else -> error("Unsupported JSON escape: $escaped")
                            }
                        }
                        else -> append(character)
                    }
                }
            }
        }

        private fun readNumber(): Number {
            val start = offset
            while (offset < source.length && source[offset] in "-+0123456789.eE") offset++
            require(offset > start) { "Invalid JSON value" }
            val value = source.substring(start, offset)
            return value.toLongOrNull() ?: value.toDoubleOrNull() ?: error("Invalid JSON number")
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(source.startsWith(literal, offset)) { "Invalid JSON literal" }
            offset += literal.length
            return value
        }

        private fun expect(character: Char) {
            skipWhitespace()
            require(offset < source.length && source[offset] == character) { "Expected '$character'" }
            offset++
        }

        private fun consume(character: Char): Boolean {
            skipWhitespace()
            if (offset >= source.length || source[offset] != character) return false
            offset++
            return true
        }

        private fun skipWhitespace() {
            while (offset < source.length && source[offset].isWhitespace()) offset++
        }
    }
}
