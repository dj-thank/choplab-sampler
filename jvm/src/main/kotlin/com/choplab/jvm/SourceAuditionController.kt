package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.model.Asset
import com.choplab.core.model.FrameRange
import com.choplab.engine.EngineCommand
import com.choplab.engine.OriginalSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Independent original-source transport. Never replaces Studio's document program or PAD table. */
class SourceAuditionController(
    private val driver: StreamingEnginePort,
    private val pcm: PcmPort,
    scope: CoroutineScope,
    /** How monitoring commands reach the output. */
    private val send: suspend (EngineCommand) -> Boolean = driver::applyMonitoring,
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val generation = AtomicLong()
    private val order = AtomicLong()
    private val preparation = AtomicReference<Job?>(null)
    private val loaded = AtomicReference<Asset?>(null)
    private val loadedPcm = AtomicReference<com.choplab.engine.PcmLease?>(null)
    private val controls = Mutex()
    /** The song key the original should play at; sent again with every source, so a rebuilt output keeps it. */
    @Volatile private var semitones = 0f
    @Volatile private var loadedLoop = false
    @Volatile private var loadedRange: FrameRange? = null
    /** Only the output which acknowledged SOURCE and its key can reuse this cached load. */
    @Volatile private var loadedSession: Any? = null
    /** Whether the loaded original took [semitones]; a key the output refused is sent again before the next play. */
    @Volatile private var keyApplied = false
    @Volatile private var handMonitorGain = 1f

    fun cancelPreparation() {
        generation.incrementAndGet()
        preparation.getAndSet(null)?.cancel()
    }

    private suspend fun ensureSource(asset: Asset, token: Long, loop: Boolean = false, range: FrameRange? = null): Boolean {
        require(range == null || range.end <= asset.frames)
        val status = loadedPcm.get()?.pcm?.pages?.status
        if (loaded.get() == asset && loadedLoop == loop && loadedRange == range && hasCurrentSource() &&
            status != com.choplab.engine.PcmReadStatus.FAILED && status != com.choplab.engine.PcmReadStatus.CLOSED) return true
        val delivery = AtomicReference<com.choplab.engine.PcmLease?>(null)
        val job = jobs.async {
            pcm.acquire(asset).also { delivery.set(it); ensureActive() }
        }
        job.invokeOnCompletion { failure -> if (failure != null) delivery.getAndSet(null)?.close() }
        preparation.set(job)
        if (token != generation.get()) {
            preparation.compareAndSet(job, null); job.cancel(); delivery.getAndSet(null)?.close(); return false
        }
        try {
            val data = job.await().pcm
            val first = range?.let { ((it.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate).toInt() } ?: 0
            val last = range?.let { (it.end * 48_000 / asset.sampleRate).toInt() } ?: data.frameCount
            if (last <= first) return false
            return controls.withLock {
                if (token != generation.get()) return@withLock false
                val session = driver.sourceOutputSession() ?: return@withLock false
                // A partly accepted replacement cannot leave the previous asset marked as reusable.
                loadedSession = null
                val accepted = command { frame, id -> EngineCommand.SetOriginalSource(frame, id, OriginalSource(data, first, last, loop = loop, loopCrossfadeFrames = if (loop) 480 else 0)) } &&
                    command { frame, id -> EngineCommand.SetOriginalPitch(frame, id, semitones) }
                val current = accepted && token == generation.get() && session === driver.sourceOutputSession()
                if (current) {
                    loaded.set(asset); loadedLoop = loop; loadedRange = range; loadedPcm.getAndSet(delivery.getAndSet(null))?.close(); keyApplied = true
                    loadedSession = session
                }
                current
            }
        } finally { preparation.compareAndSet(job, null); job.cancel(); delivery.getAndSet(null)?.close() }
    }

    private fun hasCurrentSource(): Boolean {
        val session = driver.sourceOutputSession() ?: return false
        if (loadedSession !== session) return false
        val loaded = when (val readout = driver.originalPlaybackProbe()) {
            is OriginalPlaybackProbe.Ready -> readout.playback.loaded
            // An acknowledged load on this same output stays valid during a bounded read collision.
            OriginalPlaybackProbe.Contended -> true
            OriginalPlaybackProbe.Unavailable -> false
        }
        return loaded && session === driver.sourceOutputSession()
    }

    suspend fun play(asset: Asset, loop: Boolean = false, range: FrameRange? = null): Boolean {
        cancelPreparation()
        val token = generation.get()
        if (!ensureSource(asset, token, loop, range)) return false
        val position = driver.originalPlayback().sourceFrame
        val end = range?.let { it.end * 48_000 / asset.sampleRate } ?: (loadedPcm.get()?.pcm?.frameCount ?: 0).toLong()
        val first = range?.let { (it.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate } ?: 0
        if (!warm(token, if (position >= end) first else position)) return false
        return controls.withLock {
            token == generation.get() && (keyApplied || sendKey()) && command { frame, id -> EngineCommand.PlayOriginalSource(frame, id) }
        }
    }
    suspend fun pause(): Boolean {
        cancelPreparation()
        return controls.withLock { command { frame, id -> EngineCommand.PauseOriginalSource(frame, id) } }
    }
    suspend fun clear(): Boolean {
        cancelPreparation()
        loadedSession = null
        loaded.set(null)
        loadedPcm.getAndSet(null)?.close()
        return controls.withLock { command { frame, id -> EngineCommand.SetOriginalSource(frame, id, null) } }
    }
    suspend fun seek(asset: Asset, nativeFrame: Long, loop: Boolean = false, range: FrameRange? = null): Boolean {
        require(nativeFrame in 0..asset.frames)
        require(range == null || nativeFrame in range.start..range.end)
        cancelPreparation()
        val token = generation.get()
        if (!ensureSource(asset, token, loop, range)) return false
        val frame48 = if (range == null) ProgramFrames.to48k(nativeFrame, asset.sampleRate) else
            ProgramFrames.to48k(nativeFrame, asset.sampleRate).coerceIn((range.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate, range.end * 48_000 / asset.sampleRate)
        if (!warm(token, frame48)) return false
        return controls.withLock { token == generation.get() && command { frame, id -> EngineCommand.SeekOriginalSource(frame, id, frame48) } }
    }
    /**
     * Varispeed for listening: pitch and tempo change together. Kept even when no output takes it now (hidden,
     * lost or still opening); the next source sent to an output carries it.
     */
    suspend fun pitch(semitones: Float): Boolean {
        require(semitones.isFinite() && semitones in -24f..24f)
        return controls.withLock {
            this.semitones = semitones
            sendKey()
        }
    }
    private suspend fun sendKey(): Boolean = command { frame, id -> EngineCommand.SetOriginalPitch(frame, id, semitones) }.also { keyApplied = it }
    suspend fun originalGain(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.SetOriginalMonitorGain(frame, id, gain) } }
    }
    suspend fun songGain(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.SetSongMonitorGain(frame, id, gain) } }
    }
    suspend fun handGain(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock {
            handMonitorGain = gain
            command { frame, id -> EngineCommand.SetHandMonitorGain(frame, id, gain) }
        }
    }
    /**
     * HAND reads the original between [start] and [end] (native frames, end exclusive), from [from]. SOURCE continues
     * independently. Reuses its loaded PCM; the existing bounded loader is used only when the source is not loaded.
     */
    suspend fun scratchStart(asset: Asset, from: Long, start: Long, end: Long): Boolean {
        require(start in 0 until end && end <= asset.frames && from in start until end)
        cancelPreparation()
        val token = generation.get()
        val first48 = ProgramFrames.to48k(start, asset.sampleRate)
        val last48 = ProgramFrames.to48k(end, asset.sampleRate)
        // Sub-sample native regions can collapse when normalized to 48 kHz. Refuse that empty region.
        if (last48 <= first48 || last48 > Int.MAX_VALUE) return false
        if (!ensureSource(asset, token)) return false
        val first = first48.toInt()
        val last = last48.toInt()
        val at = from * 48_000.0 / asset.sampleRate
        if (!warm(token, at.toLong())) return false
        return controls.withLock {
            token == generation.get() && command { frame, id -> EngineCommand.SetHandMonitorGain(frame, id, handMonitorGain) } &&
                token == generation.get() && command { frame, id -> EngineCommand.ScratchOriginalStart(frame, id, at, first, last) }
        }
    }
    /**
     * Moves the held original to [position] (its own frames, fractional) over [durationFrames] output frames, no faster
     * than eight times normal speed.
     */
    suspend fun scratchTo(position: Double, durationFrames: Int): Boolean {
        val asset = loaded.get() ?: return false
        val token = generation.get()
        // Preparation remains cancellable. A late seek cannot restart a hand already released.
        val current = driver.handPlayback().sourceFrame
        if (!warm(token, maxOf(0.0, current).toLong())) return false
        return controls.withLock { token == generation.get() && command { frame, id -> EngineCommand.ScratchOriginalPosition(frame, id, position * 48_000 / asset.sampleRate, durationFrames) } }
    }
    suspend fun scratchCut(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.ScratchOriginalCut(frame, id, gain) } }
    }
    /** Also cancels a pending load, so letting go cannot be followed by a late HAND start. SOURCE is untouched. */
    suspend fun scratchEnd(): Boolean {
        cancelPreparation()
        return controls.withLock { command { frame, id -> EngineCommand.ScratchOriginalEnd(frame, id) } }
    }
    private suspend fun warm(token: Long, frame: Long): Boolean {
        val port = pcm as? com.choplab.core.PrefetchPcmPort ?: return token == generation.get()
        val lease = loadedPcm.get()?.pcm?.tryAcquire() ?: return false
        val data = lease.pcm
        if (frame >= data.frameCount) { lease.close(); return token == generation.get() } // END never wraps.
        val job = jobs.async {
            port.prefetch(data, maxOf(0L, frame - 12_288).toInt(), minOf(data.frameCount.toLong(), frame + 12_288).toInt())
        }
        job.invokeOnCompletion { lease.close() }
        preparation.set(job)
        if (token != generation.get()) { preparation.compareAndSet(job, null); job.cancel(); return false }
        return try { job.await(); token == generation.get() } finally { preparation.compareAndSet(job, null); job.cancel() }
    }

    fun nativeHandFrame(): Double {
        val asset = loaded.get() ?: return -1.0
        val hand = driver.handPlayback().sourceFrame
        return if (hand < 0) -1.0 else (hand * asset.sampleRate / 48_000).coerceIn(0.0, asset.frames.toDouble())
    }
    fun nativeFrame(): Long {
        val asset = loaded.get() ?: return 0
        return (driver.originalPlayback().sourceFrame * asset.sampleRate / 48_000).coerceIn(0, asset.frames)
    }
    private suspend fun command(factory: (Long, Long) -> EngineCommand): Boolean =
        send(factory(driver.snapshot().frame, order.incrementAndGet()))

    override fun close() { cancelPreparation(); loadedSession = null; loaded.set(null); loadedPcm.getAndSet(null)?.close(); owner.cancel() }

    private object ProgramFrames {
        fun to48k(frame: Long, rate: Int): Long = (frame * 48_000 + rate - 1) / rate
    }
}
