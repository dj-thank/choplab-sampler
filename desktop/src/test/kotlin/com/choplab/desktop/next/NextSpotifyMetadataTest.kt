package com.choplab.desktop.next

import com.choplab.desktop.provider.*
import com.choplab.desktop.spotify.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** No real OAuth, browser, window, audio device or provider; exercises the native window's controller. */
class NextSpotifyMetadataTest {
    @Test fun metadataSessionRequestsOnlyLibraryReadAndRefusesPlaybackAndAutoImport() {
        var authorization: URI? = null
        val api = FakeApi()
        session(api, browser = SpotifyBrowser { authorization = it }).use { session ->
            connect(session)
            val query = requireNotNull(authorization).rawQuery.split('&').associate { part ->
                part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8)
            }
            assertEquals("user-library-read", query["scope"])
            assertEquals("code", query["response_type"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals("http://127.0.0.1:8877/callback", query["redirect_uri"])
            assertFalse(query["state"].isNullOrBlank())
            assertFailsWith<IllegalStateException> { session.pause() }
            assertFailsWith<IllegalStateException> { session.resume() }
            assertFailsWith<IllegalStateException> { session.showCurrentPlayback() }
            assertFailsWith<IllegalStateException> { session.loadImportLibrary() }
            assertEquals(0, api.calls.get())
            assertFalse(session.state.value.toString().contains("test-access-token"))
        }
    }

    @Test fun rateLimitShowsCountdownPreventsEarlyRequestsAndAllowsExplicitRetryToEmpty() {
        val clock = AtomicReference(Instant.parse("2026-09-28T00:00:00Z"))
        val api = FakeApi().apply { library = SpotifyApiResponse(429, "private provider response", 12) }
        session(api, now = clock::get).use { session ->
            connect(session)
            NextSpotifyMetadataController(session).use { window ->
                window.favorites(); idle(session)
                assertEquals(Res.string.ce_spotify_retry_after, window.view().status)
                assertEquals(listOf(12L), window.view().statusArguments)
                assertFalse(window.view().canFetch)
                window.favorites()
                assertEquals(1, api.calls.get())
                clock.set(clock.get().plusMillis(11_001))
                assertEquals(listOf(1L), window.view().statusArguments)
                window.setQuery("new query"); window.search()
                assertEquals(1, api.calls.get())
                clock.set(clock.get().plusMillis(999))
                assertTrue(window.view().canFetch)
                api.library = SpotifyApiResponse(200, """{"items":[],"next":null}""")
                window.favorites(); idle(session)
                assertEquals(Res.string.ce_spotify_empty, window.view().status)
                assertNull(session.state.value.problem)
                assertEquals(2, api.calls.get())
                assertFalse(session.state.value.toString().contains("private provider response"))
            }
        }
    }

    @Test fun browserErrorsPersistAcrossRefreshThenRetryAndDisconnectClearsMetadata() {
        var failLogin = true
        var failLink = true
        val api = FakeApi().apply { library = library("First") }
        session(api, browser = SpotifyBrowser { if (failLogin) throw SpotifyBrowserUnavailableException() }).use { session ->
            NextSpotifyMetadataController(session, SpotifyBrowser { if (failLink) throw IOException("private detail") }).use { window ->
                window.login(); idle(session)
                assertEquals(Res.string.ce_spotify_browser, window.view().status)
                assertTrue(window.view().canLogin)
                failLogin = false; window.login(); idle(session)
                assertTrue(session.connected)
                window.favorites(); idle(session)
                val track = window.view().tracks.single()
                window.open(track)
                repeat(3) { assertEquals(Res.string.ce_spotify_browser, window.view().status) }
                failLink = false; window.open(track)
                assertEquals(Res.string.ce_spotify_connected, window.view().status)
                window.disconnect()
                assertTrue(window.view().tracks.isEmpty())
                assertFalse(session.connected)
                assertTrue(window.view().canLogin)
            }
        }
    }

    @Test fun closeCancelsPendingLoginAndNeverExchangesALateCode() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val exchanges = AtomicInteger()
        val callback = object : ImmediateCallback() {
            override fun await(timeout: Duration): SpotifyCallbackResult {
                entered.countDown(); awaitIgnoringInterrupt(release)
                return super.await(timeout)
            }
            override fun close() { completed.countDown() }
        }
        session(callback = callback, exchanges = exchanges).use { session ->
            val window = NextSpotifyMetadataController(session)
            window.login(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            window.close(); release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, exchanges.get())
            assertFalse(session.connected)
            assertFalse(session.state.value.busy)
            assertEquals(SpotifyConnectionPhase.READY, session.state.value.phase)
        }
    }

