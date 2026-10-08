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

/** One library job, independent from the editor document. Only explicit Use opens an original. */
internal class NextLibrary(directory: Path, initialOffset: Int, validate: (java.io.File) -> Unit) : AutoCloseable {
    constructor(directory: Path, validate: (java.io.File) -> Unit) : this(directory, 0, validate)
    enum class Status { READY, LOADING, LOAD_FAILED, IMPORTING, ADDED, PARTLY_ADDED, FAILED, CANCELLED, EXPORTING, EXPORTED, SELECTING, SELECTED, BUNDLE_LIMIT }
    enum class FailureReason { MISSING, ACCESS, EMPTY, TOO_LARGE, INVALID_AUDIO, CAPACITY, CORRUPT }
    data class Failure(val path: Path, val reason: FailureReason, val entryTitle: String? = null) {
        val name get() = listOfNotNull(path.fileName.toString(), entryTitle).joinToString(" / ").filter { it.code >= 32 }.take(480)
    }
    data class Selection(val path: Path, val title: String, val hash: String)
    data class State(val items: List<AudioLibraryItem> = emptyList(), val status: Status = Status.LOADING,
        val busy: Boolean = false, val selection: Selection? = null, val completed: Int = 0, val reused: Int = 0,
        val total: Int = 0, val position: Int = 0, val failures: List<Failure> = emptyList(),
        val readFailed: Boolean = false, val unreadable: Int = 0, val catalogTotal: Int = 0, val catalogOffset: Int = 0,
        val exportItems: List<AudioLibraryItem> = emptyList(), val repaired: Int = 0, val tagFallbacks: Int = 0) {
        val failed get() = failures.size
        val hasOlder get() = catalogOffset + LocalAudioLibrary.MAX_ITEMS < catalogTotal
        val hasNewer get() = catalogOffset > 0
    }
    private val library by lazy { LocalAudioLibrary(directory.toFile(), validate) }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-NEXT-Library").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var future: Future<*>? = null
    private var closed = false

    init { refresh(initialOffset) }

    fun refresh(offset: Int = state.value.catalogOffset): Boolean = start(Status.LOADING, resetResults = false) { lease ->
        if (readListing(lease, offset)) publish(lease) { it.copy(status = Status.READY) }
    }
    private fun readListing(lease: Long, offset: Int): Boolean = try {
        current(lease)
        val listing = library.listing(offset)
        current(lease)
        publish(lease) { it.copy(items = listing.items, catalogTotal = listing.total, catalogOffset = listing.offset,
            unreadable = listing.unreadable, readFailed = false) }
        true
    } catch (_: Exception) {
        publish(lease) { it.copy(readFailed = true, status = if (it.status == Status.LOADING) Status.LOAD_FAILED else it.status) }
        false
    }

