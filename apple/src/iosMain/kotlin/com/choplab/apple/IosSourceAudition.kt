package com.choplab.apple

import com.choplab.core.PcmPort
import com.choplab.core.model.Asset
import com.choplab.core.model.FrameRange
import com.choplab.engine.EngineCommand
import com.choplab.engine.OriginalSource
import com.choplab.engine.PcmLease
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/**
 * Independent original (SOURCE) transport and HAND scratch, ported from the JVM hosts' SourceAuditionController.
 * It never replaces Studio's document program or PAD table. PCM here is resident, so no prefetch step is needed.
 */
internal class IosSourceAudition(private val driver: IosEnginePort, private val pcm: PcmPort, scope: CoroutineScope) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val generation = AtomicLong(0)
    private val order = AtomicLong(0)
    private val preparation = AtomicReference<Job?>(null)
    private val loaded = AtomicReference<Asset?>(null)
    private val loadedPcm = AtomicReference<PcmLease?>(null)
    private val controls = Mutex()
    @Volatile private var semitones = 0f
    @Volatile private var loadedLoop = false
    @Volatile private var loadedRange: FrameRange? = null
    @Volatile private var loadedSession: Any? = null
    @Volatile private var keyApplied = false
    @Volatile private var handMonitorGain = 1f

    fun cancelPreparation() {
        generation.incrementAndFetch()
        preparation.exchange(null)?.cancel()
    }

    private suspend fun ensureSource(asset: Asset, token: Long, loop: Boolean = false, range: FrameRange? = null): Boolean {
        require(range == null || range.end <= asset.frames)
        if (loaded.load() == asset && loadedLoop == loop && loadedRange == range && hasCurrentSource()) return true
        val delivery = AtomicReference<PcmLease?>(null)
        val job = jobs.async { pcm.acquire(asset).also { delivery.store(it); ensureActive() } }
        job.invokeOnCompletion { failure -> if (failure != null) delivery.exchange(null)?.close() }
        preparation.store(job)
        if (token != generation.load()) {
            preparation.compareAndSet(job, null); job.cancel(); delivery.exchange(null)?.close(); return false
        }
        try {
            val data = job.await().pcm
            val first = range?.let { ((it.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate).toInt() } ?: 0
            val last = range?.let { (it.end * 48_000 / asset.sampleRate).toInt() } ?: data.frameCount
            if (last <= first) return false
            return controls.withLock {
                if (token != generation.load()) return@withLock false
                val session = driver.sourceOutputSession() ?: return@withLock false
                loadedSession = null
                val accepted = command { frame, id -> EngineCommand.SetOriginalSource(frame, id, OriginalSource(data, first, last, loop = loop, loopCrossfadeFrames = if (loop) 480 else 0)) } &&
                    command { frame, id -> EngineCommand.SetOriginalPitch(frame, id, semitones) }
                val current = accepted && token == generation.load() && session === driver.sourceOutputSession()
                if (current) {
                    loaded.store(asset); loadedLoop = loop; loadedRange = range; loadedPcm.exchange(delivery.exchange(null))?.close(); keyApplied = true
                    loadedSession = session
                }
                current
            }
        } finally { preparation.compareAndSet(job, null); job.cancel(); delivery.exchange(null)?.close() }
    }

    private fun hasCurrentSource(): Boolean {
        val session = driver.sourceOutputSession() ?: return false
        if (loadedSession !== session) return false
        val present = when (val readout = driver.originalPlaybackProbe()) {
            is OriginalPlaybackProbe.Ready -> readout.playback.loaded
            OriginalPlaybackProbe.Contended -> true
            OriginalPlaybackProbe.Unavailable -> false
        }
        return present && session === driver.sourceOutputSession()
    }

    suspend fun play(asset: Asset, loop: Boolean = false, range: FrameRange? = null): Boolean {
        cancelPreparation()
        val token = generation.load()
        if (!ensureSource(asset, token, loop, range)) return false
        return controls.withLock {
            token == generation.load() && (keyApplied || sendKey()) && command { frame, id -> EngineCommand.PlayOriginalSource(frame, id) }
        }
    }
    suspend fun pause(): Boolean {
        cancelPreparation()
        return controls.withLock { command { frame, id -> EngineCommand.PauseOriginalSource(frame, id) } }
    }
    suspend fun clear(): Boolean {
        cancelPreparation()
        loadedSession = null
        loaded.store(null)
        loadedPcm.exchange(null)?.close()
        return controls.withLock { command { frame, id -> EngineCommand.SetOriginalSource(frame, id, null) } }
    }
    suspend fun seek(asset: Asset, nativeFrame: Long, loop: Boolean = false, range: FrameRange? = null): Boolean {
        require(nativeFrame in 0..asset.frames)
        require(range == null || nativeFrame in range.start..range.end)
        cancelPreparation()
        val token = generation.load()
        if (!ensureSource(asset, token, loop, range)) return false
        val frame48 = if (range == null) to48k(nativeFrame, asset.sampleRate) else
            to48k(nativeFrame, asset.sampleRate).coerceIn((range.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate, range.end * 48_000 / asset.sampleRate)
        return controls.withLock { token == generation.load() && command { frame, id -> EngineCommand.SeekOriginalSource(frame, id, frame48) } }
    }
    /** Varispeed for listening; kept for the next source even when no route takes it now. */
    suspend fun pitch(semitones: Float): Boolean {
        require(semitones.isFinite() && semitones in -24f..24f)
        return controls.withLock { this.semitones = semitones; sendKey() }
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
        return controls.withLock { handMonitorGain = gain; command { frame, id -> EngineCommand.SetHandMonitorGain(frame, id, gain) } }
    }
    /** HAND reads the original between [start] and [end] (native frames), from [from]; SOURCE continues on its own. */
    suspend fun scratchStart(asset: Asset, from: Long, start: Long, end: Long): Boolean {
        require(start in 0 until end && end <= asset.frames && from in start until end)
        cancelPreparation()
        val token = generation.load()
        val first48 = to48k(start, asset.sampleRate)
        val last48 = to48k(end, asset.sampleRate)
        if (last48 <= first48 || last48 > Int.MAX_VALUE) return false
        if (!ensureSource(asset, token)) return false
        val at = from * 48_000.0 / asset.sampleRate
        return controls.withLock {
            token == generation.load() && command { frame, id -> EngineCommand.SetHandMonitorGain(frame, id, handMonitorGain) } &&
                token == generation.load() && command { frame, id -> EngineCommand.ScratchOriginalStart(frame, id, at, first48.toInt(), last48.toInt()) }
        }
    }
    suspend fun scratchTo(position: Double, durationFrames: Int): Boolean {
        val asset = loaded.load() ?: return false
        val token = generation.load()
        return controls.withLock { token == generation.load() && command { frame, id -> EngineCommand.ScratchOriginalPosition(frame, id, position * 48_000 / asset.sampleRate, durationFrames) } }
    }
    suspend fun scratchCut(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.ScratchOriginalCut(frame, id, gain) } }
    }
    suspend fun scratchEnd(): Boolean {
        cancelPreparation()
        return controls.withLock { command { frame, id -> EngineCommand.ScratchOriginalEnd(frame, id) } }
    }

    fun nativeHandFrame(): Double {
        val asset = loaded.load() ?: return -1.0
        val hand = driver.handPlayback().sourceFrame
        return if (hand < 0) -1.0 else (hand * asset.sampleRate / 48_000).coerceIn(0.0, asset.frames.toDouble())
    }
    fun nativeFrame(): Long {
        val asset = loaded.load() ?: return 0
        return (driver.originalPlayback().sourceFrame * asset.sampleRate / 48_000).coerceIn(0, asset.frames)
    }
    private suspend fun command(factory: (Long, Long) -> EngineCommand): Boolean =
        driver.applyMonitoring(factory(driver.snapshot().frame, order.incrementAndFetch()))

    override fun close() { cancelPreparation(); loadedSession = null; loaded.store(null); loadedPcm.exchange(null)?.close(); owner.cancel() }

    private fun to48k(frame: Long, rate: Int): Long = (frame * 48_000 + rate - 1) / rate
}
