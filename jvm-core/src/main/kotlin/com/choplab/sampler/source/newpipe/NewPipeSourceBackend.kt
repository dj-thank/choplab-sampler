package com.choplab.sampler.source.newpipe

import com.choplab.sampler.source.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

interface DetailedYoutubeBackend : YoutubeSourceBackend {
    fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource>
}

/** No accounts, cookies from users, alternative providers, subprocesses, or transcoding. */
class NewPipeSourceBackend internal constructor(
    private val http: NewPipeHttp,
    private val gateway: NewPipeGateway,
) : DetailedYoutubeBackend, AutoCloseable {
    constructor() : this(NewPipeHttp(), NewPipeExtractorGateway())
    private var active: NewPipeJob? = null
    private var closed = false
    private val cancelled = ArrayDeque<String>()

    override fun search(query: String, jobId: String): List<YoutubeSource> = search(query, jobId, YoutubeSearchKind.VIDEOS)
    override fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource> = job(jobId) { job ->
        if (query.isBlank() || query.length > 240 || query.any { it.code < 32 }) refuse(OnlineSourceProblem.INVALID_INPUT)
        NewPipeRuntime.run(http, job) { gateway.search(query, kind) }.also { job.check() }
    }

    override fun info(url: String, jobId: String): YoutubeSource = job(jobId) { job ->
        resolve(url, job).source
    }

    override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File =
        job(jobId, 720, discard = { it.delete() }) { job ->
            val selected = source.metadata?.formats?.singleOrNull { it.id == source.selectedFormat }
                ?: refuse(OnlineSourceProblem.UNSUPPORTED_FORMAT)
            // Signed URLs expire and stay private to this worker. Recheck the user's exact format;
            // a missing or changed stream requires another explicit confirmation, never a fallback.
            val fresh = resolve(source.url, job)
            val stream = fresh.streams.singleOrNull { it.format.id == selected.id }
                ?: refuse(OnlineSourceProblem.FORMAT_CHANGED)
            if (fresh.source.id != source.id || fresh.source.durationSeconds != source.durationSeconds || stream.format != selected)
                refuse(OnlineSourceProblem.FORMAT_CHANGED)
            job.check()
            http.download(stream.url, folder, selected.container, selected.bytes, job, progress)
        }

    private fun resolve(url: String, job: NewPipeJob): ResolvedYoutube {
        val canonical = try { SourceRecipes.youtubeUrl(url) } catch (_: IllegalArgumentException) {
            refuse(OnlineSourceProblem.INVALID_INPUT)
        }
        return NewPipeRuntime.run(http, job) { gateway.info(canonical) }.also { resolved ->
            job.check()
            if (resolved.source.url != canonical || resolved.source.durationSeconds !in 0.01..600.0 ||
                resolved.source.title.isBlank()) refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
            if (resolved.streams.isEmpty()) refuse(OnlineSourceProblem.UNSUPPORTED_FORMAT)
        }
    }

    private fun <T> job(id: String, seconds: Long = 120, discard: (T) -> Unit = {}, action: (NewPipeJob) -> T): T {
        val job = synchronized(this) {
            if (closed) refuse(OnlineSourceProblem.CLOSED)
            if (active != null) refuse(OnlineSourceProblem.BUSY)
            if (id.isBlank() || id.length > 128) refuse(OnlineSourceProblem.INVALID_INPUT)
            if (id in cancelled) refuse(OnlineSourceProblem.CANCELLED)
            NewPipeJob(id, seconds).also { active = it }
        }
        try {
            job.check()
            val result = action(job)
            try { job.check() } catch (error: Exception) { discard(result); throw error }
            return result
        } catch (error: Exception) {
            job.check()
            when (error) {
                is OnlineSourceException -> throw error
                is InterruptedException -> refuse(OnlineSourceProblem.CANCELLED)
                is SocketTimeoutException -> refuse(OnlineSourceProblem.TIMED_OUT)
                is ReCaptchaException -> refuse(OnlineSourceProblem.RESTRICTED)
                is ContentNotAvailableException -> refuse(OnlineSourceProblem.UNAVAILABLE)
                is ExtractionException -> refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
                is IOException -> refuse(OnlineSourceProblem.NETWORK)
                else -> refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
            }
        } finally { synchronized(this) { if (active === job) active = null } }
    }

    @Synchronized override fun cancel(jobId: String) {
        if (jobId !in cancelled) { cancelled.addLast(jobId); if (cancelled.size > 64) cancelled.removeFirst() }
        active?.takeIf { it.id == jobId }?.cancel()
    }
    @Synchronized override fun close() { if (!closed) { closed = true; active?.cancel() } }
}

internal data class ResolvedYoutube(val source: YoutubeSource, val streams: List<ResolvedAudio>)
internal data class ResolvedAudio(val format: YoutubeAudioFormat, val url: String)
internal interface NewPipeGateway {
    fun search(query: String, kind: YoutubeSearchKind): List<YoutubeSource>
    fun info(url: String): ResolvedYoutube
}