    @Test fun closingWindowDropsLateMetadataAndReopeningCanRetryWithoutChangingTheSong() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = FakeApi().apply { firstLibrary = { entered.countDown(); awaitIgnoringInterrupt(release); library("Old") }; library = library("Fresh") }
        val directory = Files.createTempDirectory("spotify-next-host")
        val backend = NextBackend.create(directory, sinkFactory = { error("No device in metadata tests") }, microphone = { null })
        val session = session(api)
        try {
            DesktopEditorPorts(backend, session) { null }.use { ports ->
                assertTrue(ports.spotifyMetadataAvailable)
                val original = backend.studio.document.value
                connect(session)
                val first = NextSpotifyMetadataController(session)
                first.favorites(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                first.close()
                assertFalse(session.state.value.busy)
                assertTrue(session.connected)
                release.countDown()
                NextSpotifyMetadataController(session).use { second ->
                    second.favorites(); idle(session)
                    assertEquals("Fresh", second.view().tracks.single().title)
                    assertEquals(original, backend.studio.document.value)
                    assertEquals(0L, session.state.value.importLibraryRevision)
                }
            }
            assertFalse(session.connected)
            assertTrue(session.state.value.sourceTracks.isEmpty())
            session.login()
            assertFalse(session.state.value.busy)
        } finally {
            release.countDown(); session.close(); backend.shutdown(flush = false); directory.toFile().deleteRecursively()
        }
    }

    @Test fun changingQueryCancelsOldSearchIncludingWhenChangedBackBeforeItsResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = object : FakeApi() {
            override fun searchTracks(accessToken: String, query: String, limit: Int): SpotifyApiResponse {
                if (calls.incrementAndGet() == 1) { entered.countDown(); awaitIgnoringInterrupt(release); return search("Old") }
                return search("Fresh")
            }
        }
        session(api).use { session ->
            connect(session)
            NextSpotifyMetadataController(session).use { window ->
                window.setQuery("A"); window.search(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                window.setQuery("B"); window.setQuery("A")
                assertFalse(session.state.value.busy)
                release.countDown(); window.search(); idle(session)
                assertEquals("Fresh", window.view().tracks.single().title)
                assertEquals("A", session.state.value.searchCompletedQuery)
            }
        }
    }

    @Test fun initialAndMalformedResponsesAreNotDisplayedAsSuccessfulEmptyResults() {
        val api = FakeApi().apply { library = SpotifyApiResponse(200, "{}") }
        session(api).use { session ->
            connect(session)
            NextSpotifyMetadataController(session).use { window ->
                assertEquals(Res.string.ce_spotify_connected, window.view().status)
                window.favorites(); idle(session)
                assertEquals(Res.string.ce_spotify_api, window.view().status)
                api.library = SpotifyApiResponse(200, """{"items":[]}""")
                window.favorites(); idle(session)
                assertEquals(Res.string.ce_spotify_empty, window.view().status)
                window.setQuery("missing"); window.search(); idle(session)
                assertEquals(Res.string.ce_spotify_empty, window.view().status)
                window.setQuery("another")
                assertEquals(Res.string.ce_spotify_connected, window.view().status)
            }
        }
    }

    @Test fun failedNextPagePreservesFavoritesAndRetryUsesTheSameOffset() {
        val offsets = mutableListOf<Int>()
        val api = object : FakeApi() {
            override fun savedTracksPage(accessToken: String, offset: Int): SpotifyApiResponse {
                offsets.add(offset)
                return if (offsets.size == 1) SpotifyApiResponse(503, "unavailable")
                else library("Second").let { it.copy(body = it.body.replace("0000000000000000000001", "0000000000000000000002")) }
            }
        }.apply { library = library("First").let { it.copy(body = it.body.replace("\"next\":null", "\"next\":\"https://api.spotify.com/v1/me/tracks?offset=20\"")) } }
        session(api).use { session ->
            connect(session)
            NextSpotifyMetadataController(session).use { window ->
                window.favorites(); idle(session)
                assertTrue(window.view().canMore)
                window.more(); idle(session)
                assertEquals(listOf("First"), window.view().tracks.map { it.title })
                assertEquals(Res.string.ce_spotify_api, window.view().status)
                window.more(); idle(session)
                assertEquals(listOf(20, 20), offsets)
                assertEquals(listOf("First", "Second"), window.view().tracks.map { it.title })
                assertFalse(window.view().canMore)
                assertNull(session.state.value.problem)
                assertEquals(0L, session.state.value.importLibraryRevision)
            }
        }
    }

