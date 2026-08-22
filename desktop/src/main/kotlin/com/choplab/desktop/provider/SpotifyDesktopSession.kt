package com.choplab.desktop.provider

import com.choplab.desktop.spotify.JdkSpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyApi
import com.choplab.desktop.spotify.SpotifyApiClient
import com.choplab.desktop.spotify.SpotifyApiResponse
import com.choplab.desktop.spotify.SpotifyAuthorizationCallback
import com.choplab.desktop.spotify.SpotifyAuthorizationCallbackFactory
import com.choplab.desktop.spotify.SpotifyAuthorizationDeniedException
import com.choplab.desktop.spotify.SpotifyLoopbackCallbackServer
import com.choplab.desktop.spotify.SpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyTokenRequestException
import com.choplab.desktop.spotify.SpotifyTokens
import com.choplab.desktop.spotify.newSpotifyAuthorizationAttempt
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpotifyConnectionPhase(val label: String) {
    UNCONFIGURED("Client ID未設定"),
    READY("接続準備完了"),
    AUTHENTICATING("認証中"),
    CONNECTED("接続済み"),
    ERROR("接続エラー"),
}

data class SpotifyDesktopState(
    val phase: SpotifyConnectionPhase = SpotifyConnectionPhase.UNCONFIGURED,
    val clientIdConfigured: Boolean = false,
    val clientIdSource: String? = null,
    val currentTrack: String = "現在再生情報は未取得です",
    val savedTracks: List<String> = emptyList(),
    val librarySummary: String = "ライブラリは未取得です",
    val message: String = "Client IDを設定してSpotifyへ接続してください",
    val busy: Boolean = false,
) {
    val connection: String get() = phase.label
    val canLogin: Boolean get() = !busy && clientIdConfigured && phase != SpotifyConnectionPhase.CONNECTED
    val canCancelLogin: Boolean get() = phase == SpotifyConnectionPhase.AUTHENTICATING
    val canUsePlaybackControls: Boolean get() = !busy && phase == SpotifyConnectionPhase.CONNECTED
    val canDisconnect: Boolean get() = phase != SpotifyConnectionPhase.UNCONFIGURED
    val canConfigureClientId: Boolean get() = !busy
}

fun interface SpotifyBrowser {
    fun open(uri: URI)
}

internal object DesktopSpotifyBrowser : SpotifyBrowser {
    override fun open(uri: URI) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "既定ブラウザーを開けません"
        }
        Desktop.getDesktop().browse(uri)
    }
}

/**
 * Memory-only Spotify metadata/control session.
 *
 * The explicit lifecycle ensures cancellation and disconnect always win over a
 * late OAuth callback. It never exposes Spotify audio bytes to the sampler.
 */
