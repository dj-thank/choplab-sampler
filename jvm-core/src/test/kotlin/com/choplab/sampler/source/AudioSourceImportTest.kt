package com.choplab.sampler.source

import com.choplab.sampler.audio.WavFileWriter
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.*

class AudioSourceImportTest {
    private fun testLibrary(file:File)=LocalAudioLibrary(file) { input ->
        val bytes=input.inputStream().use { it.readNBytes(12) }
        require(bytes.size==12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WAVE")
    }

    @Test fun malformedAudioAndFailedBundleNeverPublishLibraryEntries() {
        val root=Files.createTempDirectory("library-reject").toFile()
        try {
            val library=testLibrary(File(root,"library"))
            assertThrows(IllegalArgumentException::class.java){library.importStream("bad audio".byteInputStream(),"mp3","bad","file")}
            val good=File(root,"good.wav").also(::wav)
            val bundle=File(root,"broken.choplib")
            ZipOutputStream(bundle.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("good.wav"));zip.write(good.readBytes());zip.closeEntry()
                zip.putNextEntry(ZipEntry("bad.mp3"));zip.write("bad".toByteArray());zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java){library.importBundle(bundle)}
            assertTrue(library.list().isEmpty())
            assertTrue(library.directory.listFiles().orEmpty().isEmpty())
            ZipOutputStream(bundle.outputStream()).use { zip ->repeat(129) { zip.putNextEntry(ZipEntry("$it.bin"));zip.closeEntry() } }
            assertThrows(IllegalArgumentException::class.java){library.importBundle(bundle)}
            assertTrue(library.directory.listFiles().orEmpty().isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun cancellationAndQueryEditsCannotExposeThePreviousSearch() {
        val root=Files.createTempDirectory("source-cancel").toFile()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val backend=object:YoutubeSourceBackend {
            override fun search(query:String,jobId:String):List<YoutubeSource> {
                if(query=="first") { entered.countDown();while(release.count>0)try{release.await()}catch(_:InterruptedException){} }
                return listOf(YoutubeSource(if(query=="first")"zq-lIBwhWLk" else "abcdefghijk",query,"author",120.0))
            }
            override fun info(url:String,jobId:String):YoutubeSource=error("unused")
            override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File=error("unused")
            override fun cancel(jobId:String)=Unit
        }
        try {
            AudioSourceController(testLibrary(File(root,"library")),backend).use { hub ->
                hub.query("first");hub.search();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
                hub.cancel();hub.query("second");hub.search();release.countDown()
                val deadline=System.currentTimeMillis()+5000
                while(hub.state.value.busy && System.currentTimeMillis()<deadline)Thread.sleep(10)
                assertEquals("second",hub.state.value.candidates.single().title)
                hub.query("third");assertTrue(hub.state.value.candidates.isEmpty())
                hub.cancel();assertNull(hub.state.value.pendingUseId)
            }
        } finally {release.countDown();root.deleteRecursively()}
    }
    @Test fun ambiguousFavoriteCandidatesRequireSelection() {
        val track=SourceTrack("Test Song","Artist","https://open.spotify.com/track/0123456789012345678901",120.0)
        val source=YoutubeSource("zq-lIBwhWLk","Artist Test Song","Artist",120.0)
        assertNull(SourceRecipes.matchingFavorite(track,listOf(source,source.copy(id="abcdefghijk"))))
        assertEquals(source,SourceRecipes.matchingFavorite(track,listOf(source,source)))
    }
    @Test fun oversizedExportRefusesBeforeTouchingExistingTarget() {
        val root=Files.createTempDirectory("library-export-bound").toFile()
        try {
            val library=testLibrary(File(root,"library"))
            repeat(33) { n ->
                val input=File(root,"$n.wav")
                WavFileWriter(input,8000,1).use { it.writePcm16(ShortArray(100) { n.toShort() }) }
                library.importFile(input)
            }
            val target=File(root,"existing.choplib").apply { writeText("existing",Charsets.UTF_8) }
            assertThrows(IllegalArgumentException::class.java){library.exportBundle(target)}
            assertEquals("existing",target.readText(Charsets.UTF_8))
        } finally {root.deleteRecursively()}
    }

    private fun wav(file:File) { WavFileWriter(file,8000,1).use { it.writePcm16(ShortArray(800) { 4000 }) } }
    @Test fun privateLibraryDeduplicatesAndRoundTripsThePortableSet() {
        val root=Files.createTempDirectory("choplab-library-test").toFile()
        try {
            val input=File(root,"素材.wav");wav(input)
            val before=input.readBytes();val library=testLibrary(File(root,"library"))
            val first=library.importFile(input,"素材の曲","ファイル")
            assertEquals(first.id,library.importFile(input).id)
            assertEquals(1,library.list().size)
            assertArrayEquals(before,input.readBytes())
            assertArrayEquals(before,library.resolve(first.id).readBytes())
            val bundle=File(root,"音源.choplib");library.exportBundle(bundle)
            val other=testLibrary(File(root,"other"));other.importBundle(bundle)
            assertEquals("素材の曲",other.list().single().title)
            assertArrayEquals(before,other.resolve(first.id).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun bundleEntryPathsCannotEscapeTheLibrary() {
        val root=Files.createTempDirectory("choplab-bundle-test").toFile()
        try {
            val source=File(root,"source.wav");wav(source)
            val bundle=File(root,"input.zip")
            ZipOutputStream(bundle.outputStream()).use { zip ->zip.putNextEntry(ZipEntry("../../outside.wav"));zip.write(source.readBytes());zip.closeEntry() }
            val library=testLibrary(File(root,"library"));library.importBundle(bundle)
            assertEquals(1,library.list().size)
            assertFalse(File(root,"outside.wav").exists())
            assertThrows(IllegalArgumentException::class.java) { library.resolve("../outside") }
        } finally { root.deleteRecursively() }
    }
    @Test fun urlsAreCanonicalizedAndCommandArgumentsCannotBecomeOptions() {
        assertEquals("https://www.youtube.com/watch?v=zq-lIBwhWLk",SourceRecipes.youtubeUrl("https://youtu.be/zq-lIBwhWLk?si=x"))
        listOf("http://youtube.com/watch?v=zq-lIBwhWLk","https://youtube.com.evil.test/watch?v=zq-lIBwhWLk","https://youtube.com@127.0.0.1/watch?v=zq-lIBwhWLk","https://www.youtube.com/playlist?list=abc").forEach {
            assertThrows(IllegalArgumentException::class.java){SourceRecipes.youtubeUrl(it)}
        }
        val args=SourceRecipes.searchArguments("--exec some-command")
        assertEquals("ytsearch5:--exec some-command",args.last())
        assertFalse(args.contains("--exec"))
        assertTrue(args.contains("--ignore-config"))
    }
    @Test fun favoriteMatchingAcceptsPartAliasesButRejectsCoverAndWrongDuration() {
        val track=SourceTrack("Luv(sic.) pt4","Nujabes","https://open.spotify.com/track/0123456789012345678901",305.0)
        val original=YoutubeSource("zq-lIBwhWLk","Nujabes - Luv(sic) Part 4 feat.Shing02 [Official Audio]","Nujabes",305.0)
        assertEquals(original,SourceRecipes.matchingFavorite(track,listOf(original)))
        assertNull(SourceRecipes.matchingFavorite(track,listOf(original.copy(title=original.title+" cover"))))
        assertNull(SourceRecipes.matchingFavorite(track,listOf(original.copy(durationSeconds=600.0))))
    }
    @Test fun oneFavoriteTapPublishesAndSelectsOnlyTheCompletedLibraryAudio() {
        val root=Files.createTempDirectory("choplab-source-flow").toFile()
        val source=YoutubeSource("zq-lIBwhWLk","Nujabes - Luv(sic) Part 4","Nujabes",305.0)
        var searches=0;var downloads=0
        val backend=object:YoutubeSourceBackend {
            override fun search(query:String,jobId:String):List<YoutubeSource>{searches++;return listOf(source)}
            override fun info(url:String,jobId:String)=source
            override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File {
                downloads++;return File(folder,"audio.wav").also(::wav)
            }
            override fun cancel(jobId:String)=Unit
        }
        try {
            AudioSourceController(testLibrary(File(root,"library")),backend).use { hub ->
                hub.importFavorite(SourceTrack("Luv(sic.) pt4","Nujabes","https://open.spotify.com/track/0123456789012345678901",305.0))
                val deadline=System.currentTimeMillis()+5000
                while(hub.state.value.busy && System.currentTimeMillis()<deadline)Thread.sleep(10)
                assertFalse(hub.state.value.busy)
                assertEquals(1,searches);assertEquals(1,downloads)
                assertNotNull(hub.state.value.pendingUseId)
                assertEquals(1,hub.state.value.library.size)
                assertTrue(hub.file(hub.state.value.pendingUseId!!).isFile)
            }
        } finally { root.deleteRecursively() }
    }
}
