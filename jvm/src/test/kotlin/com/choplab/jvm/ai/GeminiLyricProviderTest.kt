package com.choplab.jvm.ai

import com.choplab.core.ai.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** No sockets or real credentials. Production transport builds the request and parses the reply over a fake connection. */
class GeminiLyricProviderTest {
    @Test fun productionHttpRequestUsesFixedOriginHeaderAndCurrentStructuredOutput() = runBlocking<Unit> {
        lateinit var connection: Connection
        val provider = GeminiLyricProvider(UrlConnectionGeminiTransport { url -> Connection(url, reply()).also { connection = it } })
        val key = SessionApiKey("fake-session-key")
        try {
            val result = assertIs<LyricProviderResult.Success>(provider.lyrics(request(key), key))
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent", connection.url.toString())
            assertNull(connection.url.query)
            assertEquals("POST", connection.requestMethod)
            assertFalse(connection.instanceFollowRedirects)
            assertFalse(connection.useCaches)
            assertEquals("fake-session-key", connection.getRequestProperty("x-goog-api-key"))
            val sent = Json.parseToJsonElement(connection.sent.toString("UTF-8")).jsonObject
            val config = sent.getValue("generationConfig").jsonObject
            assertEquals(64, config.getValue("maxOutputTokens").jsonPrimitive.int)
            assertFalse(connection.sent.toString("UTF-8").contains("nanoUnits"))
            val format = config.getValue("responseFormat").jsonObject.getValue("text").jsonObject
            assertEquals("APPLICATION_JSON", format.getValue("mimeType").jsonPrimitive.content)
            assertEquals(false, format.getValue("schema").jsonObject.getValue("additionalProperties").jsonPrimitive.boolean)
            assertFalse(config.containsKey("responseSchema"))
            val fields = Json.parseToJsonElement(sent["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
            assertEquals(setOf("theme", "mood", "language", "style", "structure", "rhyme", "keepLines"), fields.keys)
            assertEquals("private-theme", fields["theme"]!!.jsonPrimitive.content)
            assertFalse(connection.sent.toString("UTF-8").contains("fake-session-key"))
            assertEquals(3, result.proposal.sections.single().lines.single().mora, "Provider count 99 is not trusted")
            assertEquals("aqu", result.proposal.sections.single().lines.single().rhymeVowels)
            assertEquals(LyricUsage(21, 35, 56), result.usage)
            assertEquals("gemini-test-revision", result.modelVersion)
            assertTrue(connection.disconnected)
        } finally { provider.close(); key.close() }
    }

    @Test fun schemaFailuresCannotBecomeProposalsAndMissingUsageIsUnknown() = runBlocking<Unit> {
        val invalid = listOf(
            reply(finish = "MAX_TOKENS"), reply(candidateCount = 2), reply(blocked = true),
            reply(lyric = lyric().replace("\"ja\"", "\"en\"")),
            reply(lyric = lyric().replace("\"mora\":99", "\"mora\":\"99\"")),
            reply(lyric = lyric().replace("きゃっぷ", "歌う")),
            reply(lyric = lyric().replace("\"title\":\"Test\"", "\"title\":\"Test\",\"extra\":1")),
            reply(lyric = lyric().replace("\"bars\":4", "\"bars\":65")),
            reply(lyric = lyric(lineCount = 17)), reply(lyric = lyric(sectionCount = 5, lineCount = 16)),
            reply(lyric = "```json\n${lyric()}\n```"), reply(lyric = "{}"), "not-json",
        )
        for (body in invalid) {
            val result = execute(GeminiHttpResponse(200, body = body))
            assertEquals(LyricAiProblem.INVALID_RESPONSE, assertIs<LyricProviderResult.Failure>(result).failure.problem)
        }
        val unknown = assertIs<LyricProviderResult.Success>(execute(GeminiHttpResponse(200, body = reply(usage = false))))
        assertNull(unknown.usage)
    }

    @Test fun transportCompletesOnlyAfterConnectionCleanupForSuccessAndFailure() = runBlocking<Unit> {
        for (fails in listOf(false, true)) {
            val disconnecting = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            lateinit var connection: Connection
            val transport = UrlConnectionGeminiTransport { url -> object : Connection(url, reply()) {
                override fun getInputStream(): InputStream = if (fails) throw IOException("Synthetic read failure") else super.getInputStream()
                override fun disconnect() {
                    disconnecting.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS)) { "Cleanup was not released" }
                    super.disconnect()
                }
            }.also { connection = it } }
            // Run the resumed caller inline: returning before finally cannot hide behind dispatcher timing.
            val pending = async(Dispatchers.Unconfined) { runCatching { transport.post(GeminiHttpRequest("gemini-test", "fake-key", "{}")) } }
            try {
                withTimeout(2_000) { disconnecting.await() }
                assertFalse(pending.isCompleted, "The request must still own cleanup before completion (fails=$fails)")
                release.countDown()
                val result = withTimeout(2_000) { pending.await() }
                assertEquals(fails, result.isFailure)
                assertTrue(connection.disconnected)
            } finally { release.countDown(); pending.cancelAndJoin(); transport.close() }
        }
    }

