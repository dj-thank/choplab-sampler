package com.choplab.desktop.next

import com.choplab.jvm.WavAudio
import com.choplab.sampler.source.LocalAudioLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Owns one separation job. The editor changes only after the caller explicitly accepts the result. */
internal class NextSeparation(directory: Path, validate: (java.io.File) -> Unit,
    private val load: (cancelled: () -> Boolean) -> WavAudio,
    private val render: (WavAudio, Path, (Float) -> Unit, () -> Boolean) -> Unit,
    private val title: String, val initialDownloadBytes: Long = 0) : AutoCloseable {
    enum class Status { READY, RUNNING, DONE, FAILED, CANCELLED }
    data class State(val status: Status = Status.READY, val busy: Boolean = false,
                     val progress: Int = 0, val result: NextLibrary.Selection? = null)
    private val library by lazy { LocalAudioLibrary(directory.toFile(), validate) }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ChopLab-NEXT-Separation").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var future: Future<*>? = null
    private var closed = false
    private fun publish(lease: Long, change: (State) -> State) = mutable.update { if (generation.get() == lease) change(it) else it }

    @Synchronized fun start(): Boolean {
        if (closed || mutable.value.busy) return false
        val lease = generation.incrementAndGet()
        mutable.value = State(Status.RUNNING, busy = true)
        future = executor.submit {
            val cancelled = { generation.get() != lease || Thread.currentThread().isInterrupted }
            fun current() { if (cancelled()) throw InterruptedException() }
            var temporary: Path? = null
            try {
                current()
                val source = load(cancelled)
                current()
                val scratch = Files.createTempDirectory("choplab-next-separation-").also { temporary = it }
                val output = scratch.resolve("drums.wav")
                render(source, output, { value ->
                    if (value.isFinite()) publish(lease) { it.copy(progress = (value * 100).toInt().coerceIn(0, 100)) }
                }, cancelled)
                current()
                val item = library.importFile(output.toFile(), title)
                current()
                publish(lease) { it.copy(status = Status.DONE, progress = 100,
                    result = NextLibrary.Selection(library.resolve(item.id).toPath(), item.title, item.id)) }
            } catch (_: Exception) { publish(lease) { it.copy(status = Status.FAILED) } }
            finally {
                temporary?.toFile()?.deleteRecursively()
                publish(lease) { it.copy(busy = false) }
            }
        }
        return true
    }
    @Synchronized fun cancel() {
        if (closed || !mutable.value.busy) return
        val lease = generation.incrementAndGet()
        future?.cancel(true)
        mutable.value = State(Status.CANCELLED, busy = true)
        // Native inference can finish its current chunk after interruption. Wait for its actual release.
        future = executor.submit { publish(lease) { it.copy(busy = false) } }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        future?.cancel(true)
        executor.shutdownNow()
    }
}
