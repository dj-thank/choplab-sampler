package com.choplab.desktop.source

import com.choplab.desktop.provider.*
import com.choplab.sampler.source.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class SpotifyAutoImportTest {
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline)Thread.sleep(10)
        assertTrue(condition())
    }
    @Test fun connectingAutomaticallyFetchesAndImportsOnceThenExplicitSyncIsAllowed() {
        val root=Files.createTempDirectory("spotify-automatic-flow").toFile()
        val state=MutableStateFlow(SpotifyDesktopState())
        val requests=AtomicInteger()
        val searches=AtomicInteger()
        val backend=object:YoutubeSourceBackend {
            override fun search(query:String,jobId:String):List<YoutubeSource> { searches.incrementAndGet();return emptyList() }
            override fun info(url:String,jobId:String):YoutubeSource=error("no match")
            override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File=error("no match")
            override fun cancel(jobId:String)=Unit
        }
        try {
            AudioSourceController(LocalAudioLibrary(root){},backend).use { sources ->
                SpotifyAutoImport(state,sources) {
                    requests.incrementAndGet()
                    state.value=state.value.copy(importLibraryRevision=state.value.importLibraryRevision+1,
                        sourceTracks=listOf(SourceTrack("song","artist","https://open.spotify.com/track/0000000000000000000001",120.0)))
                }.use { sync ->
                    state.value=state.value.copy(phase=SpotifyConnectionPhase.CONNECTED)
                    await { sources.state.value.spotifySync?.completed==1 && !sources.state.value.busy }
                    assertEquals(1,requests.get())
                    assertNull(sources.state.value.pendingUseId)
                    state.value=state.value.copy(currentTrack="unrelated playback update")
                    Thread.sleep(80)
                    assertEquals(1,requests.get())
                    sync.syncAgain()
                    await { requests.get()==2 && searches.get()==2 && !sources.state.value.busy }
                    sync.cancel()
                    state.value=state.value.copy(currentTrack="still paused")
                    Thread.sleep(80)
                    assertEquals(2,requests.get())
                    assertTrue(sync.addTrack(SourceTrack("other","artist","https://open.spotify.com/track/0000000000000000000002",120.0)))
                    await { searches.get()==3 && !sources.state.value.busy }
                    assertEquals(2,requests.get()) // Search additions do not refetch or overwrite favorites.
                }
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun failedMetadataFetchDoesNotRetryOrImportStaleTracksUntilRequested() {
        val root=Files.createTempDirectory("spotify-metadata-failure").toFile()
        val state=MutableStateFlow(SpotifyDesktopState(phase=SpotifyConnectionPhase.CONNECTED))
        val requests=AtomicInteger()
        val backend=object:YoutubeSourceBackend {
            override fun search(query:String,jobId:String):List<YoutubeSource> = error("must not import")
            override fun info(url:String,jobId:String):YoutubeSource=error("unused")
            override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File=error("unused")
            override fun cancel(jobId:String)=Unit
        }
        try {
            AudioSourceController(LocalAudioLibrary(root){},backend).use { sources ->
                SpotifyAutoImport(state,sources) {
                    requests.incrementAndGet()
                    state.value=state.value.copy(busy=true)
                }.use { sync ->
                    await { requests.get()==1 }
                    state.value=state.value.copy(busy=false,message="429: retry later")
                    Thread.sleep(100)
                    assertEquals(1,requests.get())
                    assertNull(sources.state.value.spotifySync)
                    sync.syncAgain()
                    await { requests.get()==2 }
                    sync.cancel()
                    state.value=state.value.copy(busy=false,importLibraryRevision=1)
                    Thread.sleep(80)
                    assertNull(sources.state.value.spotifySync)
                }
            }
        } finally {root.deleteRecursively()}
    }
}