    @Test fun failuresAreTypedRetryAfterIsHonoredAndNothingRetriesAutomatically() = runBlocking<Unit> {
        val now = Instant.parse("2026-09-27T12:00:00Z")
        val cases = listOf(401 to LyricAiProblem.AUTHENTICATION, 403 to LyricAiProblem.AUTHENTICATION,
            404 to LyricAiProblem.UNKNOWN_MODEL, 500 to LyricAiProblem.PROVIDER_REJECTED, 302 to LyricAiProblem.PROVIDER_REJECTED)
        for ((status, expected) in cases) assertEquals(expected, assertIs<LyricProviderResult.Failure>(execute(GeminiHttpResponse(status))).failure.problem)
        for ((header, seconds) in listOf("7" to 7L, "Sun, 27 Sep 2026 12:00:03 GMT" to 3L, "-2" to 0L, "garbage" to null)) {
            var calls = 0
            val provider = GeminiLyricProvider(object : GeminiHttpTransport {
                override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse { calls++; return GeminiHttpResponse(429, header, "private error body") }
            }, now = { now })
            val key = SessionApiKey("fake-key")
            try {
                val failure = assertIs<LyricProviderResult.Failure>(provider.lyrics(request(key), key)).failure
                assertEquals(LyricAiProblem.RATE_LIMITED, failure.problem); assertEquals(seconds, failure.retryAfterSeconds)
                assertTrue(failure.costUnknown); assertEquals(1, calls)
                assertFalse(failure.toString().contains("private"))
            } finally { provider.close(); key.close() }
        }
        val offline = GeminiLyricProvider(object : GeminiHttpTransport {
            override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse = throw IOException("private exception text")
        })
        val key = SessionApiKey("fake-key")
        try {
            val failure = assertIs<LyricProviderResult.Failure>(offline.lyrics(request(key), key)).failure
            assertEquals(LyricAiProblem.OFFLINE, failure.problem)
            assertFalse(failure.toString().contains("private exception text"))
            offline.close()
            assertEquals(LyricAiProblem.CLOSED, assertIs<LyricProviderResult.Failure>(offline.lyrics(request(key), key)).failure.problem)
        } finally { offline.close(); key.close() }
    }

    @Test fun cancellationTimeoutAndCloseDisconnectAnActiveBlockingConnection() = runBlocking<Unit> {
        for (mode in listOf("cancel", "timeout", "close")) {
            lateinit var connection: BlockingConnection
            val opened = CompletableDeferred<Unit>()
            val provider = GeminiLyricProvider(UrlConnectionGeminiTransport { url ->
                BlockingConnection(url).also { connection = it; opened.complete(Unit) }
            }, timeoutMillis = if (mode == "timeout") 300 else 5_000)
            val key = SessionApiKey("fake-key")
            val pending = async { provider.lyrics(request(key), key) }
            try {
                withTimeout(2_000) { opened.await(); while (connection.readStarted.count != 0L) delay(1) }
                when (mode) {
                    "cancel" -> { pending.cancelAndJoin(); assertTrue(pending.isCancelled) }
                    "close" -> {
                        provider.close()
                        try { assertEquals(LyricAiProblem.CLOSED, assertIs<LyricProviderResult.Failure>(pending.await()).failure.problem) }
                        catch (_: CancellationException) { /* The transport owner may cancel before disconnect returns. */ }
                    }
                    else -> assertEquals(LyricAiProblem.TIMEOUT, assertIs<LyricProviderResult.Failure>(withTimeout(2_000) { pending.await() }).failure.problem)
                }
                withTimeout(2_000) { while (!connection.disconnected) delay(1) }
                assertTrue(connection.disconnected, "The blocking read is released by disconnect for $mode")
            } finally { provider.close(); pending.cancel(); key.close() }
        }
    }

