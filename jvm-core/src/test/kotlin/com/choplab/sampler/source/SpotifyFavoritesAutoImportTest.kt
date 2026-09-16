package com.choplab.sampler.source

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android binding: the shared coordinator driven by [SpotifyImportState]. */
class SpotifyFavoritesAutoImportTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private fun coordinator(state: MutableStateFlow<SpotifyImportState>, sources: AudioSourceController, load: () -> Unit) =
        SpotifyFavoritesAutoImport(state, sources, load,
            isConnected = { it.connected }, isBusy = { it.busy },
            libraryRevision = { it.libraryRevision }, libraryTracks = { it.tracks })

    @Test
    fun connectingImportsLikedTracksOnceAndSearchAdditionsDoNotRefetch() {
        val root = Files.createTempDirectory("android-spotify-sync").toFile()
        val state = MutableStateFlow(SpotifyImportState())
        val loads = AtomicInteger()
        val searches = AtomicInteger()
        val backend = object : YoutubeSourceBackend {
            override fun search(query: String, jobId: String): List<YoutubeSource> { searches.incrementAndGet(); return emptyList() }
            override fun info(url: String, jobId: String): YoutubeSource = error("no match")
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File = error("no match")
            override fun cancel(jobId: String) = Unit
        }
        try {
            AudioSourceController(LocalAudioLibrary(root) {}, backend).use { sources ->
                coordinator(state, sources) {
                    loads.incrementAndGet()
                    state.value = state.value.copy(libraryRevision = state.value.libraryRevision + 1,
                        tracks = listOf(SourceTrack("song", "artist", "https://open.spotify.com/track/0000000000000000000001", 120.0)))
                }.use { sync ->
                    state.value = state.value.copy(connected = true)
                    await { sources.state.value.spotifySync?.completed == 1 && !sources.state.value.busy }
                    assertEquals(1, loads.get())
                    assertNull(sources.state.value.pendingUseId)
                    state.value = state.value.copy(searchQuery = "other")
                    Thread.sleep(80)
                    assertEquals(1, loads.get())
                    assertTrue(sync.addTrack(SourceTrack("other", "artist", "https://open.spotify.com/track/0000000000000000000002", 120.0)))
                    await { searches.get() == 2 && !sources.state.value.busy }
                    assertEquals(1, loads.get())
                    sync.syncAgain()
                    await { loads.get() == 2 && searches.get() == 3 && !sources.state.value.busy }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun disconnectingDuringImportCancelsOnlyTheOwnedQueue() {
        val root = Files.createTempDirectory("android-spotify-disconnect").toFile()
        val state = MutableStateFlow(SpotifyImportState(connected = true))
        val entered = java.util.concurrent.CountDownLatch(1)
        val backend = object : YoutubeSourceBackend {
            override fun search(query: String, jobId: String): List<YoutubeSource> {
                entered.countDown()
                Thread.sleep(5_000)
                return emptyList()
            }
            override fun info(url: String, jobId: String): YoutubeSource = error("unused")
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File = error("unused")
            override fun cancel(jobId: String) = Unit
        }
        try {
            AudioSourceController(LocalAudioLibrary(root) {}, backend).use { sources ->
                coordinator(state, sources) {
                    state.value = state.value.copy(libraryRevision = state.value.libraryRevision + 1,
                        tracks = listOf(SourceTrack("song", "artist", "https://open.spotify.com/track/0000000000000000000001", 120.0)))
                }.use {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(sources.state.value.busy)
                    state.value = state.value.copy(connected = false)
                    await { !sources.state.value.busy }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