class SpotifyDesktopSession(
    private val onStatus: (String) -> Unit,
    clientId: String = System.getenv("CHOPLAB_SPOTIFY_CLIENT_ID").orEmpty(),
    private val tokenClient: SpotifyTokenClient = JdkSpotifyTokenClient(),
    private val api: SpotifyApiClient = SpotifyApi(),
    private val callbackFactory: SpotifyAuthorizationCallbackFactory = SpotifyAuthorizationCallbackFactory { SpotifyLoopbackCallbackServer() },
    private val browser: SpotifyBrowser = DesktopSpotifyBrowser,
    private val now: () -> Instant = Instant::now,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Spotify-OAuth").apply { isDaemon = true }
    }
    private val lock = Any()
    private val generation = ProviderSessionGeneration()
    private val closed = AtomicBoolean(false)
    private var configuredClientId: String = clientId.trim().takeIf(::isValidSpotifyClientId).orEmpty()
    private val providedClientIdWasInvalid = clientId.isNotBlank() && configuredClientId.isBlank()
    private var credentials: Credentials? = null
    private var nextLoginId = 0L
    private var activeLogin: ActiveLogin? = null
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<SpotifyDesktopState> = mutableState.asStateFlow()

    val connected: Boolean
        get() = synchronized(lock) { credentials != null && mutableState.value.phase == SpotifyConnectionPhase.CONNECTED }

    fun configureClientId(value: String): Boolean {
        if (closed.get()) {
            onStatus("Spotify連携は終了しています")
            return false
        }
        val normalized = value.trim()
        if (!isValidSpotifyClientId(normalized)) {
            report("Spotify Client IDの形式を確認してください（16〜128文字の英数字）")
            return false
        }
        val callback = synchronized(lock) {
            val old = activeLogin
            generation.invalidate()
            activeLogin = null
            credentials = null
            configuredClientId = normalized
            setStateLocked(
                SpotifyDesktopState(
                    phase = SpotifyConnectionPhase.READY,
                    clientIdConfigured = true,
                    clientIdSource = "この起動中のみ",
                    message = "Client IDをメモリ内に設定しました。Spotifyログインを開始できます",
                ),
            )
            old?.callback
        }
        callback?.cancel()
        onStatus("Client IDをメモリ内に設定しました。ディスクには保存しません")
        return true
    }

    fun login() {
        if (closed.get()) {
            onStatus("Spotify連携は終了しています")
            return
        }
        val login = synchronized(lock) {
            if (mutableState.value.busy) {
                setStateLocked(mutableState.value.copy(message = "Spotifyの前の操作が完了してから実行してください"))
                return@synchronized null
            }
            if (configuredClientId.isBlank()) {
                setStateLocked(initialState("Spotify Client IDが未設定です。連携パネルで設定してください"))
                return@synchronized null
            }
            if (credentials != null || mutableState.value.phase == SpotifyConnectionPhase.CONNECTED) {
                setStateLocked(mutableState.value.copy(message = "Spotifyはすでに接続済みです。再認証するには連携解除してください"))
                return@synchronized null
            }
            val attempt = ActiveLogin(++nextLoginId, generation.begin(), null)
            activeLogin = attempt
            setStateLocked(
                mutableState.value.copy(
                    phase = SpotifyConnectionPhase.AUTHENTICATING,
                    busy = true,
                    message = "ブラウザーでSpotify連携を許可してください。中止する場合は「認証をキャンセル」を選びます",
                ),
            )
            attempt to configuredClientId
        } ?: return

        onStatus("ブラウザーでSpotify連携を許可してください")
        executor.execute { performLogin(login.first, login.second) }
    }

    fun cancelLogin() {
        val callback = synchronized(lock) {
            val active = activeLogin ?: return@synchronized null
            activeLogin = null
            generation.invalidate()
            setStateLocked(readyState("Spotifyログインをキャンセルしました。必要ならもう一度ログインしてください"))
            active.callback
        }
        callback?.cancel()
        onStatus("Spotifyログインをキャンセルしました")
    }

    fun showCurrentPlayback() = withAccessToken("Spotify現在再生") { token, lease ->
        val response = api.currentPlayback(token)
        generation.requireCurrent(lease)
        when (response.statusCode) {
            204 -> OperationResult(
                "Spotifyで現在再生中の曲はありません",
                transform = { it.copy(currentTrack = "現在再生中の曲はありません") },
            )
            in 200..299 -> {
                val description = SpotifyPlaybackJson.describe(response.body)
                OperationResult("現在再生を更新しました", transform = { it.copy(currentTrack = description) })
            }
            else -> throw SpotifyApiException(response, "現在再生")
        }
    }

    fun showLibrary() = withAccessToken("Spotifyライブラリ") { token, lease ->
        val response = api.savedTracks(token)
        generation.requireCurrent(lease)
        if (response.statusCode !in 200..299) throw SpotifyApiException(response, "ライブラリ")
        val parsed = SpotifyPlaybackJson.savedTracks(response.body)
        when {
            !parsed.recognized -> OperationResult(
                "Spotifyライブラリの応答を読み取れませんでした。時間を置いて再試行してください",
                transform = {
                    it.copy(
                        savedTracks = emptyList(),
                        librarySummary = "ライブラリの応答を読み取れませんでした",
                    )
                },
            )
            parsed.tracks.isEmpty() -> OperationResult(
                "保存済みトラックはありません",
                transform = {
                    it.copy(
                        savedTracks = emptyList(),
                        librarySummary = "保存済みトラックはありません",
                    )
                },
            )
            else -> OperationResult(
                "保存済みトラックを${parsed.tracks.size}件表示しました",
                transform = {
                    it.copy(
                        savedTracks = parsed.tracks,
                        librarySummary = "保存済みトラックを${parsed.tracks.size}件表示中",
                    )
                },
            )
        }
    }

    fun pause() = withAccessToken("Spotify一時停止") { token, lease ->
        val response = api.pausePlayback(token)
        generation.requireCurrent(lease)
        if (response.statusCode !in 200..299) throw SpotifyApiException(response, "一時停止")
        OperationResult("Spotify再生を一時停止しました")
    }

    fun resume() = withAccessToken("Spotify再開") { token, lease ->
        val response = api.resumePlayback(token)
        generation.requireCurrent(lease)
        if (response.statusCode !in 200..299) throw SpotifyApiException(response, "再開")
        OperationResult("Spotify再生を再開しました")
    }

    fun disconnect() {
        if (closed.get()) return
        val callback = synchronized(lock) {
            val old = activeLogin
            generation.invalidate()
            activeLogin = null
            credentials = null
            setStateLocked(readyState("Spotify連携を解除しました。トークンと表示済みメタデータはメモリから破棄されました"))
            old?.callback
        }
        callback?.cancel()
        onStatus("Spotify連携をこの端末のメモリから解除しました")
    }

    private fun performLogin(login: ActiveLogin, clientId: String) {
        var callback: SpotifyAuthorizationCallback? = null
        try {
            callback = callbackFactory.create()
            synchronized(lock) {
                if (!isCurrentLoginLocked(login)) {
                    callback.cancel()
                    return
                }
                activeLogin = login.copy(callback = callback)
            }
            val attempt = newSpotifyAuthorizationAttempt(
                clientId = clientId,
                redirectUri = callback.redirectUri,
                scopes = listOf("user-library-read", "user-read-playback-state", "user-modify-playback-state"),
            )
            callback.expectState(attempt.state)
            generation.requireCurrent(login.lease)
            browser.open(attempt.request.toUri())
            val received = callback.await()
            generation.requireCurrent(login.lease)
            val tokens = tokenClient.exchangeCode(clientId, received.code, callback.redirectUri, attempt.verifier)
            generation.requireCurrent(login.lease)
            val connected = synchronized(lock) {
                if (!isCurrentLoginLocked(login)) return@synchronized false
                credentials = Credentials(tokens, now())
                activeLogin = null
                setStateLocked(
                    mutableState.value.copy(
                        phase = SpotifyConnectionPhase.CONNECTED,
                        busy = false,
                        message = "Spotifyと接続しました。表示とSpotify Connectの再生制御のみを行います",
                    ),
                )
                true
            }
            if (connected) onStatus("Spotifyと接続しました。音声取込はローカル素材または録音を使用します")
        } catch (error: Throwable) {
            val failure = synchronized(lock) {
                if (!isCurrentLoginLocked(login)) return@synchronized null
                activeLogin = null
                credentials = null
                setStateLocked(errorState(loginFailureMessage(error)))
                mutableState.value.message
            }
            if (failure != null) onStatus(failure)
        } finally {
            callback?.close()
        }
    }

    private fun withAccessToken(label: String, action: (String, Long) -> OperationResult) {
        if (closed.get()) {
            onStatus("$label 失敗: Spotify連携は終了しています")
            return
        }
        val operation = synchronized(lock) {
            if (mutableState.value.busy) {
                setStateLocked(mutableState.value.copy(message = "Spotifyの前の操作が完了してから実行してください"))
                return@synchronized null
            }
            val current = credentials
            if (current == null || mutableState.value.phase != SpotifyConnectionPhase.CONNECTED) {
                setStateLocked(readyState("先にSpotifyへログインしてください"))
                return@synchronized null
            }
            setStateLocked(mutableState.value.copy(busy = true, message = "$label を実行しています"))
            Operation(generation.snapshot(), current, configuredClientId)
        } ?: return

        executor.execute {
            try {
                generation.requireCurrent(operation.lease)
                val current = refreshIfNeeded(operation.credentials, operation.clientId)
                generation.requireCurrent(operation.lease)
                val result = action(current.tokens.accessToken, operation.lease)
                generation.requireCurrent(operation.lease)
                val message = synchronized(lock) {
                    if (closed.get() || !generation.isCurrent(operation.lease) || mutableState.value.phase != SpotifyConnectionPhase.CONNECTED) {
                        return@synchronized null
                    }
                    credentials = current
                    setStateLocked(result.transform(mutableState.value).copy(busy = false, message = result.message))
                    result.message
                }
                if (message != null) onStatus(message)
            } catch (error: Throwable) {
                val message = synchronized(lock) { completeOperationFailureLocked(operation, label, error) }
                if (message != null) onStatus(message)
            }
        }
    }

    private fun refreshIfNeeded(current: Credentials, clientId: String): Credentials {
        val refreshAt = current.acquiredAt.plusSeconds((current.tokens.expiresInSeconds - 60L).coerceAtLeast(0L))
        if (now().isBefore(refreshAt)) return current
        val refreshToken = current.tokens.refreshToken ?: error("Spotifyログインの有効期限が切れました。再ログインしてください")
        val refreshed = tokenClient.refresh(clientId, refreshToken)
        return Credentials(
            tokens = refreshed.copy(refreshToken = refreshed.refreshToken ?: refreshToken),
            acquiredAt = now(),
        )
    }

    private fun completeOperationFailureLocked(operation: Operation, label: String, error: Throwable): String? {
        if (closed.get() || !generation.isCurrent(operation.lease) || mutableState.value.phase != SpotifyConnectionPhase.CONNECTED) {
            return null
        }
        if ((error is SpotifyApiException && error.response.statusCode == 401) ||
            (error is SpotifyTokenRequestException && error.statusCode in 400..401)
        ) {
            credentials = null
            setStateLocked(errorState("$label: 認証期限が切れたか更新できませんでした。もう一度ログインしてください"))
        } else {
            val message = when (error) {
                is SpotifyApiException -> SpotifyErrorGuidance.forStatus(error.response.statusCode, label, error.response.retryAfterSeconds)
                is SpotifyTokenRequestException -> "$label: Spotify認証の更新に失敗しました。時間を置いて再試行してください"
                else -> "$label に失敗しました。ネットワーク接続とSpotifyの状態を確認して再試行してください"
            }
            setStateLocked(mutableState.value.copy(busy = false, message = message))
        }
        return mutableState.value.message
    }

    private fun loginFailureMessage(error: Throwable): String = when (error) {
        is CancellationException -> "Spotifyログインをキャンセルしました。必要ならもう一度ログインしてください"
        is TimeoutException -> "Spotifyログインが時間切れになりました。ブラウザーでの認可を確認して、もう一度ログインしてください"
        is SpotifyAuthorizationDeniedException ->
            "Spotify連携が拒否または中止されました。許可する場合は、もう一度ログインしてください"
        else -> "Spotifyログインに失敗しました。Client ID、Redirect URI、開発モードの許可ユーザーとPremiumを確認して再試行してください"
    }

    private fun initialState(message: String? = null): SpotifyDesktopState =
        if (configuredClientId.isBlank()) {
            SpotifyDesktopState(
                message = message ?: if (providedClientIdWasInvalid) {
                    "環境変数のSpotify Client ID形式が不正です。16〜128文字の英数字を設定し直してください"
                } else {
                    "Client IDを設定してSpotifyへ接続してください"
                },
            )
        } else {
            SpotifyDesktopState(
                phase = SpotifyConnectionPhase.READY,
                clientIdConfigured = true,
                clientIdSource = "環境変数",
                message = message ?: "Client IDは環境変数から設定済みです。Spotifyログインを開始できます",
            )
        }

    private fun readyState(message: String): SpotifyDesktopState =
        if (configuredClientId.isBlank()) initialState(message) else SpotifyDesktopState(
            phase = SpotifyConnectionPhase.READY,
            clientIdConfigured = true,
            clientIdSource = mutableState.value.clientIdSource ?: "この起動中のみ",
            message = message,
        )

    private fun errorState(message: String): SpotifyDesktopState =
        if (configuredClientId.isBlank()) initialState(message) else SpotifyDesktopState(
            phase = SpotifyConnectionPhase.ERROR,
            clientIdConfigured = true,
            clientIdSource = mutableState.value.clientIdSource ?: "この起動中のみ",
            message = message,
        )

    private fun isCurrentLoginLocked(login: ActiveLogin): Boolean =
        !closed.get() && activeLogin?.id == login.id && activeLogin?.lease == login.lease && generation.isCurrent(login.lease)

    private fun setStateLocked(value: SpotifyDesktopState) {
        mutableState.value = value
    }

    private fun report(message: String) {
        synchronized(lock) { setStateLocked(mutableState.value.copy(message = message)) }
        onStatus(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.invalidate()
        val callback = synchronized(lock) {
            credentials = null
            val current = activeLogin?.callback
            activeLogin = null
            current
        }
        callback?.cancel()
        executor.shutdownNow()
    }

    private data class Credentials(val tokens: SpotifyTokens, val acquiredAt: Instant)
    private data class ActiveLogin(val id: Long, val lease: Long, val callback: SpotifyAuthorizationCallback?)
    private data class Operation(val lease: Long, val credentials: Credentials, val clientId: String)
    private data class OperationResult(
        val message: String,
        val transform: (SpotifyDesktopState) -> SpotifyDesktopState = { it },
    )
}