    @Test fun responseSizeInvalidUtf8AndPrivateErrorBodiesAreBoundedAtTransport() = runBlocking<Unit> {
        for (bytes in listOf(ByteArray(GeminiLyricWire.MAX_RESPONSE_BYTES + 1) { 32 }, byteArrayOf(0xc3.toByte(), 0x28))) {
            val provider = GeminiLyricProvider(UrlConnectionGeminiTransport { url -> Connection(url, bytes) })
            val key = SessionApiKey("fake-key")
            try { assertEquals(LyricAiProblem.INVALID_RESPONSE, assertIs<LyricProviderResult.Failure>(provider.lyrics(request(key), key)).failure.problem) }
            finally { provider.close(); key.close() }
        }
        var reads = 0
        val transport = UrlConnectionGeminiTransport { url -> object : Connection(url, "private response".toByteArray(), 403) {
            override fun getInputStream(): InputStream { reads++; error("Must not read a provider error body") }
            override fun getErrorStream(): InputStream { reads++; error("Must not read a provider error body") }
        } }
        try {
            val response = transport.post(GeminiHttpRequest("gemini-test", "fake-key", "{}"))
            assertEquals("", response.body); assertEquals(0, reads)
            assertFalse(GeminiHttpRequest("gemini-test", "fake-key", "private request").toString().contains("private request"))
        } finally { transport.close() }
    }

