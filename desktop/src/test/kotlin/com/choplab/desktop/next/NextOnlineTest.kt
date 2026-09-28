package com.choplab.desktop.next

import com.choplab.sampler.source.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test

class NextOnlineTest {
    @Test fun selectedOnlineAudioPassesTheProductionSaveExportAndRestartPath() = kotlinx.coroutines.runBlocking<Unit> {
        val root = Files.createTempDirectory("next-online-production-")
        try { NextOnlineSelfTest.run(root) } finally { root.toFile().deleteRecursively() }
    }
    private val source = YoutubeSource("abcdefghijk", "Synthetic original", "Synthetic", 1.0)
    private fun idle(online: NextOnline) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (online.state.value.busy) { check(System.nanoTime() < until); Thread.sleep(5) }
    }
    @Test fun aUrlOnlyShowsACandidateAndExplicitAcquisitionPreservesBytesAndTitle() {
        val root = Files.createTempDirectory("next-online-test-")
        var downloads = 0
        var original: ByteArray? = null
        val backend = object : YoutubeSourceBackend {
            override fun search(query: String, jobId: String) = listOf(source)
            override fun info(url: String, jobId: String) = source
            override fun cancel(jobId: String) {}
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File {
                downloads++; progress(50f)
                return folder.resolve("audio.wav").also { NextSelfTest.writeDemo(it.toPath()); original = it.readBytes() }
            }
        }
        try {
            NextOnline(root.resolve("library"), {}, backend).use { online ->
                assertTrue(online.search(source.url)); idle(online)
                assertEquals(listOf(source), online.state.value.candidates)
                assertEquals(0, downloads); assertNull(online.state.value.saved)
                assertFalse(online.acquire("unknown"))
                assertTrue(online.acquire(source.id)); idle(online)
                val chosen = requireNotNull(online.state.value.saved)
                assertEquals(OnlineSourcePhase.SAVED, online.state.value.phase)
                assertEquals(source.title, chosen.title)
                assertContentEquals(original, Files.readAllBytes(chosen.path))
                assertEquals(1, downloads)
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun cancelRejectsLateResultsAndRetryWorksWithoutAConcurrentWriter() {
        val root = Files.createTempDirectory("next-online-cancel-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var first = true; var cancelled = 0
        val backend = object : YoutubeSourceBackend {
            override fun search(query: String, jobId: String): List<YoutubeSource> {
                if (first) {
                    first = false; entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
                }
                return listOf(source)
            }
            override fun info(url: String, jobId: String) = source
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File = error("Must not download")
            override fun cancel(jobId: String) { cancelled++ }
        }
        try {
            NextOnline(root.resolve("library"), {}, backend).use { online ->
                assertTrue(online.search("synthetic")); assertTrue(entered.await(5, TimeUnit.SECONDS))
                online.cancel(); assertFalse(online.search("retry while owned")); release.countDown(); idle(online)
                assertEquals(OnlineSourcePhase.CANCELLED, online.state.value.phase)
                assertTrue(online.state.value.candidates.isEmpty()); assertNull(online.state.value.saved)
                assertEquals(1, cancelled)
                assertTrue(online.search("retry")); idle(online); assertEquals(listOf(source), online.state.value.candidates)
            }
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }
    @Test fun originalAcquisitionArgumentsDoNotTranscodeOrRequestAVideoFallback() {
        val args = SourceRecipes.originalDownloadArguments(source.url, "audio.%(ext)s")
        assertEquals("bestaudio", args[args.indexOf("-f") + 1])
        assertFalse("-x" in args); assertFalse("--audio-format" in args)
        assertTrue("--ignore-config" in args && "--no-playlist" in args)
        assertEquals("256M", args[args.indexOf("--max-filesize") + 1])
        assertEquals(source.url, args.last())
        assertFailsWith<IllegalArgumentException> { SourceRecipes.originalDownloadArguments("http://example.invalid", "audio") }
        assertTrue("--audio-format" in SourceRecipes.downloadArguments(source.url, "audio"), "Legacy path is preserved")
    }
}