private fun isValidSpotifyClientId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9]{16,128}"))

private class SpotifyApiException(
    val response: SpotifyApiResponse,
    operation: String,
) : IllegalStateException("$operation: HTTP ${response.statusCode}")

internal data class SpotifySavedTracksResult(
    val tracks: List<String>,
    val recognized: Boolean,
)

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

    fun savedTracks(body: String): SpotifySavedTracksResult {
        val root = runCatching { JsonValueReader(body).read() as? Map<*, *> }.getOrNull()
            ?: return SpotifySavedTracksResult(emptyList(), recognized = false)
        val items = root["items"] as? List<*> ?: return SpotifySavedTracksResult(emptyList(), recognized = false)
        return SpotifySavedTracksResult(
            tracks = items.mapNotNull { entry ->
                val track = (entry as? Map<*, *>)?.get("track") as? Map<*, *> ?: return@mapNotNull null
                val name = track["name"] as? String ?: return@mapNotNull null
                val artist = (track["artists"] as? List<*>)?.firstOrNull()?.let { it as? Map<*, *> }?.get("name") as? String
                if (artist.isNullOrBlank()) name else "$artist — $name"
            },
            recognized = true,
        )
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
    fun forStatus(status: Int, operation: String, retryAfterSeconds: Long? = null): String = when (status) {
        401 -> "$operation: 認証期限が切れました。連携解除後に再ログインしてください"
        403 -> "$operation: Spotify Premium、開発モードの許可ユーザー、または必要な権限を確認してください"
        404 -> "$operation: 操作できるSpotify Connect再生デバイスが見つかりません。Spotifyアプリで再生を開始してください"
        429 -> retryAfterSeconds?.let { "$operation: Spotify APIの利用上限です。$it 秒待ってから再試行してください" }
            ?: "$operation: Spotify APIの利用上限です。しばらく待ってから再試行してください"
        in 500..599 -> "$operation: Spotify側で一時的な障害が発生しています。時間を置いて再試行してください"
        else -> "$operation: Spotify APIエラー (HTTP $status)"
    }
}
