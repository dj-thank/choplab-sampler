package com.choplab.sampler.source

import com.choplab.sampler.audio.WavFileWriter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*

class SpotifyAutomaticImportTest {
    private fun track(n:Int) = SourceTrack("Song $n","Artist","https://open.spotify.com/track/" + n.toString().padStart(22,'0'),120.0)
    private fun source(n:Int) = YoutubeSource(n.toString().padStart(11,'0'),"Artist Song $n","Artist",120.0)
    private fun library(root:File) = LocalAudioLibrary(File(root,"library")) { require(it.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII)=="RIFF") }
    private open class Backend:YoutubeSourceBackend {
        var searches=0
        var downloads=0
        override fun search(query:String,jobId:String):List<YoutubeSource> {
            searches++
            val n=query.substringAfterLast(' ').toInt()
            return listOf(YoutubeSource(n.toString().padStart(11,'0'),"Artist Song $n","Artist",120.0))
        }
        override fun info(url:String,jobId:String):YoutubeSource {
            val n=url.substringAfter("v=").toInt()
            return YoutubeSource(n.toString().padStart(11,'0'),"Artist Song $n","Artist",120.0)
        }
        override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File {
            downloads++
            return File(folder,"audio.wav").also { file ->
                WavFileWriter(file,8000,1).use { it.writePcm16(ShortArray(100) { source.id.toInt().toShort() }) }
            }
        }
        override fun cancel(jobId:String) = Unit
    }
    private fun await(hub:AudioSourceController) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(hub.state.value.busy && System.nanoTime()<deadline)Thread.sleep(5)
        assertFalse("import timed out",hub.state.value.busy)
    }

    @Test fun batchAddsAllWithoutSelectingAudioAndReopenDoesNotSearchOrDownloadAgain() {
        val root=Files.createTempDirectory("spotify-batch").toFile()
        try {
            val backend=Backend()
            AudioSourceController(library(root),backend).use { hub ->
                hub.syncSpotifyFavorites(listOf(track(1),track(2),track(1)));await(hub)
                assertEquals(2,hub.state.value.library.size)
                assertEquals(2,hub.state.value.spotifySync!!.added)
                assertNull(hub.state.value.pendingUseId)
                assertTrue(hub.state.value.candidates.isEmpty())
                assertEquals(SourceSection.LIBRARY,hub.state.value.section)
            }
            AudioSourceController(library(root),backend).use { hub ->
                hub.syncSpotifyFavorites(listOf(track(1),track(2)));await(hub)
                assertEquals(2,hub.state.value.spotifySync!!.existing)
                assertEquals(0,hub.state.value.spotifySync!!.added)
                assertEquals(2,backend.searches)
                assertEquals(2,backend.downloads)
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun multipleCompatibleCandidatesUseClosestDurationWithoutASelectionPrompt() {
        val exact=source(1)
        val alternate=exact.copy(id="abcdefghijk",durationSeconds=125.0)
        assertEquals(exact,SourceRecipes.automaticFavorite(track(1),listOf(alternate,exact)))
        assertEquals(exact,SourceRecipes.automaticFavorite(track(1),listOf(exact,alternate)))
        assertNull(SourceRecipes.automaticFavorite(track(1),listOf(exact.copy(title="Artist Song 1 cover"))))
        assertNull(SourceRecipes.automaticFavorite(track(1).copy(artist=""),listOf(exact)))
    }

    @Test fun oneFailureAndChangedInfoDoNotBlockTheRemainingSongsOrExposeCandidates() {
        val root=Files.createTempDirectory("spotify-partial").toFile()
        val backend=object:Backend() {
            override fun search(query:String,jobId:String):List<YoutubeSource> {
                if(query.endsWith("1"))throw java.io.IOException("offline")
                return super.search(query,jobId)
            }
            override fun info(url:String,jobId:String):YoutubeSource {
                val value=super.info(url,jobId)
                return if(value.id.toInt()==2)value.copy(title=value.title+" remix") else value
            }
        }
        try {
            AudioSourceController(library(root),backend).use { hub ->
                hub.syncSpotifyFavorites(listOf(track(1),track(2),track(3)));await(hub)
                assertEquals(1,hub.state.value.library.size)
                assertEquals(2,hub.state.value.spotifySync!!.unavailable.size)
                assertEquals(3,hub.state.value.spotifySync!!.completed)
                assertEquals(1,backend.downloads)
                assertNull(hub.state.value.pendingUseId)
                assertTrue(hub.state.value.candidates.isEmpty())
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun cancelledSearchCannotPublishOrContinueQueueAndResyncRecovers() {
        val root=Files.createTempDirectory("spotify-cancel").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val backend=object:Backend() {
            var first=true
            override fun search(query:String,jobId:String):List<YoutubeSource> {
                if(first) {
                    first=false;entered.countDown()
                    while(release.count>0)try{release.await()}catch(_:InterruptedException){}
                }
                return super.search(query,jobId)
            }
        }
        try {
            AudioSourceController(library(root),backend).use { hub ->
                hub.syncSpotifyFavorites(listOf(track(1),track(2)))
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                hub.dismiss()
                assertTrue(hub.state.value.busy)
                hub.cancel()
                assertFalse(hub.state.value.busy)
                release.countDown()
                hub.syncSpotifyFavorites(listOf(track(1),track(2)));await(hub)
                assertEquals(2,backend.downloads)
                assertEquals(2,hub.state.value.spotifySync!!.added)
                assertNull(hub.state.value.pendingUseId)
            }
        } finally {release.countDown();root.deleteRecursively()}
    }

    @Test fun anExistingYoutubeImportIsLinkedWithoutDownloadingAgain() {
        val root=Files.createTempDirectory("spotify-known-source").toFile()
        try {
            val library=library(root);val backend=Backend()
            val input=backend.download(source(1),root,"fixture",{})
            library.importFile(input,"previous title",source(1).url)
            AudioSourceController(library,backend).use { hub ->
                hub.syncSpotifyFavorites(listOf(track(1)));await(hub)
                assertEquals(1,backend.downloads)
                assertEquals(1,hub.state.value.spotifySync!!.existing)
                assertEquals("previous title",library.spotifyItem(track(1).spotifyUrl)!!.title)
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun automaticImportPreservesManualSelectionUntilItIsConsumed() {
        val root=Files.createTempDirectory("spotify-manual-notification").toFile()
        try {
            val backend=Backend()
            val input=backend.download(source(1),root,"fixture",{})
            AudioSourceController(library(root),backend).use { hub ->
                hub.importFiles(listOf(input));await(hub)
                val pending=hub.state.value.pendingUseId
                assertNotNull(pending)
                assertFalse(hub.syncSpotifyFavorites(listOf(track(2))))
                assertEquals(pending,hub.state.value.pendingUseId)
                hub.consumed(pending!!)
                assertTrue(hub.syncSpotifyFavorites(listOf(track(2))))
                // Ownership exists synchronously, before the worker begins or publishes progress.
                assertNotNull(hub.state.value.spotifySync)
                await(hub)
                assertEquals(2,hub.state.value.library.size)
                assertNull(hub.state.value.pendingUseId)
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun spotifyDisconnectCannotCancelAnUnrelatedManualImport() {
        val root=Files.createTempDirectory("spotify-manual-owner").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val backend=object:Backend() {
            override fun search(query:String,jobId:String):List<YoutubeSource> {
                entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS))
                return super.search(query,jobId)
            }
        }
        try {
            AudioSourceController(library(root),backend).use { hub ->
                hub.query("Artist Song 1");hub.search()
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                hub.cancelSpotify()
                assertTrue(hub.state.value.busy)
                release.countDown();await(hub)
                assertEquals(1,hub.state.value.candidates.size)
            }
        } finally {release.countDown();root.deleteRecursively()}
    }
}
