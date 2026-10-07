package com.choplab.sampler.source

import com.choplab.sampler.source.newpipe.DetailedYoutubeBackend
import com.choplab.sampler.source.newpipe.OnlineSourceException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

enum class OnlineSourcePhase { READY, SEARCHING, CANDIDATES, INSPECTING, DETAILS, DOWNLOADING, SAVING, SAVED, FAILED, CANCELLED, CLOSED }
data class OnlineLibrarySelection(val path: Path, val title: String, val hash: String)
data class OnlineSourceState(
    val phase: OnlineSourcePhase = OnlineSourcePhase.READY,
    val busy: Boolean = false,
    val candidates: List<YoutubeSource> = emptyList(),
    val details: YoutubeSource? = null,
    val progress: Int = 0,
    val saved: OnlineLibrarySelection? = null,
    val problem: OnlineSourceProblem? = null,
    val artwork: ByteArray? = null,
)

/** One owned provider/library worker. Saving here never changes the active Studio document. */
class OnlineSourceSession(directory: Path, validate: (File) -> Unit,
                          private val backend: YoutubeSourceBackend) : AutoCloseable {
    private val library by lazy { LocalAudioLibrary(directory.toFile()) { file ->
        try { validate(file) }
        catch (error: InterruptedException) { throw error }
        catch (error: OnlineSourceException) { throw error }
        catch (_: Exception) { throw OnlineSourceException(OnlineSourceProblem.INVALID_AUDIO) }
    } }
    private val mutable = MutableStateFlow(OnlineSourceState())
    val state = mutable.asStateFlow()
    val detailed: Boolean get() = backend is DetailedYoutubeBackend
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-Online-Source").apply { isDaemon = true } }
    // HttpURLConnection.disconnect may wait for platform I/O. Cancellation never runs it on the UI thread.
    private val cancellation = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-Online-Cancel").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var future: Future<*>? = null
    private var jobId: String? = null
    private var closed = false

    @Synchronized private fun start(phase: OnlineSourcePhase, action: (Long, String) -> Unit): Boolean {
        if (closed || mutable.value.busy) return false
        val lease = generation.incrementAndGet()
        val id = UUID.randomUUID().toString(); jobId = id
        mutable.update { it.copy(phase = phase, busy = true, saved = null, progress = 0, problem = null,
            candidates = if (phase == OnlineSourcePhase.SEARCHING) emptyList() else it.candidates,
            details = if (phase in setOf(OnlineSourcePhase.SEARCHING, OnlineSourcePhase.INSPECTING)) null else it.details,
            artwork = if (phase in setOf(OnlineSourcePhase.SEARCHING, OnlineSourcePhase.INSPECTING)) null else it.artwork) }
        future = executor.submit {
            try { current(lease); action(lease, id) }
            catch (error: Exception) {
                val problem = when (error) {
                    is OnlineSourceException -> error.problem
                    is InterruptedException -> OnlineSourceProblem.CANCELLED
                    is IllegalArgumentException -> OnlineSourceProblem.INVALID_INPUT
                    else -> OnlineSourceProblem.NETWORK
                }
                publish(lease) { it.copy(phase = OnlineSourcePhase.FAILED, problem = problem) }
            } finally {
                synchronized(this) { if (generation.get() == lease) jobId = null }
                publish(lease) { it.copy(busy = false) }
            }
        }
        return true
    }
    private fun current(lease: Long) {
        if (generation.get() != lease || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
    private fun publish(lease: Long, change: (OnlineSourceState) -> OnlineSourceState) {
        mutable.update { if (generation.get() == lease) change(it) else it }
    }

    fun search(query: String, kind: YoutubeSearchKind = YoutubeSearchKind.VIDEOS): Boolean = start(OnlineSourcePhase.SEARCHING) { lease, id ->
        val input = query.trim()
        require(input.isNotBlank() && input.length <= 240 && input.none { it.code < 32 })
        val found = if (SourceRecipes.isUrlInput(input)) listOf(backend.info(SourceRecipes.youtubeUrl(input), id))
            else if (backend is DetailedYoutubeBackend) backend.search(input, id, kind)
            else {
                if (kind != YoutubeSearchKind.VIDEOS) throw OnlineSourceException(OnlineSourceProblem.UNSUPPORTED_FORMAT)
                backend.search(input, id)
            }
        current(lease)
        val choices = found.take(5).filter {
            runCatching { SourceRecipes.youtubeUrl(it.url) }.isSuccess && it.title.isNotBlank() &&
                it.durationSeconds.isFinite() && it.durationSeconds in 0.0..600.0
        }.distinctBy { it.id }.map { it.copy(selectedFormat = null) }
        publish(lease) { it.copy(phase = OnlineSourcePhase.CANDIDATES, candidates = choices) }
    }

    @Synchronized fun inspect(sourceId: String): Boolean {
        val selected = mutable.value.candidates.firstOrNull { it.id == sourceId } ?: return false
        return start(OnlineSourcePhase.INSPECTING) { lease, id ->
            val checked = backend.info(selected.url, id)
            current(lease)
            require(checked.id == selected.id && checked.durationSeconds.isFinite() && checked.durationSeconds in 0.01..600.0)
            // Artwork is optional metadata; an unavailable image does not change or downgrade the chosen audio.
            val artwork = runCatching { (backend as? DetailedYoutubeBackend)?.artwork(checked, id) }.getOrNull()
                ?.takeIf { it.size in 1..1_048_576 }
            current(lease)
            publish(lease) { it.copy(phase = OnlineSourcePhase.DETAILS, details = checked.copy(selectedFormat = null), artwork = artwork) }
        }
    }

    @Synchronized fun selectFormat(formatId: String): Boolean {
        if (closed || mutable.value.busy) return false
        val details = mutable.value.details ?: return false
        if (details.metadata?.formats?.count { it.id == formatId } != 1) return false
        mutable.update { it.copy(phase = OnlineSourcePhase.DETAILS, details = details.copy(selectedFormat = formatId), saved = null, problem = null) }
        return true
    }

    @Synchronized fun acquire(sourceId: String): Boolean {
        val selected = mutable.value.candidates.firstOrNull { it.id == sourceId } ?: return false
        val confirmed = mutable.value.details?.takeIf { it.id == sourceId }
        if (detailed && (confirmed?.selectedFormat == null || confirmed.metadata?.formats?.count { it.id == confirmed.selectedFormat } != 1)) return false
        return start(OnlineSourcePhase.DOWNLOADING) { lease, id ->
            val checked = if (detailed) requireNotNull(confirmed) else backend.info(selected.url, id)
            current(lease)
            require(checked.id == selected.id && checked.durationSeconds.isFinite() && checked.durationSeconds in 0.01..600.0)
            val temporary = Files.createTempDirectory("choplab-online-source-")
            try {
                val file = backend.download(checked, temporary.toFile(), id) { value ->
                    if (value.isFinite()) publish(lease) { it.copy(progress = value.toInt().coerceIn(0, 100)) }
                }
                current(lease)
                require(file.toPath().toRealPath().startsWith(temporary.toRealPath()))
                publish(lease) { it.copy(phase = OnlineSourcePhase.SAVING) }
                val item = try { library.importFileResult(file, checked.title, checked.url,
                    AudioLibraryMetadata(checked.metadata?.artist.orEmpty(), checked.metadata?.album.orEmpty())) { current(lease) }.item }
                    catch (error: InterruptedException) { throw error }
                    catch (error: OnlineSourceException) { throw error }
                    catch (_: IllegalArgumentException) { throw OnlineSourceException(OnlineSourceProblem.INVALID_AUDIO) }
                    catch (_: IOException) { throw OnlineSourceException(OnlineSourceProblem.STORAGE) }
                    catch (_: IllegalStateException) { throw OnlineSourceException(OnlineSourceProblem.STORAGE) }
                current(lease)
                publish(lease) { it.copy(phase = OnlineSourcePhase.SAVED,
                    saved = OnlineLibrarySelection(library.resolve(item.id).toPath(), item.title, item.id)) }
            } finally { temporary.toFile().deleteRecursively() }
        }
    }

    @Synchronized fun cancel() {
        if (closed || !mutable.value.busy || mutable.value.phase == OnlineSourcePhase.CANCELLED) return
        generation.incrementAndGet(); future?.cancel(true)
        val cancel = jobId?.let { id -> cancellation.submit { runCatching { backend.cancel(id) } } }
        mutable.update { it.copy(phase = OnlineSourcePhase.CANCELLED, busy = true, saved = null, details = null,
            problem = OnlineSourceProblem.CANCELLED) }
        val lease = generation.get()
        future = executor.submit {
            // A new job cannot overtake either the old file writer or its platform disconnect.
            cancel?.get()
            synchronized(this) { if (generation.get() == lease) jobId = null }
            publish(lease) { it.copy(busy = false) }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); future?.cancel(true); executor.shutdownNow()
        val id = jobId
        cancellation.execute {
            if (id != null) runCatching { backend.cancel(id) }
            runCatching { (backend as? AutoCloseable)?.close() }
        }
        cancellation.shutdown()
        mutable.update { it.copy(phase = OnlineSourcePhase.CLOSED, busy = false, saved = null, artwork = null) }
    }
}
