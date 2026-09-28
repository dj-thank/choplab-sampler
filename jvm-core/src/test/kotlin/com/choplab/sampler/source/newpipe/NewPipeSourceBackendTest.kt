package com.choplab.sampler.source.newpipe

import com.choplab.sampler.source.*
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NewPipeSourceBackendTest {
    private val bytes = ByteArray(1024) { (it * 17).toByte() }
    private val format = YoutubeAudioFormat("one", "webm", "opus", 48_000, 2, 128_000,
        false, bytes.size.toLong(), "ja", "Original", "ORIGINAL", false)
    private val source = YoutubeSource("abcdefghijk", "Synthetic source", "Synthetic author", 2.0,
        YoutubeMetadata(formats = listOf(format)))
    private val stream = "https://rr1.googlevideo.com/videoplayback?opaque=temporary"
    private fun resolved(value: YoutubeSource = source) = ResolvedYoutube(value,
        value.metadata!!.formats.map { ResolvedAudio(it, stream) })
    private fun gateway(info: () -> ResolvedYoutube = { resolved() }) = object : NewPipeGateway {
        override fun info(url: String) = info()
        override fun search(query: String, kind: YoutubeSearchKind) = listOf(source)
    }
    private fun failure(problem: OnlineSourceProblem, action: () -> Unit) {
        try { action(); fail("Expected $problem") } catch (error: OnlineSourceException) { assertEquals(problem, error.problem) }
    }

    @Test fun actualExtractorSearchAndStreamMetadataRunThroughTheBoundedHttpPort() {
        val requests = mutableListOf<FakeConnection>()
        val http = NewPipeHttp(NewPipeConnectionFactory { url -> FakeConnection(url) { request ->
            val body = when {
                url.path == "/sw.js" -> "\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"2.20260928.00.00\""
                url.path.endsWith("/get_search_suggestions") -> "{\"contents\":[],\"padding\":\"${"x".repeat(512)}\"}"
                url.path.endsWith("/guide") -> "{\"padding\":\"${"x".repeat(5100)}\"}"
                url.path.endsWith("/search") -> if (url.host == "music.youtube.com") musicResponse else searchResponse
                url.path.endsWith("/visitor_id") -> "{\"responseContext\":{\"visitorData\":\"synthetic\"},\"padding\":\"${"x".repeat(512)}\"}"
                url.path.endsWith("/reel/reel_item_watch") -> "{\"playerResponse\":$playerResponse}"
                url.path.endsWith("/player") -> playerResponse
                url.path.endsWith("/next") -> nextResponse
                else -> error("Unexpected fixture request path: ${url.path}")
            }
            assertFalse(request.posted.toString(Charsets.UTF_8.name()).contains("Spotify"))
            Reply(body.toByteArray(), headers = mapOf("Content-Type" to "application/json"))
        }.also(requests::add) })
        val actual = NewPipeExtractorGateway()
        val fixtureGateway = object : NewPipeGateway {
            override fun search(query: String, kind: YoutubeSearchKind) = try { actual.search(query, kind) }
                catch (error: Exception) { throw AssertionError("Synthetic search contract", error) }
            override fun info(url: String) = try { actual.info(url) }
                catch (error: Exception) { throw AssertionError("Synthetic detail contract", error) }
        }
        NewPipeSourceBackend(http, fixtureGateway).use { backend ->
            val candidates = backend.search("synthetic", "search")
            assertEquals(listOf("abcdefghijk"), candidates.map { it.id })
            assertEquals(2.0, candidates.single().durationSeconds, .0)
            assertTrue(candidates.single().metadata!!.formats.isEmpty())
            val details = backend.info(source.url, "details")
            assertEquals("Synthetic source", details.title)
            val selected = details.metadata!!.formats.single()
            assertEquals("m4a", selected.container)
            assertEquals("mp4a.40.2", selected.codec)
            assertEquals(44_100, selected.sampleRate)
            assertEquals(2, selected.channels)
            assertEquals(128_000, selected.bitrate)
            assertFalse(selected.approximateBitrate)
            assertNull(details.metadata!!.album)
            assertFalse(details.toString().contains("googlevideo"))
            assertNull(details.selectedFormat)
            val music = backend.search("synthetic 日本語", "music", YoutubeSearchKind.MUSIC)
            assertEquals("abcdefghijk", music.single().id)
            assertEquals("Synthetic artist", music.single().author)
            assertNull(music.single().metadata!!.uploaderVerified)
            assertTrue(requests.any { it.url.host == "music.youtube.com" && it.posted.toString(Charsets.UTF_8.name()).contains("WEB_REMIX") })
        }
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.disconnected })
    }

    @Test fun originalBytesNeedExplicitFormatAndAreNotTranscodedOrReplacedByFallback() {
        val root = Files.createTempDirectory("newpipe-original-").toFile()
        var requests = 0
        val http = NewPipeHttp(NewPipeConnectionFactory { url -> requests++; FakeConnection(url) { Reply(bytes) } })
        try {
            NewPipeSourceBackend(http, gateway()).use { backend ->
                val details = backend.info(source.url, "info")
                failure(OnlineSourceProblem.UNSUPPORTED_FORMAT) { backend.download(details, root, "unset") {} }
                assertEquals(0, requests)
                val progress = mutableListOf<Float>()
                val result = backend.download(details.copy(selectedFormat = format.id), root, "chosen", progress::add)
                assertEquals("webm", result.extension)
                assertArrayEquals(bytes, result.readBytes())
                assertEquals(100f, progress.last())
                assertFalse(root.resolve("audio.part").exists())
                val library = LocalAudioLibrary(root.resolve("library")) { assertArrayEquals(bytes, it.readBytes()) }
                val item = library.importFile(result, details.title, details.url)
                assertArrayEquals(bytes, library.resolve(item.id).readBytes())
                assertEquals(details.title, library.list().single().title)
                assertEquals(source.url, library.list().single().origin)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedOrMissingFormatNeedsNewConfirmationBeforeAnyMediaRequest() {
        val root = Files.createTempDirectory("newpipe-changed-").toFile()
        val http = NewPipeHttp(NewPipeConnectionFactory { error("No media request is allowed") })
        try {
            listOf(source.copy(metadata = YoutubeMetadata(formats = listOf(format.copy(bitrate = 96_000)))),
                source.copy(metadata = YoutubeMetadata(formats = listOf(format.copy(id = "other")))),
                source.copy(durationSeconds = 3.0)).forEach { changed ->
                NewPipeSourceBackend(http, gateway { resolved(changed) }).use { backend ->
                    failure(OnlineSourceProblem.FORMAT_CHANGED) { backend.download(source.copy(selectedFormat = format.id), root, "changed") {} }
                    assertEquals(emptyList<String>(), root.list()!!.toList())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun closeAtCompletionDoesNotPublishOrLeaveTheDownloadedOriginal() {
        val root = Files.createTempDirectory("newpipe-late-close-").toFile()
        val http = NewPipeHttp(NewPipeConnectionFactory { url -> FakeConnection(url) { Reply(bytes) } })
        try {
            NewPipeSourceBackend(http, gateway()).use { backend ->
                failure(OnlineSourceProblem.CANCELLED) {
                    backend.download(source.copy(selectedFormat = format.id), root, "close") { if (it == 100f) backend.close() }
                }
                assertEquals(emptyList<String>(), root.list()!!.toList())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationInterruptsOwnedResponseThenRetryUsesANewJobAndCloseIsIdempotent() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = Files.createTempDirectory("newpipe-cancel-").toFile()
        val pool = Executors.newSingleThreadExecutor()
        var first = true
        val http = NewPipeHttp(NewPipeConnectionFactory { url -> FakeConnection(url, onDisconnect = { release.countDown() }) {
            if (!first) Reply(bytes) else {
                first = false
                Reply(bytes, input = object : InputStream() {
                    override fun read(): Int { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return -1 }
                })
            }
        } })
        val backend = NewPipeSourceBackend(http, gateway())
        try {
            val old = pool.submit<OnlineSourceProblem> {
                try { backend.download(source.copy(selectedFormat = format.id), root, "old") {}; error("Must cancel") }
                catch (error: OnlineSourceException) { error.problem }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            failure(OnlineSourceProblem.BUSY) { backend.info(source.url, "overlap") }
            backend.cancel("old")
            assertEquals(OnlineSourceProblem.CANCELLED, old.get(5, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), root.list()!!.toList())
            failure(OnlineSourceProblem.CANCELLED) { backend.info(source.url, "old") }
            assertArrayEquals(bytes, backend.download(source.copy(selectedFormat = format.id), root, "new") {}.readBytes())
            backend.close(); backend.close()
            failure(OnlineSourceProblem.CLOSED) { backend.info(source.url, "closed") }
        } finally { release.countDown(); backend.close(); pool.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun transportRejectsUnsafeRedirectRateLimitsOversizeAndShortOrPartialAudio() {
        val root = Files.createTempDirectory("newpipe-failure-").toFile()
        val scenarios = listOf(
            Reply(bytes, code = 302, headers = mapOf("Location" to "https://example.invalid/audio")) to OnlineSourceProblem.INVALID_INPUT,
            Reply(bytes, code = 429) to OnlineSourceProblem.RATE_LIMITED,
            Reply(bytes, declared = NewPipeHttp.AUDIO_LIMIT + 1) to OnlineSourceProblem.TOO_LARGE,
            Reply(bytes.copyOf(100), declared = bytes.size.toLong()) to OnlineSourceProblem.MALFORMED_RESPONSE,
            Reply(bytes, code = 206, headers = mapOf("Content-Range" to "bytes 0-1023/2048")) to OnlineSourceProblem.MALFORMED_RESPONSE,
        )
        try {
            scenarios.forEachIndexed { index, (reply, problem) ->
                var opened = 0
                val http = NewPipeHttp(NewPipeConnectionFactory { url -> opened++; FakeConnection(url) { reply } })
                failure(problem) { http.download(stream, root, "webm", null, NewPipeJob("$index")) {} }
                assertEquals(1, opened)
                assertEquals(emptyList<String>(), root.list()!!.toList())
            }
        } finally { root.deleteRecursively() }
        listOf("http://rr1.googlevideo.com/a", "https://user@rr1.googlevideo.com/a", "https://rr1.googlevideo.com:444/a",
            "https://rr1.googlevideo.com.evil.invalid/a", "https://127.0.0.1/a").forEach {
            failure(OnlineSourceProblem.INVALID_INPUT) { NewPipeHttp.checkedUrl(it, true) }
        }
        val bounded = NewPipeHttp(NewPipeConnectionFactory { url -> FakeConnection(url) { Reply(ByteArray(0), declared = NewPipeHttp.METADATA_LIMIT + 1L) } })
        failure(OnlineSourceProblem.TOO_LARGE) { bounded.execute(Request.newBuilder().get(source.url).build(), NewPipeJob("large")) }
    }

    @Test fun unknownCodecPropertiesStayUnknownAndSegmentedFormatsAreNotTreatedAsFiles() {
        val gateway = NewPipeExtractorGateway()
        val raw = AudioStream.Builder().setId("251").setContent(stream, true).setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setAverageBitrate(-1).build()
        val mapped = gateway.audio(raw)!!.format
        assertNull(mapped.codec); assertNull(mapped.sampleRate); assertNull(mapped.channels); assertNull(mapped.bytes)
        assertNull(mapped.bitrate); assertFalse(mapped.approximateBitrate)
        val dash = AudioStream.Builder().setId("251").setContent(stream, true).setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setDeliveryMethod(DeliveryMethod.DASH).setAverageBitrate(160).build()
        assertNull(gateway.audio(dash))
        val itag = ItagItem(140, ItagItem.ItagType.AUDIO, MediaFormat.M4A, 128).apply {
            sampleRate = 44_100; audioChannels = 2; bitrate = 125_000; codec = "mp4a.40.2"; contentLength = 1024
        }
        val described = AudioStream.Builder().setId("140").setContent(stream, true).setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(128).setItagItem(itag).build()
        assertEquals(125_000, gateway.audio(described)!!.format.bitrate)
    }

    private data class Reply(val bytes: ByteArray, val code: Int = 200, val headers: Map<String, String> = emptyMap(),
                             val declared: Long = bytes.size.toLong(), val input: InputStream? = null)
    private class FakeConnection(url: URL, private val onDisconnect: () -> Unit = {},
                                 private val respond: (FakeConnection) -> Reply) : HttpURLConnection(url) {
        val posted = ByteArrayOutputStream()
        var disconnected = false
        private val reply by lazy { respond(this) }
        override fun getOutputStream() = posted
        override fun getResponseCode() = reply.code
        override fun getResponseMessage() = "Fixture"
        override fun getInputStream(): InputStream = reply.input ?: ByteArrayInputStream(reply.bytes)
        override fun getContentLengthLong() = reply.declared
        override fun getHeaderField(name: String?): String? = reply.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getHeaderFields(): Map<String, List<String>> = reply.headers.mapValues { listOf(it.value) }
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; onDisconnect() }
    }

    private val musicResponse = """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Synthetic song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Synthetic artist"},{"text":" \u2022 "},{"text":"Synthetic album"},{"text":" \u2022 "},{"text":"0:02"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/abcdefghijk/default.jpg","width":120,"height":90}]}}}}}]}}]}}}}]}}}"""
    private val searchResponse = """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Synthetic source"},"longBylineText":{"runs":[{"text":"Synthetic author","navigationEndpoint":{"browseEndpoint":{"browseId":"UCabcdefghijklmnopqrstuv"}}}]},"lengthText":{"simpleText":"0:02"},"viewCountText":{"simpleText":"1 view"},"publishedTimeText":{"simpleText":"1 day ago"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/abcdefghijk/default.jpg","width":120,"height":90}]}}}]}}]}}}}}"""
    private val playerResponse = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","title":"Synthetic source","author":"Synthetic author","channelId":"UCabcdefghijklmnopqrstuv","lengthSeconds":"2","isLiveContent":false,"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/abcdefghijk/default.jpg","width":120,"height":90}]}},"microformat":{"playerMicroformatRenderer":{}},"streamingData":{"adaptiveFormats":[{"itag":140,"url":"https://rr1.googlevideo.com/videoplayback?opaque=synthetic","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,"audioSampleRate":"44100","audioChannels":2,"contentLength":"1024","approxDurationMs":"2000","lastModified":"1"}]}}"""
    private val nextResponse = """{"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[{"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{}},"metadataRowContainer":{"metadataRowContainerRenderer":{"rows":[]}}}}]}}}}}"""
}