/** NewPipe has process-wide settings and caches. Extraction is serialized off the audio thread. */
private object NewPipeRuntime {
    private data class Owner(val http: NewPipeHttp, val job: NewPipeJob)
    private val owner = ThreadLocal<Owner>()
    private val lock = ReentrantLock()
    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val current = owner.get() ?: refuse(OnlineSourceProblem.CLOSED)
                return current.http.execute(request, current.job)
            }
        }, Localization("en", "US"), ContentCountry("US"))
    }
    fun <T> run(http: NewPipeHttp, job: NewPipeJob, action: () -> T): T {
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) job.check()
        try { job.check(); owner.set(Owner(http, job)); return action().also { job.check() } }
        finally { owner.remove(); lock.unlock() }
    }
}

internal class NewPipeExtractorGateway : NewPipeGateway {
    override fun search(query: String, kind: YoutubeSearchKind): List<YoutubeSource> {
        val filter = when (kind) {
            YoutubeSearchKind.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
            YoutubeSearchKind.MUSIC -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        }
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        val page = extractor.initialPage
        if (page.items.isEmpty() && page.errors.isNotEmpty()) refuse(OnlineSourceProblem.MALFORMED_RESPONSE)
        return page.items.filterIsInstance<StreamInfoItem>().asSequence().filter {
            it.streamType in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM) &&
                it.contentAvailability in setOf(ContentAvailability.AVAILABLE, ContentAvailability.UNKNOWN) && it.duration in 1..600
        }.mapNotNull { item ->
            val url = runCatching { SourceRecipes.youtubeUrl(item.url) }.getOrNull() ?: return@mapNotNull null
            val title = clean(item.name, 240) ?: return@mapNotNull null
            YoutubeSource(url.substringAfter("v="), title, clean(item.uploaderName, 160).orEmpty(), item.duration.toDouble(),
                YoutubeMetadata(thumbnailUrl = thumbnail(item.thumbnails.map { it.url }),
                    uploaderVerified = item.isUploaderVerified.takeIf { kind == YoutubeSearchKind.VIDEOS }))
        }.distinctBy { it.id }.take(5).toList()
    }

    override fun info(url: String): ResolvedYoutube {
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        if (extractor.ageLimit != 0 || extractor.streamType !in setOf(StreamType.VIDEO_STREAM, StreamType.AUDIO_STREAM))
            refuse(OnlineSourceProblem.RESTRICTED)
        val duration = extractor.length.toDouble()
        if (duration !in 0.01..600.0) refuse(OnlineSourceProblem.INVALID_INPUT)
        val audio = extractor.audioStreams.mapNotNull(::audio).distinctBy { it.format.id }.take(32)
        val source = YoutubeSource(extractor.id, clean(extractor.name, 240) ?: refuse(OnlineSourceProblem.MALFORMED_RESPONSE),
            clean(extractor.uploaderName, 160).orEmpty(), duration,
            YoutubeMetadata(thumbnail(extractor.thumbnails.map { it.url }),
                uploaderVerified = extractor.isUploaderVerified, formats = audio.map { it.format }))
        return ResolvedYoutube(source, audio)
    }

    internal fun audio(stream: AudioStream): ResolvedAudio? {
        if (!stream.isUrl || stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val extension = stream.format?.suffix?.lowercase()?.takeIf {
            it in setOf("m4a", "webm", "opus", "ogg", "mp3", "flac", "wav")
        } ?: return null
        val url = runCatching { NewPipeHttp.checkedUrl(stream.content, true).toString() }.getOrNull() ?: return null
        val itag = stream.itagItem
        val identity = listOf(stream.id, stream.audioTrackId.orEmpty(), stream.audioLocale?.toLanguageTag().orEmpty(),
            itag?.isDrc().toString()).joinToString("\n")
        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val bitrate = stream.bitrate.takeIf { it > 0 }
        val average = stream.averageBitrate.takeIf { it in 1..10_000 }?.times(1000)
        val format = YoutubeAudioFormat(id, extension, clean(stream.codec, 80),
            itag?.sampleRate?.takeIf { it in 1..768_000 }, itag?.audioChannels?.takeIf { it in 1..32 },
            bitrate ?: average, bitrate == null && average != null, itag?.contentLength?.takeIf { it > 0 },
            clean(stream.audioLocale?.toLanguageTag(), 40), clean(stream.audioTrackName, 160),
            stream.audioTrackType?.name, itag?.isDrc())
        return ResolvedAudio(format, url)
    }

    private fun clean(value: String?, limit: Int): String? = value?.filter { it.code >= 32 }?.take(limit)?.takeIf { it.isNotBlank() }
    private fun thumbnail(urls: List<String>): String? = urls.asSequence().take(16).mapNotNull { value ->
        val uri = runCatching { URI(value) }.getOrNull() ?: return@mapNotNull null
        value.takeIf { value.length <= 2048 && uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (uri.host?.endsWith(".ytimg.com") == true || uri.host?.endsWith(".googleusercontent.com") == true) }
    }.firstOrNull()
}
