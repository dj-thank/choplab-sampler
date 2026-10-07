@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.sampler.source.*
import com.choplab.sampler.ui.LibraryBrowserPanel
import com.choplab.desktop.SpotifyCatalogPanel
import com.choplab.desktop.provider.SpotifyCatalogBrowser
import com.choplab.desktop.provider.SpotifyCatalogBrowserTest
import com.choplab.desktop.spotify.SpotifyApiResponse
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Actual Compose layout, 800 synthetic metadata records, and an explicit silent selection port. */
class MusicBrowserUiTest {
    @Test fun rateLimitExpiryReenablesExplicitBrowsingWithoutAnAutomaticRetry() = runBlocking {
        val requests = AtomicInteger()
        SpotifyCatalogBrowserTest().session {
            if (requests.incrementAndGet() == 1) SpotifyApiResponse(429, "", 2)
            else SpotifyApiResponse(200, """{"items":[],"next":null}""")
        }.use { session ->
            session.login()
            while (session.state.value.busy) delay(5)
            assertTrue(session.connected)
            SpotifyCatalogBrowser(session).use { browser ->
                browser.root(SpotifyCatalogKind.TRACK)
                while (session.state.value.busy) delay(5)
                val scene = ImageComposeScene(width = 960, height = 700, density = Density(1f), coroutineContext = coroutineContext) {
                    val state by session.state.collectAsState()
                    com.choplab.sampler.ui.theme.ChopLabTheme { SpotifyCatalogPanel(state, browser) }
                }
                try {
                    settle(scene)
                    assertNotNull(node(scene, "spotify-track").config.getOrNull(SemanticsProperties.Disabled))
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (node(scene, "spotify-track").config.getOrNull(SemanticsProperties.Disabled) != null && System.nanoTime() < deadline) settle(scene)
                    assertNull(node(scene, "spotify-track").config.getOrNull(SemanticsProperties.Disabled))
                    assertEquals(1, requests.get())
                    click(scene, "spotify-track")
                    while (session.state.value.busy) delay(5)
                    assertEquals(2, requests.get())
                } finally { scene.close() }
            }
        }
    }

    @Test fun artistsAlbumsAndSongsNavigateWithoutOpeningAudioUntilATrackIsChosen() = runBlocking {
        val items = (0 until 800).map { i -> AudioLibraryItem("id$i", "Song $i", "file", 100, "Artist ${i / 80}", "Album ${i / 20}") }
        var opened: String? = null
        val scene = ImageComposeScene(width = 960, height = 700, density = Density(1f), coroutineContext = coroutineContext) {
            com.choplab.sampler.ui.theme.ChopLabTheme { LibraryBrowserPanel(items, true, { opened = it }) }
        }
        try {
            settle(scene)
            assertNull(opened)
            capture(scene, "library-artists-960")
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library-track-") == true })
            click(scene, "library-group-Artist 0-")
            capture(scene, "library-albums-960")
            click(scene, "library-group-Artist 0-Album 0")
            assertNull(opened)
            capture(scene, "library-artist-album-960")
            click(scene, "library-track-id0")
            assertEquals("id0", opened)
            assertEquals(800, items.size)
        } finally { scene.close() }
    }

    @Test fun narrowLargeTextJapaneseAndEnglishKeepBrowseAndPageControlsVisible() = runBlocking {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) {
                Locale.setDefault(locale)
                val items = (0 until 800).map { i -> AudioLibraryItem("id$i", "Song $i", "file", 100) }
                val scene = ImageComposeScene(width = 390, height = 720, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                    com.choplab.sampler.ui.theme.ChopLabTheme { LibraryBrowserPanel(items, true, { error("Browsing cannot open audio") }) }
                }
                try {
                    settle(scene)
                    click(scene, "library-group--")
                    click(scene, "library-group--")
                    val next = node(scene, "library-next")
                    assertTrue(next.size.height >= 48)
                    assertTrue(next.boundsInRoot.bottom <= 720f && next.boundsInRoot.left >= 0f && next.boundsInRoot.right <= 390f)
                    click(scene, "library-next")
                    assertTrue(nodes(scene).none { it.config.getOrNull(SemanticsProperties.TestTag) == "library-track-id0" })
                    capture(scene, "library-390-font2-${locale.language}")
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun node(scene: ImageComposeScene, tag: String) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private suspend fun click(scene: ImageComposeScene, tag: String) {
        assertTrue(requireNotNull(node(scene, tag).config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        settle(scene)
    }
    private suspend fun settle(scene: ImageComposeScene) { repeat(8) { scene.render(System.nanoTime()).close(); delay(10) } }
    private fun capture(scene: ImageComposeScene, name: String) {
        val folder = File(requireNotNull(System.getProperty("uiReview.evidenceDir"))).apply { mkdirs() }
        scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { File(folder, "$name.png").writeBytes(it.bytes) } }
    }
}
