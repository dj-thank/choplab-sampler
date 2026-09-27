package com.choplab.sampler.source.newpipe

import com.choplab.sampler.source.OnlineSourceProblem
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/** Carries a typed, non-sensitive reason; never publishes request URLs or provider error bodies. */
class OnlineSourceException(val problem: OnlineSourceProblem) : IOException(problem.name)

internal fun refuse(problem: OnlineSourceProblem): Nothing = throw OnlineSourceException(problem)

internal class NewPipeJob(val id: String, timeoutSeconds: Long = 120) {
    private val cancelled = AtomicBoolean()
    private val connection = AtomicReference<HttpURLConnection?>()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var requests = 0
    fun check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) refuse(OnlineSourceProblem.CANCELLED)
        if (System.nanoTime() > deadline) refuse(OnlineSourceProblem.TIMED_OUT)
    }
    fun attach(value: HttpURLConnection) {
        connection.set(value)
        try { check() } catch (error: Exception) { release(value); throw error }
    }
    fun release(value: HttpURLConnection) { connection.compareAndSet(value, null); value.disconnect() }
    fun cancel() { cancelled.set(true); connection.getAndSet(null)?.disconnect() }
}

internal fun interface NewPipeConnectionFactory { fun open(url: URL): HttpURLConnection }
private enum class NewPipeEndpoint { EXTRACTOR, AUDIO, ARTWORK }

