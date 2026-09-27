package com.choplab.jvm.ai

import com.choplab.core.ai.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiHttpRequest(val model: String, val key: String, val body: String) {
    override fun toString() = "GeminiHttpRequest([redacted])"
}
class GeminiHttpResponse(val status: Int, val retryAfter: String? = null, val body: String = "") {
    override fun toString() = "GeminiHttpResponse(status=$status)"
}
interface GeminiHttpTransport {
    suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse
    fun close() {}
}

/** JDK/Android standard HTTP; one fixed HTTPS origin, no redirect, log, persistence or automatic retry. */
class UrlConnectionGeminiTransport(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : GeminiHttpTransport {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    override suspend fun post(request: GeminiHttpRequest): GeminiHttpResponse = suspendCancellableCoroutine { answer ->
        check(!closed)
        require(request.model.matches(Regex("gemini-[a-z0-9][a-z0-9._-]{0,79}")))
        val connection = AtomicReference<HttpURLConnection?>(null)
        val work = owner.launch {
            var http: HttpURLConnection? = null
            try {
                ensureActive()
                http = open(URL("https://generativelanguage.googleapis.com/v1beta/models/${request.model}:generateContent"))
                connection.set(http); active.add(http)
                if (!answer.isActive || closed) throw CancellationException()
                http.requestMethod = "POST"
                http.instanceFollowRedirects = false
                http.connectTimeout = 8_000; http.readTimeout = 20_000
                http.doOutput = true; http.useCaches = false
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                http.setRequestProperty("Accept", "application/json")
                http.setRequestProperty("x-goog-api-key", request.key)
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 32_768)
                http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) }
                val status = http.responseCode
                val retry = http.getHeaderField("Retry-After")?.take(128)
                // Error bodies are not needed to classify the failure and can echo private request text.
                val body = if (status == 200) {
                    require(http.contentLengthLong <= GeminiLyricWire.MAX_RESPONSE_BYTES)
                    val out = ByteArrayOutputStream()
                    http.inputStream.use { input ->
                        val buffer = ByteArray(4_096)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= GeminiLyricWire.MAX_RESPONSE_BYTES)
                            out.write(buffer, 0, count)
                        }
                    }
                    try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                    } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid response encoding") }
                } else ""
                if (answer.isActive) answer.resume(GeminiHttpResponse(status, retry, body))
            } catch (failure: Exception) {
                if (answer.isActive) answer.resumeWithException(failure)
            } finally {
                http?.let { active.remove(it); it.disconnect() }
                connection.set(null)
            }
        }
        work.invokeOnCompletion { cause ->
            if (cause != null && answer.isActive) answer.cancel(CancellationException("Request closed", cause))
        }
        answer.invokeOnCancellation { connection.get()?.disconnect(); work.cancel() }
    }
    override fun close() {
        closed = true
        for (connection in active) connection.disconnect()
        owner.cancel()
    }
}

/** The host supplies the session model/key and an explicit consent gate; model names and free tiers are not defaults. */
class GeminiLyricProvider(
    private val transport: GeminiHttpTransport = UrlConnectionGeminiTransport(),
    private val timeoutMillis: Long = 30_000,
    private val now: () -> Instant = Instant::now,
) : LlmProvider {
    @Volatile private var closed = false
    init { require(timeoutMillis in 1..120_000) }
    override suspend fun lyrics(request: LyricRequest, key: SessionApiKey): LyricProviderResult {
        if (closed) return failure(LyricAiProblem.CLOSED, false)
        return try {
            val wire = key.useValue { GeminiHttpRequest(request.model, it, GeminiLyricWire.request(request)) }
            val response = withTimeout(timeoutMillis) { transport.post(wire) }
            if (closed) return failure(LyricAiProblem.CLOSED, true)
            when (response.status) {
                200 -> try { GeminiLyricWire.decode(response.body, request.language) }
                    catch (_: Exception) { failure(LyricAiProblem.INVALID_RESPONSE, true) }
                401, 403 -> failure(LyricAiProblem.AUTHENTICATION, true)
                404 -> failure(LyricAiProblem.UNKNOWN_MODEL, true)
                429 -> LyricProviderResult.Failure(LyricAiFailure(LyricAiProblem.RATE_LIMITED,
                    retrySeconds(response.retryAfter), costUnknown = true))
                else -> failure(LyricAiProblem.PROVIDER_REJECTED, true)
            }
        } catch (_: TimeoutCancellationException) { failure(LyricAiProblem.TIMEOUT, true) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: SocketTimeoutException) { failure(LyricAiProblem.TIMEOUT, true) }
        catch (_: IOException) { failure(if (closed) LyricAiProblem.CLOSED else LyricAiProblem.OFFLINE, true) }
        catch (_: Exception) { failure(LyricAiProblem.INVALID_RESPONSE, true) }
    }
    private fun retrySeconds(value: String?): Long? {
        if (value == null) return null
        val seconds = value.trim().toLongOrNull() ?: runCatching {
            val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val millis = target.toEpochMilli() - now().toEpochMilli()
            if (millis <= 0) 0L else (millis + 999) / 1_000
        }.getOrNull() ?: return null
        return seconds.coerceIn(0, 31_536_000)
    }
    private fun failure(problem: LyricAiProblem, unknown: Boolean) = LyricProviderResult.Failure(LyricAiFailure(problem, costUnknown = unknown))
    override fun close() { closed = true; transport.close() }
}