    @Test fun changedCredentialDuringFinalAdmissionAndDuplicateAttemptMakeNoPost() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        var hold = false
        val session = GoogleLyricSession {
            if (hold) { reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            100L
        }
        val dialog = session.openDialog()
        val key = SessionApiKey("private-fake-key")
        val changed = SessionApiKey("private-fake-key") // Equal text is still a new credential input.
        val input = LyricRequest("gemini-test", "private-theme", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", "")
        dialog.bindInputs(input.model, key)
        assertNull(session.install(requireNotNull(session.pendingReview()), reviewed()))
        val admitted = input.forAttempt(assertIs<GoogleAttemptDecision.Allowed>(dialog.reserve(input, key, session.state.value.version)).attempt)
        var calls = 0
        val provider = GeminiLyricProvider(object : GeminiHttpTransport {
            override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse { calls++; return GeminiHttpResponse(200, body = reply()) }
        })
        hold = true
        val pending = async(Dispatchers.Default) { provider.lyrics(admitted, key) }
        try {
            withContext(Dispatchers.IO) { assertTrue(reached.await(5, TimeUnit.SECONDS)) }
            dialog.bindInputs(input.model, changed)
            release.countDown()
            val refused = assertIs<LyricProviderResult.Failure>(pending.await()).failure
            assertEquals(GoogleAdmissionProblem.INPUT_CHANGED, refused.admissionProblem)
            assertFalse(refused.costUnknown)
            assertEquals(0, calls)
            assertEquals(GoogleAdmissionProblem.ATTEMPT_USED,
                assertIs<LyricProviderResult.Failure>(provider.lyrics(admitted, key)).failure.admissionProblem)
            assertEquals(LyricAiProblem.PROVIDER_UNVERIFIED,
                assertIs<LyricProviderResult.Failure>(provider.lyrics(input, changed)).failure.problem)
            assertEquals(0, calls)
        } finally { release.countDown(); pending.cancelAndJoin(); provider.close(); key.close(); changed.close(); session.close() }
    }

    @Test fun failureTimeoutAndCancellationNeverRestoreTheSamePermit() = runBlocking<Unit> {
        for (mode in listOf("failure", "timeout", "cancel")) {
            var calls = 0
            val entered = CompletableDeferred<Unit>()
            val provider = GeminiLyricProvider(object : GeminiHttpTransport {
                override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse {
                    calls++; entered.complete(Unit)
                    if (mode != "failure") awaitCancellation()
                    return GeminiHttpResponse(429, "0")
                }
            }, timeoutMillis = 300)
            val key = SessionApiKey("fake-key")
            val admitted = request(key)
            val pending = async { provider.lyrics(admitted, key) }
            try {
                withTimeout(2_000) { entered.await() }
                if (mode == "cancel") pending.cancelAndJoin() else assertIs<LyricProviderResult.Failure>(pending.await())
                assertEquals(GoogleAdmissionProblem.ATTEMPT_USED,
                    assertIs<LyricProviderResult.Failure>(provider.lyrics(admitted, key)).failure.admissionProblem)
                assertEquals(1, calls, mode)
            } finally { pending.cancelAndJoin(); provider.close(); key.close() }
        }
    }

    private suspend fun execute(response: GeminiHttpResponse): LyricProviderResult {
        val provider = GeminiLyricProvider(object : GeminiHttpTransport { override suspend fun post(request: GeminiHttpRequest) = response })
        val key = SessionApiKey("fake-key")
        return try { provider.lyrics(request(key), key) } finally { provider.close(); key.close() }
    }
    private fun request(key: SessionApiKey): LyricRequest {
        val input = LyricRequest("gemini-test", "private-theme", "", LyricLanguage.JAPANESE, LyricStyle.RAP, "", "", "")
        val session = GoogleLyricSession { 100L }
        val dialog = session.openDialog()
        dialog.bindInputs(input.model, key)
        assertNull(session.install(requireNotNull(session.pendingReview()), reviewed()))
        return input.forAttempt(assertIs<GoogleAttemptDecision.Allowed>(dialog.reserve(input, key, session.state.value.version)).attempt)
    }
    private fun reviewed() = ReviewedGoogleUse("gemini-test", GoogleAccountTier.PAID,
        GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION,
        GoogleTokenPrice("gemini-test", GoogleAccountTier.PAID, "USD", 1_000, 100, 200, 10_000, 0, 1_000),
        GoogleTokenBounds(4096, 128, 64), GoogleMoney("USD", 2_000), 0, 1_000)
    private fun lyric(sectionCount: Int = 1, lineCount: Int = 1): String {
        val line = """{"text":"キャップ","reading":"きゃっぷ","mora":99,"rhymeVowels":"wrong"}"""
        val section = """{"name":"A","kind":"verse","bars":4,"lines":[${List(lineCount) { line }.joinToString()}]}"""
        return """{"title":"Test","language":"ja","sections":[${List(sectionCount) { section }.joinToString()}]}"""
    }
    private fun reply(lyric: String = lyric(), finish: String = "STOP", candidateCount: Int = 1, blocked: Boolean = false, usage: Boolean = true): String = buildJsonObject {
        if (blocked) put("promptFeedback", buildJsonObject { put("blockReason", "SAFETY") })
        put("candidates", buildJsonArray { repeat(candidateCount) { add(buildJsonObject {
            put("finishReason", finish)
            put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", lyric) }) }) })
        }) } })
        if (usage) put("usageMetadata", buildJsonObject { put("promptTokenCount", 21); put("candidatesTokenCount", 35); put("totalTokenCount", 56) })
        put("modelVersion", "gemini-test-revision")
    }.toString()

    private open class Connection(url: URL, val bytes: ByteArray, private val status: Int = 200) : HttpURLConnection(url) {
        constructor(url: URL, body: String) : this(url, body.toByteArray())
        val sent = ByteArrayOutputStream()
        @Volatile var disconnected = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getResponseCode() = status
        override fun getContentLengthLong() = -1L // exercise streaming limit, not only Content-Length
        override fun getHeaderField(name: String?): String? = null
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
    private class BlockingConnection(url: URL) : Connection(url, byteArrayOf()) {
        val readStarted = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                check(released.await(4, TimeUnit.SECONDS)) { "Connection was not disconnected" }
                throw IOException("Connection closed")
            }
        }
        override fun disconnect() { super.disconnect(); released.countDown() }
    }
}