    /** Consumed through the editor's normal bounded, original-byte import. */
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
        val item = library.get(id)
        current(lease)
        publish(lease) { it.copy(status = Status.SELECTED, selection = Selection(path, item.title, id)) }
    }

    @Synchronized private fun start(status: Status, resetResults: Boolean = true, action: (Long) -> Unit): Boolean {
        if (closed || mutable.value.busy) return false
        val lease = generation.incrementAndGet()
        mutable.update { previous ->
            val next = previous.copy(status = status, busy = true, selection = null)
            if (resetResults) next.copy(completed = 0, reused = 0, repaired = 0, tagFallbacks = 0, total = 0, position = 0, failures = emptyList()) else next
        }
        future = executor.submit {
            try { action(lease) }
            catch (_: Exception) { publish(lease) { it.copy(status = Status.FAILED) } }
            finally {
                if (status != Status.LOADING) readListing(lease, mutable.value.catalogOffset)
                publish(lease) { it.copy(busy = false) }
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
    fun retryFailures(): Boolean = state.value.failures.map { it.path }.distinct().takeIf { it.isNotEmpty() }?.let(::add) ?: false
    fun repairFailures(): Boolean = state.value.failures.filter { it.reason == FailureReason.CORRUPT && it.entryTitle == null }
        .map { it.path }.distinct().takeIf { it.isNotEmpty() }?.let { add(it, repair = true) } ?: false
    fun add(paths: List<Path>): Boolean = add(paths, repair = false)
    private fun add(paths: List<Path>, repair: Boolean): Boolean {
        require(paths.isNotEmpty() && paths.size <= 128)
        return start(Status.IMPORTING) { lease ->
            publish(lease) { it.copy(total = paths.size) }
            var added = 0; var reused = 0; var repaired = 0; var tagFallbacks = 0
            fun count(results: List<LocalAudioLibrary.ImportResult>) {
                added += results.count { it.added }; reused += results.count { !it.added && !it.repaired }
                repaired += results.count { it.repaired }; tagFallbacks += results.count { it.tagProblem != null }
            }
            val failures = mutableListOf<Failure>()
            for ((index, path) in paths.withIndex()) {
                current(lease)
                publish(lease) { it.copy(position = index + 1) }
                try {
                    val results = if (path.fileName.toString().substringAfterLast('.').lowercase() in setOf("zip", "choplib"))
                        library.importBundleResults(path.toFile())
                    else listOf(if (repair) library.repairFromOriginal(path.toFile()) { current(lease) }
                        else library.importFileResult(path.toFile(), checkCancelled = { current(lease) }))
                    count(results)
                } catch (error: Exception) {
                    current(lease)
                    if (error is LocalAudioLibrary.PartialBundle) count(error.completed)
                    val size = runCatching { Files.size(path) }.getOrDefault(-1)
                    fun failure(cause: Exception, title: String? = null) = Failure(path, when {
                        cause is LocalAudioLibrary.CorruptPayload -> FailureReason.CORRUPT
                        cause is LocalAudioLibrary.CapacityExceeded -> FailureReason.CAPACITY
                        !Files.exists(path) -> FailureReason.MISSING
                        !Files.isReadable(path) || cause is java.io.IOException -> FailureReason.ACCESS
                        Files.isRegularFile(path) && size == 0L -> FailureReason.EMPTY
                        Files.isRegularFile(path) && size > LocalAudioLibrary.MAX_FILE_BYTES && path.toFile().extension.lowercase() !in setOf("zip", "choplib") -> FailureReason.TOO_LARGE
                        else -> FailureReason.INVALID_AUDIO
                    }, title)
                    if (error is LocalAudioLibrary.PartialBundle) failures += error.failures.map { failure(it.error, it.title) }
                    else failures += failure(error)
                }
                publish(lease) { it.copy(completed = added, reused = reused, repaired = repaired, tagFallbacks = tagFallbacks, failures = failures.toList()) }
            }
            current(lease)
            publish(lease) { it.copy(status = when { failures.isEmpty() -> Status.ADDED; added + reused + repaired > 0 -> Status.PARTLY_ADDED; else -> Status.FAILED }) }
        }
    }

    @Synchronized fun toggleExport(item: AudioLibraryItem): Boolean {
        if (closed || state.value.busy) return false
        val selected = state.value.exportItems
        return if (selected.any { it.id == item.id }) setExportItems(selected.filterNot { it.id == item.id })
            else addExportItems(listOf(item))
    }
    @Synchronized fun addExportItems(items: List<AudioLibraryItem>): Boolean {
        if (closed || state.value.busy || items.any { it !in state.value.items }) return false
        return setExportItems((state.value.exportItems + items).distinctBy { it.id })
    }
    private fun setExportItems(items: List<AudioLibraryItem>): Boolean {
        if (items.size > 32 || items.sumOf { it.bytes } > 1024L * 1024 * 1024) {
            mutable.update { it.copy(status = Status.BUNDLE_LIMIT) }; return false
        }
        mutable.update { it.copy(exportItems = items, status = if (it.status == Status.BUNDLE_LIMIT) Status.READY else it.status) }; return true
    }
    @Synchronized fun clearExportSelection() { if (!closed && !state.value.busy) setExportItems(emptyList()) }

    /** Replace only after the whole selected bundle is verified; cancellation preserves the destination. */
    fun export(target: Path, selectedIds: List<String>? = null): Boolean = start(Status.EXPORTING) { lease ->
        val items = if (selectedIds == null) library.list() else selectedIds.map(library::get)
        if (items.isEmpty() || items.size > 32 || items.sumOf { it.bytes } > 1024L * 1024 * 1024) {
            publish(lease) { it.copy(status = Status.BUNDLE_LIMIT) }; return@start
        }
        val absolute = target.toAbsolutePath()
        val temporary = Files.createTempFile(absolute.parent, ".choplab-library-", ".pending")
        try {
            library.exportBundle(temporary.toFile(), selectedIds)
            synchronized(this) {
                current(lease)
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                publish(lease) { it.copy(status = Status.EXPORTED, exportItems = emptyList()) }
            }
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun cancel() {
        if (closed || !mutable.value.busy) return
        generation.incrementAndGet(); future?.cancel(true)
        mutable.update { it.copy(status = Status.CANCELLED, busy = true, selection = null) }
        val lease = generation.get()
        // Wait on the same worker before accepting another writer.
        future = executor.submit {
            readListing(lease, mutable.value.catalogOffset)
            publish(lease) { it.copy(busy = false) }
        }
    }
    @Synchronized override fun close() { closed = true; generation.incrementAndGet(); future?.cancel(true); executor.shutdownNow() }
}
