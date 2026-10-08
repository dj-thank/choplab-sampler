package com.choplab.sampler.next

import com.choplab.sampler.source.*
import com.choplab.sampler.source.newpipe.NewPipeSourceBackend
import com.choplab.ui.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Platform-only projection: common UI does not depend on the legacy source module. */
internal class AndroidOnlineSourcePort(
    directory: Path, validate: (File) -> Unit, scope: CoroutineScope,
    private val stop: () -> Unit,
    backend: YoutubeSourceBackend = NewPipeSourceBackend(),
) : OnlineSourcePort {
    private val online = OnlineSourceSession(directory, validate, backend)
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + owner)
    private val closed = AtomicBoolean()
    private val mutable = MutableStateFlow(OnlineWorkerState())
    override val state = mutable.asStateFlow()
    init {
        ownScope.launch {
            var encoded: ByteArray? = null
            var image: androidx.compose.ui.graphics.ImageBitmap? = null
            online.state.collectLatest { value ->
                val decoded = if (encoded !== value.artwork) value.artwork?.let { decodeOnlineArtwork(it) } else image
                currentCoroutineContext().ensureActive()
                encoded = value.artwork; image = decoded
                if (online.state.value === value) mutable.update { if (closed.get()) it else value.toUi(image) }
            }
        }
    }
    override fun search(query: String, catalog: OnlineCatalog) = online.search(query, YoutubeSearchKind.valueOf(catalog.name)).also { refresh() }
    override fun inspect(id: String) = online.inspect(id).also { refresh() }
    override fun selectFormat(id: String) = online.selectFormat(id).also { refresh() }
    override fun save(id: String) = online.acquire(id).also { refresh() }
    override fun cancel() { online.cancel(); refresh() }
    override fun stopAll() { if (!closed.get()) stop() }
    /** Resolve only the currently displayed saved receipt, immediately before the host's guarded import. */
    fun saved(id: String): OnlineLibrarySelection? = online.state.value.takeIf { !closed.get() && !it.busy && it.phase == OnlineSourcePhase.SAVED }
        ?.saved?.takeIf { it.hash == id }?.let { OnlineLibrarySelection(it.path, it.title, it.hash) }
    private fun refresh() {
        if (!closed.get()) {
            val current = online.state.value
            mutable.update { if (closed.get() || online.state.value !== current) it else
                current.toUi(it.details?.takeIf { detail -> current.artwork != null && detail.id == current.details?.id }?.artwork) }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        owner.cancel(); online.close()
        mutable.value = OnlineWorkerState(phase = OnlinePhase.CLOSED)
    }
}

private fun OnlineSourceState.toUi(image: androidx.compose.ui.graphics.ImageBitmap?): OnlineWorkerState = OnlineWorkerState(
    phase = OnlinePhase.valueOf(phase.name), busy = busy, candidates = candidates.map { it.toUi(null) },
    details = details?.toUi(image), progress = progress,
    saved = saved?.let { OnlineSaved(it.hash, it.title) }, problem = problem?.let { OnlineProblem.valueOf(it.name) },
    artworkUnavailable = details != null && image == null && !artworkLoading,
    artworkLoading = artworkLoading, maxDownloadBytes = OnlineSourceLimits.MAX_AUDIO_BYTES,
    failedOperation = failedOperation?.let { OnlinePhase.valueOf(it.name) },
)
private fun YoutubeSource.toUi(image: androidx.compose.ui.graphics.ImageBitmap?) = OnlineCandidate(id, title, author, durationSeconds,
    metadata?.artist, metadata?.album, metadata?.uploaderVerified, metadata?.formats.orEmpty().map {
        OnlineAudioFormat(it.id, it.container, it.codec, it.sampleRate, it.channels, it.bitrate, it.approximateBitrate,
            it.bytes, it.language, it.trackName, it.trackType, it.dynamicRangeCompressed)
    }, selectedFormat, image)
