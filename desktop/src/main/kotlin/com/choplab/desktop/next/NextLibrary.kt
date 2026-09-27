package com.choplab.desktop.next

import com.choplab.sampler.source.AudioLibraryItem
import com.choplab.sampler.source.LocalAudioLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** One library job, independent from the editor document. Only an explicit selection opens an original. */
internal class NextLibrary(directory: Path, validate: (java.io.File) -> Unit) : AutoCloseable {
    enum class Status { READY, LOADING, IMPORTING, ADDED, PARTLY_ADDED, FAILED, CANCELLED, EXPORTING, EXPORTED, SELECTING, SELECTED }
    data class Selection(val path: Path, val title: String, val hash: String)
    data class State(val items: List<AudioLibraryItem> = emptyList(), val status: Status = Status.LOADING,
        val busy: Boolean = true, val selection: Selection? = null, val completed: Int = 0, val total: Int = 0, val failed: Int = 0)
    private val library by lazy { LocalAudioLibrary(directory.toFile(), validate) }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-NEXT-Library").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var future: Future<*>? = null
    private var closed = false

    init { start(Status.LOADING) { lease -> publish(lease) { it.copy(status = Status.READY) } } }

    /** The returned file is consumed through the editor's normal bounded, original-byte import. */
    fun select(id: String): Boolean = start(Status.SELECTING) { lease ->
        val path = library.resolve(id).toPath()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                current(lease)
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == id)
        val item = library.list().first { it.id == id }
        current(lease)
        publish(lease) { it.copy(status = Status.SELECTED, selection = Selection(path, item.title, id)) }
    }

    @Synchronized private fun start(status: Status, action: (Long) -> Unit): Boolean {
        if (closed || future?.isDone == false) return false
        val lease = generation.incrementAndGet()
        mutable.update { it.copy(status = status, busy = true, selection = null, completed = 0, total = 0, failed = 0) }
        future = executor.submit {
            try { action(lease) }
            catch (_: Exception) { publish(lease) { it.copy(status = Status.FAILED) } }
            finally {
                val items = try { library.list() } catch (_: Exception) { emptyList() }
                publish(lease) { it.copy(items = items, busy = false) }
            }
        }
        return true
    }
    private fun current(lease: Long) {
        if (generation.get() != lease || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
    private fun publish(lease: Long, transform: (State) -> State) {
        mutable.update { if (generation.get() == lease) transform(it) else it }
    }
    fun add(paths: List<Path>): Boolean {
        require(paths.isNotEmpty() && paths.size <= 128)
        return start(Status.IMPORTING) { lease ->
            publish(lease) { it.copy(total = paths.size) }
            var added = 0; var failed = 0
            for (path in paths) {
                current(lease)
                try {
                    if (path.fileName.toString().substringAfterLast('.').lowercase() in setOf("zip", "choplib"))
                        added += library.importBundle(path.toFile()).size
                    else { library.importFile(path.toFile()); added++ }
                } catch (_: Exception) { current(lease); failed++ }
                publish(lease) { it.copy(completed = added, failed = failed) }
            }
            current(lease)
            publish(lease) { it.copy(status = when { failed == 0 -> Status.ADDED; added > 0 -> Status.PARTLY_ADDED; else -> Status.FAILED }) }
        }
    }
    /** Replace only after the entire bundle is written; cancellation leaves an existing destination untouched. */
    fun export(target: Path): Boolean = start(Status.EXPORTING) { lease ->
        val absolute = target.toAbsolutePath()
        val temporary = Files.createTempFile(absolute.parent, ".choplab-library-", ".pending")
        try {
            library.exportBundle(temporary.toFile())
            current(lease)
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            publish(lease) { it.copy(status = Status.EXPORTED) }
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun cancel() {
        if (closed) return
        generation.incrementAndGet(); future?.cancel(true)
        mutable.update { it.copy(status = Status.CANCELLED, busy = true, selection = null) }
        val lease = generation.get()
        // The same worker refreshes after the interrupted job has released its files; no competing writer starts.
        future = executor.submit {
            val items = try { library.list() } catch (_: Exception) { emptyList() }
            publish(lease) { it.copy(items = items, busy = false) }
        }
    }
    @Synchronized override fun close() { closed = true; generation.incrementAndGet(); future?.cancel(true); executor.shutdownNow() }
}
