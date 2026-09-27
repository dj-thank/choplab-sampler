package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.model.Asset
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
    private val preparation = AtomicReference<Deferred<com.choplab.engine.PcmAsset>?>(null)
    private val loaded = AtomicReference<Asset?>(null)
    private val controls = Mutex()
    /** The song key the original should play at; sent again with every source, so a rebuilt output keeps it. */
    @Volatile private var semitones = 0f
    /** Whether the loaded original took [semitones]; a key the output refused is sent again before the next play. */
    @Volatile private var keyApplied = false
    @Volatile private var handMonitorGain = 1f

    fun cancelPreparation() {
        generation.incrementAndGet()
        preparation.getAndSet(null)?.cancel()
    }

    private suspend fun ensureSource(asset: Asset, token: Long): Boolean {
        if (loaded.get() == asset && driver.originalPlayback().loaded) return true
        val job = jobs.async { pcm.load(asset) }
        preparation.set(job)
        if (token != generation.get()) { preparation.compareAndSet(job, null); job.cancel(); return false }
        try {
            val data = job.await()
            return controls.withLock {
                if (token != generation.get()) return@withLock false
                val accepted = command { frame, id -> EngineCommand.SetOriginalSource(frame, id, OriginalSource(data)) } &&
                    command { frame, id -> EngineCommand.SetOriginalPitch(frame, id, semitones) }
                if (accepted && token == generation.get()) { loaded.set(asset); keyApplied = true }
                accepted && token == generation.get()
            }
        } finally { preparation.compareAndSet(job, null) }
    }

    suspend fun play(asset: Asset): Boolean {
        cancelPreparation()
        val token = generation.get()
        if (!ensureSource(asset, token)) return false
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
        loaded.set(null)
        return controls.withLock { command { frame, id -> EngineCommand.SetOriginalSource(frame, id, null) } }
    }
    suspend fun seek(asset: Asset, nativeFrame: Long): Boolean {
        require(nativeFrame in 0..asset.frames)
        cancelPreparation()
        val token = generation.get()
        if (!ensureSource(asset, token)) return false
        val frame48 = ProgramFrames.to48k(nativeFrame, asset.sampleRate)
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
        return controls.withLock { command { frame, id -> EngineCommand.ScratchOriginalPosition(frame, id, position * 48_000 / asset.sampleRate, durationFrames) } }
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

    override fun close() { cancelPreparation(); loaded.set(null); owner.cancel() }

    private object ProgramFrames {
        fun to48k(frame: Long, rate: Int): Long = (frame * 48_000 + rate - 1) / rate
    }
}