/** Bounded worker I/O shared by Android and desktop; redirects are checked before opening them. */
internal class NewPipeHttp(
    private val connections: NewPipeConnectionFactory = NewPipeConnectionFactory { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val METADATA_LIMIT = 8 * 1024 * 1024
        const val AUDIO_LIMIT = 256L * 1024 * 1024
        const val ARTWORK_LIMIT = 1024 * 1024
        private const val REQUEST_LIMIT = 64
        private const val REDIRECT_LIMIT = 4
        private val ranges = Regex("bytes 0-([0-9]+)/([0-9]+)")

        fun checkedUrl(value: String, audio: Boolean = false): URL = checked(value, if (audio) NewPipeEndpoint.AUDIO else NewPipeEndpoint.EXTRACTOR)
        private fun checked(value: String, endpoint: NewPipeEndpoint): URL {
            val uri = try { URI(value) } catch (_: Exception) { refuse(OnlineSourceProblem.INVALID_INPUT) }
            val host = uri.host?.lowercase() ?: refuse(OnlineSourceProblem.INVALID_INPUT)
            val allowed = when (endpoint) {
                NewPipeEndpoint.AUDIO -> host.endsWith(".googlevideo.com")
                NewPipeEndpoint.ARTWORK -> host.endsWith(".ytimg.com") || host.endsWith(".googleusercontent.com")
                NewPipeEndpoint.EXTRACTOR -> host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtubei.googleapis.com" || host == "www.youtube-nocookie.com"
            }
            if (value.length > 16_384 || uri.scheme != "https" || uri.rawUserInfo != null ||
                uri.port !in setOf(-1, 443) || uri.rawFragment != null || !allowed)
                refuse(OnlineSourceProblem.INVALID_INPUT)
            return uri.toURL()
        }
    }

    fun execute(request: Request, job: NewPipeJob): Response {
        val method = request.httpMethod()
        if (method !in setOf("GET", "HEAD", "POST") || (request.dataToSend()?.size ?: 0) > METADATA_LIMIT)
            refuse(OnlineSourceProblem.INVALID_INPUT)
        return connection(request.url(), method, request.headers(), request.dataToSend(), job, NewPipeEndpoint.EXTRACTOR) { conn, url ->
            val code = conn.responseCode
            status(code)
            if (conn.contentLengthLong > METADATA_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
            val bytes = ByteArrayOutputStream()
            if (method != "HEAD") body(conn).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    job.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > METADATA_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
                    bytes.write(buffer, 0, count)
                }
            }
            job.check()
            Response(code, conn.responseMessage.orEmpty(), conn.headerFields.filterKeys { it != null },
                bytes.toString(Charsets.UTF_8.name()), url.toString())
        }
    }

    fun download(url: String, folder: File, extension: String, expectedBytes: Long?, job: NewPipeJob,
                 progress: (Float) -> Unit): File {
        require(extension in setOf("m4a", "webm", "opus", "ogg", "mp3", "flac", "wav"))
        if (expectedBytes != null && expectedBytes !in 1..AUDIO_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
        val part = File(folder, "audio.part")
        val result = File(folder, "audio.$extension")
        if (!folder.isDirectory || part.exists() || result.exists()) refuse(OnlineSourceProblem.INVALID_INPUT)
        try {
            connection(url, "GET", emptyMap(), null, job, NewPipeEndpoint.AUDIO) { conn, _ ->
                val code = conn.responseCode
                status(code)
                val declared = conn.contentLengthLong.takeIf { it >= 0 }
                if (declared != null && declared !in 1..AUDIO_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
                if (expectedBytes != null && declared != null && expectedBytes != declared)
                    refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                if (code == 206) {
                    val match = ranges.matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                        ?: refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                    val end = match.groupValues[1].toLongOrNull()
                    val total = match.groupValues[2].toLongOrNull()
                    if (total == null || total !in 1..AUDIO_LIMIT || end != total - 1 || declared != total)
                        refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                } else if (code != 200) refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                // Media bytes are preserved exactly. Transfer compression is not an audio container.
                if (conn.getHeaderField("Content-Encoding").orEmpty().let { it.isNotEmpty() && it != "identity" })
                    refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                var total = 0L
                conn.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > AUDIO_LIMIT || declared != null && total > declared ||
                            expectedBytes != null && total > expectedBytes) refuse(OnlineSourceProblem.TOO_LARGE)
                        output.write(buffer, 0, count)
                        val length = expectedBytes ?: declared
                        if (length != null) progress((100.0 * total / length).toFloat().coerceAtMost(99f))
                    }
                    output.fd.sync()
                } }
                job.check()
                if (total == 0L || declared != null && total != declared || expectedBytes != null && total != expectedBytes)
                    refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
            }
            job.check()
            Files.move(part.toPath(), result.toPath(), StandardCopyOption.ATOMIC_MOVE)
            job.check()
            progress(100f)
            job.check()
            return result
        } catch (error: Exception) { result.delete(); throw error }
        finally { part.delete() }
    }

    fun artwork(url: String, job: NewPipeJob): ByteArray =
        connection(url, "GET", emptyMap(), null, job, NewPipeEndpoint.ARTWORK) { conn, _ ->
            status(conn.responseCode)
            if (conn.contentLengthLong > ARTWORK_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
            if (conn.responseCode != 200 ||
                conn.contentType?.substringBefore(';')?.lowercase() !in setOf("image/jpeg", "image/png", "image/webp"))
                refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
            val bytes = ByteArrayOutputStream()
            body(conn).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    job.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > ARTWORK_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
                    bytes.write(buffer, 0, count)
                }
            }
            job.check()
            if (bytes.size() == 0 || conn.contentEncoding.orEmpty().let { it.isEmpty() || it == "identity" } &&
                conn.contentLengthLong >= 0 && conn.contentLengthLong != bytes.size().toLong()) refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
            bytes.toByteArray()
        }

    private fun body(connection: HttpURLConnection): java.io.InputStream = when (connection.contentEncoding?.lowercase()) {
        null, "", "identity" -> connection.inputStream
        "gzip" -> GZIPInputStream(connection.inputStream)
        else -> refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
    }

    private fun status(code: Int) {
        when {
            code == 429 -> refuse(OnlineSourceProblem.RATE_LIMITED)
            code == 401 || code == 403 || code == 451 -> refuse(OnlineSourceProblem.RESTRICTED)
            code !in 200..299 -> refuse(OnlineSourceProblem.UNAVAILABLE)
        }
    }

    private fun <T> connection(value: String, initialMethod: String, initialHeaders: Map<String, List<String>>,
                               initialData: ByteArray?, job: NewPipeJob, endpoint: NewPipeEndpoint,
                               consume: (HttpURLConnection, URL) -> T): T {
        var url = checked(value, endpoint)
        var method = initialMethod
        var headers = initialHeaders
        var data = initialData
        repeat(REDIRECT_LIMIT + 1) { redirect ->
            job.check()
            if (++job.requests > REQUEST_LIMIT) refuse(OnlineSourceProblem.TOO_LARGE)
            val conn = connections.open(url)
            job.attach(conn)
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = if (endpoint == NewPipeEndpoint.ARTWORK) 5_000 else 15_000
                conn.readTimeout = conn.connectTimeout; conn.useCaches = false
                conn.requestMethod = method
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, values) ->
                    if (name.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding"))
                        values.forEach { conn.addRequestProperty(name, it) }
                }
                if (data != null && method == "POST") {
                    conn.doOutput = true; conn.setFixedLengthStreamingMode(data.size)
                    conn.outputStream.use { it.write(data) }
                }
                job.check()
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == REDIRECT_LIMIT) refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                    val location = conn.getHeaderField("Location") ?: refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                    val next = checked(url.toURI().resolve(location).toString(), endpoint)
                    if (next.host != url.host) {
                        if (method == "POST" && code in setOf(307, 308)) refuse(OnlineSourceProblem.RESTRICTED)
                        headers = headers.filterKeys { it.lowercase() !in setOf("authorization", "cookie") }
                    }
                    if (code == 303 || method == "POST" && code in setOf(301, 302)) { method = "GET"; data = null }
                    url = next
                } else return consume(conn, url)
            } finally { job.release(conn) }
        }
        refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
    }
}
