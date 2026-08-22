package com.choplab.desktop.provider

import com.choplab.desktop.spotify.SpotifyApiClient
import com.choplab.desktop.spotify.SpotifyApiResponse
import com.choplab.desktop.spotify.SpotifyAuthorizationCallback
import com.choplab.desktop.spotify.SpotifyAuthorizationCallbackFactory
import com.choplab.desktop.spotify.SpotifyCallbackResult
import com.choplab.desktop.spotify.SpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyTokenRequestException
import com.choplab.desktop.spotify.SpotifyTokens
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyDesktopSessionLifecycleTest {
    @Test
    fun authorizationFailureLeavesARecoverableErrorInsteadOfAuthenticating() {
        val session = session(callbackFactory = SpotifyAuthorizationCallbackFactory { FailingCallback })
        try {
            session.login()
            await { session.state.value.phase == SpotifyConnectionPhase.ERROR && !session.state.value.busy }

            assertTrue(session.state.value.canLogin)
            assertTrue(session.state.value.message.contains("Spotifyログインに失敗しました"))
        } finally {
            session.close()
        }
    }

    @Test
    fun cancelPreventsALateOAuthCompletionFromReconnecting() {
        val callback = DeferredCallback()
        val tokenClient = FakeTokenClient()
        val session = session(tokenClient = tokenClient, callbackFactory = SpotifyAuthorizationCallbackFactory { callback })
        try {
            session.login()
            await { session.state.value.phase == SpotifyConnectionPhase.AUTHENTICATING }
            callback.awaitStarted()

            session.cancelLogin()
            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
            assertFalse(session.state.value.busy)

            callback.complete()
            callback.awaitClosed()
            assertEquals(0, tokenClient.exchanges)
            assertFalse(session.connected)
            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
        } finally {
            session.close()
        }
    }

    @Test
    fun disconnectAlsoWinsAgainstALateOAuthCompletion() {
        val callback = DeferredCallback()
        val tokenClient = FakeTokenClient()
        val session = session(tokenClient = tokenClient, callbackFactory = SpotifyAuthorizationCallbackFactory { callback })
        try {
            session.login()
            await { session.state.value.phase == SpotifyConnectionPhase.AUTHENTICATING }
            callback.awaitStarted()

            session.disconnect()
            callback.complete()
            callback.awaitClosed()
            assertEquals(0, tokenClient.exchanges)

            assertFalse(session.connected)
            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
            assertEquals("現在再生情報は未取得です", session.state.value.currentTrack)
        } finally {
            session.close()
        }
    }

    @Test
    fun noContentPlaybackClearsOldTrackText() {
        val api = FakeApi(currentPlaybackResponse = SpotifyApiResponse(204, ""))
        val session = session(api = api, callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            connect(session)
            session.showCurrentPlayback()
            await { !session.state.value.busy }

            assertEquals("現在再生中の曲はありません", session.state.value.currentTrack)
            assertEquals("Spotifyで現在再生中の曲はありません", session.state.value.message)
        } finally {
            session.close()
        }
    }

    @Test
    fun reconfiguringClearsPriorAccountMetadata() {
        val api = FakeApi(
            currentPlaybackResponse = SpotifyApiResponse(200, """{"item":{"name":"Blue Room","artists":[{"name":"Sample Artist"}]}}"""),
            savedTracksResponse = SpotifyApiResponse(200, """{"items":[{"track":{"name":"Blue Room","artists":[{"name":"Sample Artist"}]}}]}"""),
        )
        val session = session(api = api, callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            connect(session)
            session.showCurrentPlayback()
            await { !session.state.value.busy }
            session.showLibrary()
            await { !session.state.value.busy }

            assertTrue(session.state.value.savedTracks.isNotEmpty())
            assertTrue(session.configureClientId("zyxwvutsrqponmlk"))

            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
            assertEquals(emptyList(), session.state.value.savedTracks)
            assertEquals("現在再生情報は未取得です", session.state.value.currentTrack)
            assertFalse(session.connected)
        } finally {
            session.close()
        }
    }

    @Test
    fun malformedLibraryResponseClearsPreviouslyDisplayedTracks() {
        val api = FakeApi(
            savedTracksResponse = SpotifyApiResponse(
                200,
                """{"items":[{"track":{"name":"Blue Room","artists":[{"name":"Sample Artist"}]}}]}""",
            ),
        )
        val session = session(api = api, callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            connect(session)
            session.showLibrary()
            await { !session.state.value.busy }
            assertEquals(listOf("Sample Artist — Blue Room"), session.state.value.savedTracks)

            api.savedTracksResponse = SpotifyApiResponse(200, "{\"not_items\":[]}")
            session.showLibrary()
            await { !session.state.value.busy }

            assertEquals(emptyList(), session.state.value.savedTracks)
            assertTrue(session.state.value.message.contains("読み取れませんでした"))
        } finally {
            session.close()
        }
    }

    @Test
    fun invalidClientIdDoesNotReplaceTheWorkingConfiguration() {
        val session = session(callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            assertFalse(session.configureClientId("not-valid"))
            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
            assertTrue(session.state.value.message.contains("形式を確認"))
        } finally {
            session.close()
        }
    }

    @Test
    fun successfulPlaybackControlReplacesTheRunningMessage() {
        val session = session(callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            connect(session)
            session.pause()
            await { !session.state.value.busy }

            assertEquals("Spotify再生を一時停止しました", session.state.value.message)
        } finally {
            session.close()
        }
    }

    @Test
    fun failedRefreshReturnsToARecoverableLoginState() {
        val tokenClient = FakeTokenClient(expiresInSeconds = 0, refreshFailure = SpotifyTokenRequestException(400))
        val session = session(tokenClient = tokenClient, callbackFactory = SpotifyAuthorizationCallbackFactory { ImmediateCallback })
        try {
            connect(session)
            session.showCurrentPlayback()
            await { session.state.value.phase == SpotifyConnectionPhase.ERROR && !session.state.value.busy }

            assertFalse(session.connected)
            assertTrue(session.state.value.canLogin)
            assertTrue(session.state.value.message.contains("もう一度ログイン"))
        } finally {
            session.close()
        }
    }

    private fun connect(session: SpotifyDesktopSession) {
        session.login()
        await { session.state.value.phase == SpotifyConnectionPhase.CONNECTED && !session.state.value.busy }
    }

    private fun session(
        tokenClient: SpotifyTokenClient = FakeTokenClient(),
        api: SpotifyApiClient = FakeApi(),
        callbackFactory: SpotifyAuthorizationCallbackFactory,
    ) = SpotifyDesktopSession(
        onStatus = {},
        clientId = "abcdefghijklmnop",
        tokenClient = tokenClient,
        api = api,
        callbackFactory = callbackFactory,
        browser = { },
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "Condition did not become true within 2 seconds")
    }

    private class FakeTokenClient(
        private val expiresInSeconds: Long = 3_600,
        private val refreshFailure: Throwable? = null,
    ) : SpotifyTokenClient {
        @Volatile var exchanges = 0

        override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String): SpotifyTokens {
            exchanges++
            return tokens()
        }

        override fun refresh(clientId: String, refreshToken: String): SpotifyTokens =
            refreshFailure?.let { throw it } ?: tokens()

        private fun tokens() = SpotifyTokens("access", "Bearer", expiresInSeconds, "refresh", "user-library-read")
    }

    private class FakeApi(
        private val currentPlaybackResponse: SpotifyApiResponse = SpotifyApiResponse(200, "{}"),
        var savedTracksResponse: SpotifyApiResponse = SpotifyApiResponse(200, "{\"items\":[]}"),
    ) : SpotifyApiClient {
        override fun searchTracks(accessToken: String, query: String, limit: Int) = SpotifyApiResponse(200, "{}")
        override fun currentPlayback(accessToken: String) = currentPlaybackResponse
        override fun savedTracks(accessToken: String, limit: Int) = savedTracksResponse
        override fun pausePlayback(accessToken: String) = SpotifyApiResponse(204, "")
        override fun resumePlayback(accessToken: String) = SpotifyApiResponse(204, "")
    }

    private object ImmediateCallback : SpotifyAuthorizationCallback {
        override val redirectUri: URI = URI("http://127.0.0.1:8877/callback")
        private var expectedState = ""
        override fun expectState(state: String) {
            expectedState = state
        }
        override fun await(timeout: Duration) = SpotifyCallbackResult("code", expectedState)
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private object FailingCallback : SpotifyAuthorizationCallback {
        override val redirectUri: URI = URI("http://127.0.0.1:8877/callback")
        override fun expectState(state: String) = Unit
        override fun await(timeout: Duration): SpotifyCallbackResult = error("access_denied")
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private class DeferredCallback : SpotifyAuthorizationCallback {
        override val redirectUri: URI = URI("http://127.0.0.1:8877/callback")
        private val gate = CountDownLatch(1)
        private val started = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private var expectedState = ""

        override fun expectState(state: String) {
            expectedState = state
        }

        override fun await(timeout: Duration): SpotifyCallbackResult {
            started.countDown()
            gate.await(2, TimeUnit.SECONDS)
            return SpotifyCallbackResult("code", expectedState)
        }

        fun awaitStarted() = assertTrue(started.await(2, TimeUnit.SECONDS), "Callback did not start")
        fun awaitClosed() = assertTrue(closed.await(2, TimeUnit.SECONDS), "Callback did not close")
        fun complete() = gate.countDown()
        override fun cancel() = Unit
        override fun close() {
            closed.countDown()
        }
    }
}
