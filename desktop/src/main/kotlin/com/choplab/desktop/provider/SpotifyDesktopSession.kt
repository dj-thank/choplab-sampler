package com.choplab.desktop.provider

import com.choplab.desktop.spotify.JdkSpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyApi
import com.choplab.desktop.spotify.SpotifyLoopbackCallbackServer
import com.choplab.desktop.spotify.SpotifyTokens
import com.choplab.desktop.spotify.newSpotifyAuthorizationAttempt
import java.awt.Desktop
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpotifyDesktopState(
    val clientIdConfigured: Boolean = false,
    val connection: String = "未接続",
    val currentTrack: String = "現在再生情報は未取得です",
    val savedTracks: List<String> = emptyList(),
    val message: String = "Client IDを設定してSpotifyへ接続してください",
    val busy: Boolean = false,
)

/**
 * Memory-only Spotify metadata/control session.
 *
 * This provider never exposes Spotify audio bytes to the sampler. Audio sources
 * remain local WAV or an explicitly started local recording.
 */
class SpotifyDesktopSession(
    private val onStatus: (String) -> Unit,
    clientId: String = System.getenv("CHOPLAB_SPOTIFY_CLIENT_ID").orEmpty(),
    private val tokenClient: JdkSpotifyTokenClient = JdkSpotifyTokenClient(),
    private val api: SpotifyApi = SpotifyApi(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Spotify-OAuth").apply { isDaemon = true }
    }
    @Volatile private var credentials: Credentials? = null
    @Volatile private var configuredClientId: String = clientId.trim()
    private val mutableState = MutableStateFlow(SpotifyDesktopState(clientIdConfigured = configuredClientId.isNotBlank()))
    val state: StateFlow<SpotifyDesktopState> = mutableState.asStateFlow()

    val connected: Boolean
        get() = credentials != null

    fun configureClientId(value: String) {
        val normalized = value.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9]{16,128}"))) {
            report("Spotify Client IDの形式を確認してください（16〜128文字の英数字）")
            return
        }
        if (normalized != configuredClientId) credentials = null
        configuredClientId = normalized
        update { it.copy(clientIdConfigured = true, connection = "未接続", message = "Client IDをメモリ内に設定しました。次にログインしてください") }
    }

    fun login() {
        val clientId = configuredClientId
        if (clientId.isBlank()) {
            report("Spotify Client IDが未設定です。連携パネルで設定するかCHOPLAB_SPOTIFY_CLIENT_IDを指定してください")
            return
        }
        submit("Spotifyログイン") {
            SpotifyLoopbackCallbackServer().use { callback ->
                val attempt = newSpotifyAuthorizationAttempt(
                    clientId = clientId,
                    redirectUri = callback.redirectUri,
                    scopes = listOf("user-library-read", "user-read-playback-state", "user-modify-playback-state"),
                )
                check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    "既定ブラウザーを開けません"
                }
                Desktop.getDesktop().browse(attempt.request.toUri())
                update { it.copy(connection = "認証待ち", message = "ブラウザーでSpotify連携を許可してください") }
                onStatus("ブラウザーでSpotify連携を許可してください")
                val received = callback.await(attempt.state)
                val tokens = tokenClient.exchangeCode(
                    clientId,
                    received.code,
                    callback.redirectUri,
                    attempt.verifier,
                )
                credentials = Credentials(tokens, Instant.now())
                update { it.copy(connection = "接続済み", message = "Spotifyと接続しました（メタデータ／再生制御のみ）") }
                onStatus("Spotifyと連携しました。音声取込はローカル素材または録音を使用します")
            }
        }
    }

    fun showCurrentPlayback() = withAccessToken("Spotify現在再生") { token ->
        val response = api.currentPlayback(token)
        when (response.statusCode) {
            204 -> onStatus("Spotifyで現在再生中の曲はありません")
            in 200..299 -> SpotifyPlaybackJson.describe(response.body).also { description ->
                update { it.copy(currentTrack = description, message = "現在再生を更新しました") }
                onStatus(description)
            }
            else -> error(SpotifyErrorGuidance.forStatus(response.statusCode, "現在再生"))
        }
    }

    fun showLibrary() = withAccessToken("Spotifyライブラリ") { token ->
        val response = api.savedTracks(token)
        if (response.statusCode !in 200..299) error(SpotifyErrorGuidance.forStatus(response.statusCode, "ライブラリ"))
        val tracks = SpotifyPlaybackJson.savedTracks(response.body)
        update { it.copy(savedTracks = tracks, message = if (tracks.isEmpty()) "保存済みトラックはありません" else "保存済みトラックを${tracks.size}件表示しました") }
    }

    fun pause() = withAccessToken("Spotify一時停止") { token ->
        val response = api.pausePlayback(token)
        check(response.statusCode in 200..299) { SpotifyErrorGuidance.forStatus(response.statusCode, "一時停止") }
        onStatus("Spotify再生を一時停止しました")
    }

    fun resume() = withAccessToken("Spotify再開") { token ->
        val response = api.resumePlayback(token)
        check(response.statusCode in 200..299) { SpotifyErrorGuidance.forStatus(response.statusCode, "再開") }
        onStatus("Spotify再生を再開しました")
    }

    fun disconnect() {
        credentials = null
        update { SpotifyDesktopState(clientIdConfigured = configuredClientId.isNotBlank(), message = "Spotify連携を解除しました。トークンはメモリから破棄されました") }
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
        val refreshed = tokenClient.refresh(configuredClientId, refreshToken)
        return Credentials(
            tokens = refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken),
            acquiredAt = Instant.now(),
        )
    }

    private fun submit(label: String, action: () -> Unit) {
        update { it.copy(busy = true, message = "$label を実行しています") }
        executor.submit {
            runCatching(action)
                .onFailure { error -> report("$label 失敗: ${error.message ?: error.javaClass.simpleName}") }
                .also { update { state -> state.copy(busy = false) } }
        }
    }

    private fun report(message: String) {
        update { it.copy(message = message) }
        onStatus(message)
    }

    private fun update(transform: (SpotifyDesktopState) -> SpotifyDesktopState) {
        mutableState.value = transform(mutableState.value)
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

    fun savedTracks(body: String): List<String> {
        val root = runCatching { JsonValueReader(body).read() as? Map<*, *> }.getOrNull() ?: return emptyList()
        val items = root["items"] as? List<*> ?: return emptyList()
        return items.mapNotNull { entry ->
            val track = (entry as? Map<*, *>)?.get("track") as? Map<*, *> ?: return@mapNotNull null
            val name = track["name"] as? String ?: return@mapNotNull null
            val artist = (track["artists"] as? List<*>)?.firstOrNull()?.let { it as? Map<*, *> }?.get("name") as? String
            if (artist.isNullOrBlank()) name else "$artist — $name"
        }
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

internal object SpotifyErrorGuidance {
    fun forStatus(status: Int, operation: String): String = when (status) {
        401 -> "$operation: 認証期限が切れました。連携解除後に再ログインしてください"
        403 -> "$operation: Spotify Premium、アプリの許可ユーザー、または必要な権限を確認してください"
        404 -> "$operation: 操作できるSpotify Connect再生デバイスが見つかりません。Spotifyアプリで再生を開始してください"
        429 -> "$operation: Spotify APIの利用上限です。しばらく待ってから再試行してください"
        in 500..599 -> "$operation: Spotify側で一時的な障害が発生しています。時間を置いて再試行してください"
        else -> "$operation: Spotify APIエラー (HTTP $status)"
    }
}
