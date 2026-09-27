package com.choplab.desktop.next

import com.choplab.sampler.source.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Candidate lookup and explicit acquisition are separate owned jobs; neither edits the document. */
internal class NextOnline(directory: Path, validate: (java.io.File) -> Unit,
                          private val backend: YoutubeSourceBackend) : AutoCloseable {
    enum class Status { READY, SEARCHING, CANDIDATES, DOWNLOADING, SELECTED, FAILED, CANCELLED }
    data class State(val status: Status = Status.READY, val busy: Boolean = false,
                     val candidates: List<YoutubeSource> = emptyList(), val progress: Int = 0,
                     val selection: NextLibrary.Selection? = null)
    private val library by lazy { LocalAudioLibrary(directory.toFile(), validate) }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-NEXT-Online").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var future: Future<*>? = null
    private var jobId: String? = null
    private var closed = false

    @Synchronized private fun start(status: Status, action: (Long, String) -> Unit): Boolean {
        if (closed || mutable.value.busy) return false
        val lease = generation.incrementAndGet()
        val id = UUID.randomUUID().toString(); jobId = id
        mutable.update { it.copy(status = status, busy = true, selection = null, progress = 0,
            candidates = if (status == Status.SEARCHING) emptyList() else it.candidates) }
        future = executor.submit {
            try { current(lease); action(lease, id) }
            catch (_: Exception) { publish(lease) { it.copy(status = Status.FAILED) } }
            finally { publish(lease) { it.copy(busy = false) } }
        }
        return true
    }
    private fun current(lease: Long) {
        if (generation.get() != lease || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
    private fun publish(lease: Long, change: (State) -> State) {
        mutable.update { if (generation.get() == lease) change(it) else it }
    }
    fun search(query: String): Boolean = start(Status.SEARCHING) { lease, id ->
        val input = query.trim()
        require(input.isNotBlank() && input.length <= 240 && input.none { it.code < 32 })
        val found = if (SourceRecipes.isUrlInput(input)) listOf(backend.info(SourceRecipes.youtubeUrl(input), id))
            else backend.search(input, id)
        current(lease)
        val choices = found.take(5).filter {
            runCatching { SourceRecipes.youtubeUrl(it.url) }.isSuccess && it.title.isNotBlank() &&
                it.durationSeconds.isFinite() && it.durationSeconds in 0.0..600.0
        }.distinctBy { it.id }
        publish(lease) { it.copy(status = Status.CANDIDATES, candidates = choices) }
    }
    @Synchronized fun acquire(sourceId: String): Boolean {
        val selected = mutable.value.candidates.firstOrNull { it.id == sourceId } ?: return false
        return start(Status.DOWNLOADING) { lease, id ->
            val checked = backend.info(selected.url, id)
            current(lease)
            require(checked.id == selected.id && checked.durationSeconds.isFinite() && checked.durationSeconds in 0.01..600.0)
            val temporary = Files.createTempDirectory("choplab-next-online-")
            try {
                val file = backend.download(checked, temporary.toFile(), id) { value ->
                    if (value.isFinite()) publish(lease) { it.copy(progress = value.toInt().coerceIn(0, 100)) }
                }
                current(lease)
                require(file.toPath().toRealPath().startsWith(temporary.toRealPath()))
                val item = library.importFile(file, checked.title, checked.url)
                current(lease)
                publish(lease) { it.copy(status = Status.SELECTED,
                    selection = NextLibrary.Selection(library.resolve(item.id).toPath(), item.title, item.id)) }
            } finally { temporary.toFile().deleteRecursively() }
        }
    }
    @Synchronized fun cancel() {
        if (closed || !mutable.value.busy) return
        generation.incrementAndGet(); future?.cancel(true); jobId?.let(backend::cancel)
        mutable.update { it.copy(status = Status.CANCELLED, busy = true, selection = null) }
        val lease = generation.get()
        // Remain busy until the old writer has released the temporary download and library files.
        future = executor.submit { publish(lease) { it.copy(busy = false) } }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); future?.cancel(true); jobId?.let(backend::cancel); executor.shutdownNow()
    }
}
