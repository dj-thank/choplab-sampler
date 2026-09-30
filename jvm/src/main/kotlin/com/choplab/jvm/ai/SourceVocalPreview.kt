package com.choplab.jvm.ai

import com.choplab.core.Action
import com.choplab.core.Studio
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Uses the existing SOURCE lane; it does not reserve another render voice or change an audio document. */
class SourceVocalPreview(private val studio: Studio, private val engine: StreamingEnginePort,
                         private val audition: SourceAuditionController, scope: CoroutineScope) : VocalPreviewPort {
    constructor(backend: EditorBackend, scope: CoroutineScope) : this(backend.studio, backend.engine, backend.audition, scope)
    private data class Saved(val projectId: String, val revision: Long, val source: Source?, val frame: Long, val gain: Float)
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val controls = Mutex()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutable = MutableStateFlow(VocalPreviewState())
    override val state = mutable.asStateFlow()
    @Volatile private var loading: Job? = null
    private var saved: Saved? = null
    @Volatile private var ownedRevision: Long? = null

    init {
        jobs.launch {
            studio.document.collect { document ->
                val revision = ownedRevision
                if (revision != null && revision != document.revision) {
                    cancelLoad()
                    restore(TtsFailure(TtsProblem.STALE_DOCUMENT))
                }
            }
        }
        jobs.launch {
            var faults = engine.status.value.faults
            engine.status.collect { status ->
                if (state.value.ownsSource && (status.faults != faults || status.phase in setOf(DriverPhase.EDITING_ONLY, DriverPhase.CLOSED))) {
                    cancelLoad(); restore(TtsFailure(TtsProblem.FAILED))
                } else if (state.value.ownsSource && state.value.phase == VocalPreviewPhase.FAILED && status.phase == DriverPhase.ATTACHED) {
                    restore(state.value.failure)
                }
                faults = status.faults
            }
        }
        jobs.launch {
            while (isActive) {
                if (state.value.phase == VocalPreviewPhase.PLAYING && engine.status.value.phase == DriverPhase.ATTACHED &&
                    !engine.originalPlayback().playing) restore(null)
                delay(25)
            }
        }
    }

    override suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit> = controls.withLock {
        currentCoroutineContext().ensureActive()
        if (closed.get()) return@withLock ttsFailure(TtsProblem.CLOSED)
        if (studio.document.value.revision != expectedRevision) return@withLock ttsFailure(TtsProblem.STALE_DOCUMENT)
        if (asset.sampleRate != 48_000 || asset.channels != 2 || asset.role != AssetRole.RENDERED) return@withLock ttsFailure(TtsProblem.INVALID_AUDIO)
        cancelLoad()
        val document = studio.document.value
        if (saved == null) saved = Saved(document.project.id, document.revision, document.project.source, audition.nativeFrame(), engine.originalPlayback().gain)
        ownedRevision = expectedRevision
        val token = generation.get()
        mutable.value = VocalPreviewState(VocalPreviewPhase.LOADING, true, asset.hash, originalFrame = requireNotNull(saved).frame)
        loading = jobs.launch {
            val ready = try {
                studio.dispatch(Action.Silence).accepted && current(token, expectedRevision) && audition.pause() &&
                    current(token, expectedRevision) && audition.pitch(0f) && audition.originalGain(1f) &&
                    current(token, expectedRevision) && audition.seek(asset, 0) && current(token, expectedRevision) && audition.play(asset)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
            controls.withLock {
                if (!current(token, expectedRevision)) return@withLock
                if (ready) mutable.value = mutable.value.copy(phase = VocalPreviewPhase.PLAYING)
            }
            if (!ready && current(token, expectedRevision)) restore(TtsFailure(TtsProblem.FAILED))
        }
        TtsResult.Success(Unit)
    }

    private fun current(token: Long, revision: Long) = !closed.get() && generation.get() == token && studio.document.value.revision == revision
    private fun cancelLoad(): Long {
        val token = generation.incrementAndGet()
        loading?.cancel(); loading = null
        audition.cancelPreparation()
        return token
    }
    override fun requestStop() {
        val token = cancelLoad()
        if (!closed.get()) jobs.launch { restore(null, token) }
    }
    override suspend fun stop(): TtsResult<Unit> { cancelLoad(); return restore(null) }

    private suspend fun restore(reason: TtsFailure?, expectedGeneration: Long? = null): TtsResult<Unit> = controls.withLock {
        if (expectedGeneration != null && generation.get() != expectedGeneration) return@withLock TtsResult.Success(Unit)
        val original = saved ?: return@withLock TtsResult.Success(Unit)
        mutable.value = mutable.value.copy(phase = VocalPreviewPhase.RESTORING)
        val document = studio.document.value
        ownedRevision = document.revision
        val same = document.project.id == original.projectId && document.revision == original.revision && document.project.source == original.source
        val source = document.project.source
        val restored = try {
            val paused = audition.pause()
            val position = if (same) original.frame else source?.range?.start ?: 0L
            val reset = if (source == null) audition.clear() else audition.seek(document.project.asset(source.assetHash), position.coerceIn(0, document.project.asset(source.assetHash).frames))
            val key = audition.pitch((source?.pitchSemitones ?: 0.0).toFloat())
            val gain = audition.originalGain(original.gain)
            paused && reset && key && gain && studio.document.value.revision == document.revision
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        if (restored) {
            saved = null
            ownedRevision = null
            mutable.value = VocalPreviewState(if (reason == null) VocalPreviewPhase.IDLE else VocalPreviewPhase.FAILED, failure = reason)
            TtsResult.Success(Unit)
        } else {
            val failure = reason ?: TtsFailure(TtsProblem.FAILED)
            mutable.value = VocalPreviewState(VocalPreviewPhase.FAILED, true, failure = failure, originalFrame = original.frame)
            TtsResult.Failure(failure)
        }
    }
    override fun frame(): Long = if (state.value.phase == VocalPreviewPhase.PLAYING) audition.nativeFrame() else 0L
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelLoad()
        try { withContext(NonCancellable) { restore(null) } }
        finally { owner.cancel(); mutable.value = VocalPreviewState(VocalPreviewPhase.CLOSED) }
    }
}