    @Test fun allNativeDialogMessagesHaveEnglishAndJapaneseResourcesWithUsableArguments() = runBlocking {
        val previous = Locale.getDefault()
        try {
            for ((locale, empty, retry, redirect) in listOf(
                arrayOf(Locale.ENGLISH, "No results", "Retry in 7 seconds", "Register this redirect URI in Spotify: http://127.0.0.1/callback"),
                arrayOf(Locale.JAPANESE, "見つかりませんでした", "再試行まで 7 秒", "Spotifyに登録するリダイレクトURI: http://127.0.0.1/callback"),
            )) {
                Locale.setDefault(locale as Locale)
                NextSpotifyMetadataController.resources.forEach { assertTrue(getString(it).isNotBlank()) }
                assertEquals(empty, getString(Res.string.ce_spotify_empty))
                assertEquals(retry, getString(Res.string.ce_spotify_retry_after, 7))
                assertEquals(redirect, getString(Res.string.ce_spotify_redirect, "http://127.0.0.1/callback"))
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun session(
        api: SpotifyApiClient = FakeApi(), callback: SpotifyAuthorizationCallback = ImmediateCallback(),
        browser: SpotifyBrowser = SpotifyBrowser {}, now: () -> Instant = Instant::now,
        exchanges: AtomicInteger = AtomicInteger(),
    ) = SpotifyDesktopSession(onStatus = {}, clientId = "0123456789abcdef0123456789abcdef", api = api,
        purpose = SpotifySessionPurpose.METADATA_ONLY, now = now, browser = browser,
        callbackFactory = SpotifyAuthorizationCallbackFactory { callback },
        tokenClient = object : SpotifyTokenClient {
            override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String): SpotifyTokens {
                exchanges.incrementAndGet()
                return SpotifyTokens("test-access-token", "Bearer", 3600, "test-refresh-token", "user-library-read")
            }
            override fun refresh(clientId: String, refreshToken: String): SpotifyTokens = error("Unexpected refresh")
        })

    private open class ImmediateCallback : SpotifyAuthorizationCallback {
        override val redirectUri = URI("http://127.0.0.1:8877/callback")
        private var state = ""
        override fun expectState(state: String) { this.state = state }
        override fun await(timeout: Duration) = SpotifyCallbackResult("test-code", state)
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private open class FakeApi : SpotifyApiClient {
        val calls = AtomicInteger()
        var library = SpotifyApiResponse(200, """{"items":[]}""")
        var firstLibrary: (() -> SpotifyApiResponse)? = null
        override fun savedTracks(accessToken: String, limit: Int): SpotifyApiResponse =
            if (calls.incrementAndGet() == 1) firstLibrary?.invoke() ?: library else library
        override fun searchTracks(accessToken: String, query: String, limit: Int) = SpotifyApiResponse(200, """{"tracks":{"items":[]}}""").also { calls.incrementAndGet() }
        override fun currentPlayback(accessToken: String): SpotifyApiResponse = error("Playback forbidden")
        override fun pausePlayback(accessToken: String): SpotifyApiResponse = error("Playback forbidden")
        override fun resumePlayback(accessToken: String): SpotifyApiResponse = error("Playback forbidden")
    }

    private fun library(name: String) = SpotifyApiResponse(200, """{"items":[{"track":{"id":"0000000000000000000001","name":"$name","artists":[{"name":"Artist"}],"duration_ms":120000}}],"next":null}""")
    private fun search(name: String) = SpotifyApiResponse(200, """{"tracks":{"items":[{"id":"0000000000000000000001","name":"$name","artists":[{"name":"Artist"}]}]}}""")
    private fun connect(session: SpotifyDesktopSession) { session.login(); idle(session); assertTrue(session.connected) }
    private fun idle(session: SpotifyDesktopSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (session.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(session.state.value.busy, "Provider operation did not complete")
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (latch.count > 0 && System.nanoTime() < deadline) try { latch.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        check(latch.count == 0L) { "Test did not release provider response" }
    }
}
