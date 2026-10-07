package com.choplab.desktop.provider

import com.choplab.desktop.spotify.*
import com.choplab.sampler.source.*
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SpotifyCatalogBrowserTest {
    @Test fun loginHasNoCatalogFetchAndEightHundredFavoritesAreRequestedOnePageAtATime() {
        val requests = mutableListOf<SpotifyCatalogRequest>()
        session { request ->
            requests += request
            SpotifyApiResponse(200, """{"items":[${(request.offset until request.offset + 20).joinToString(",") { "{\"track\":${track(it)}}" }}],"total":800,"next":"not-followed"}""")
        }.use { session ->
            connect(session)
            SpotifyCatalogBrowser(session).use { browser ->
                assertNull(browser.page); assertTrue(requests.isEmpty())
                browser.root(SpotifyCatalogKind.TRACK); idle(session)
                assertEquals(20, browser.page!!.entries.size)
                assertEquals(800, browser.page!!.total)
                assertEquals(listOf(0), requests.map { it.offset })
                assertTrue(browser.selectedTracks.isEmpty())
                browser.next(); idle(session)
                assertEquals(20, browser.page!!.entries.size)
                assertEquals(listOf(0, 20), requests.map { it.offset })
                browser.selectPage()
                var added = emptyList<SourceTrack>()
                assertTrue(browser.addSelected { added = it; true })
                assertEquals(20, added.size)
                assertEquals("Song 20", added.first().title)
                assertTrue(browser.selectedTracks.isEmpty())
                assertEquals(2, requests.size) // Selecting/downloading never fetches the rest of the library.
            }
        }
    }

    @Test fun artistToAlbumToSongsAndBackReuseOnlyTheExplicitSelection() {
        val requests = mutableListOf<SpotifyCatalogRequest>()
        session { request ->
            requests += request
            when (request.route) {
                SpotifyCatalogRoute.SEARCH -> SpotifyApiResponse(200, """{"artists":{"items":[{"id":"${id(1)}","name":"Artist"}],"next":null}}""")
                SpotifyCatalogRoute.ARTIST_ALBUMS -> SpotifyApiResponse(200, """{"items":[{"id":"${id(2)}","name":"Album","artists":[{"name":"Artist"}]}],"next":null}""")
                SpotifyCatalogRoute.ALBUM_TRACKS -> SpotifyApiResponse(200, """{"items":[${track(3)},${track(4)}],"next":null}""")
                else -> error("Unexpected request")
            }
        }.use { session ->
            connect(session)
            SpotifyCatalogBrowser(session).use { browser ->
                browser.setQuery("Artist"); browser.search(); idle(session)
                browser.open(browser.page!!.entries.single()); idle(session)
                val album = browser.page!!.entries.single()
                browser.open(album); idle(session)
                assertEquals(2, browser.page!!.entries.size)
                val chosen = browser.page!!.entries.first().track!!
                assertEquals("Album", chosen.album)
                browser.toggle(chosen)
                var added = emptyList<SourceTrack>()
                assertTrue(browser.addSelected { added = it; true })
                assertEquals(listOf(chosen), added)
                browser.back(); assertEquals(listOf(album), browser.page!!.entries)
                browser.back(); assertEquals(SpotifyCatalogKind.ARTIST, browser.page!!.entries.single().kind)
                assertEquals(3, requests.size)
                session.disconnect(); assertNull(browser.page)
                assertFalse(browser.canBack); assertTrue(browser.selectedTracks.isEmpty())
            }
        }
    }

    @Test fun changingSearchDropsAnIgnoredLateResponseAndClosingCannotLeaveASelectedDownload() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        session { request ->
            if (request.query == "old") {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
            SpotifyApiResponse(200, """{"artists":{"items":[{"id":"${id(1)}","name":"${request.query}"}],"next":null}}""")
        }.use { session ->
            connect(session)
            val browser = SpotifyCatalogBrowser(session)
            browser.setQuery("old"); browser.search(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            browser.setQuery("new"); release.countDown(); browser.search(); idle(session)
            assertEquals("new", browser.page!!.entries.single().title)
            browser.close(); assertNull(browser.page)
            assertFalse(browser.addSelected { error("Closed browser cannot download") })
        }
    }

    @Test fun rejectedDownloadKeepsTheSelectionAndNavigationNeverCallsADownloadPort() {
        session { SpotifyApiResponse(200, """{"items":[{"track":${track(1)}}],"next":null}""") }.use { session ->
            connect(session)
            SpotifyCatalogBrowser(session).use { browser ->
                browser.root(SpotifyCatalogKind.TRACK); idle(session)
                assertFalse(browser.addSelected { error("No selection") })
                browser.selectPage(); assertFalse(browser.addSelected { false })
                assertEquals(1, browser.selectedTracks.size)
                browser.home(); assertTrue(browser.selectedTracks.isEmpty()); assertNull(browser.page)
            }
        }
    }

    @Test fun reconnectCannotReuseAnotherAccountsCachedPageOrSelectionEvenWithoutAnIntermediateRender() {
        session { SpotifyApiResponse(200, """{"items":[{"track":${track(1)}}],"next":null}""") }.use { session ->
            connect(session)
            SpotifyCatalogBrowser(session).use { browser ->
                browser.root(SpotifyCatalogKind.TRACK); idle(session); browser.selectPage()
                assertEquals(1, browser.selectedTracks.size)
                session.disconnect(); connect(session)
                assertNull(browser.page)
                assertTrue(browser.selectedTracks.isEmpty())
                assertFalse(browser.canBack)
                assertFalse(browser.addSelected { error("Old selection must be gone") })
            }
        }
    }

    internal fun session(fetch: (SpotifyCatalogRequest) -> SpotifyApiResponse): SpotifyDesktopSession {
        val callback = object : SpotifyAuthorizationCallback {
            override val redirectUri = URI("http://127.0.0.1:8877/callback")
            private var state = ""
            override fun expectState(state: String) { this.state = state }
            override fun await(timeout: Duration) = SpotifyCallbackResult("synthetic-code", state)
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val api = object : SpotifyApiClient {
            override fun catalogPage(accessToken: String, request: SpotifyCatalogRequest) = fetch(request)
            override fun searchTracks(accessToken: String, query: String, limit: Int): SpotifyApiResponse = error("unused")
            override fun savedTracks(accessToken: String, limit: Int): SpotifyApiResponse = error("No automatic favorites")
            override fun currentPlayback(accessToken: String): SpotifyApiResponse = error("No automatic playback")
            override fun pausePlayback(accessToken: String): SpotifyApiResponse = error("unused")
            override fun resumePlayback(accessToken: String): SpotifyApiResponse = error("unused")
        }
        return SpotifyDesktopSession({}, "0123456789abcdef0123456789abcdef", api = api,
            callbackFactory = SpotifyAuthorizationCallbackFactory { callback }, browser = SpotifyBrowser {},
            purpose = SpotifySessionPurpose.METADATA_ONLY, tokenClient = object : SpotifyTokenClient {
                override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String) =
                    SpotifyTokens("synthetic-token", "Bearer", 3600, "synthetic-refresh", "user-library-read")
                override fun refresh(clientId: String, refreshToken: String): SpotifyTokens = error("Unexpected refresh")
            })
    }
    private fun connect(session: SpotifyDesktopSession) { session.login(); idle(session); assertTrue(session.connected) }
    private fun idle(session: SpotifyDesktopSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.state.value.busy && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(session.state.value.busy)
    }
    private fun id(n: Int) = n.toString().padStart(22, '0')
    private fun track(n: Int) = """{"id":"${id(n)}","name":"Song $n","duration_ms":120000,"artists":[{"name":"Artist"}]}"""
}
