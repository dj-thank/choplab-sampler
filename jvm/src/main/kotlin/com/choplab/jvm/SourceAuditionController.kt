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
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val generation = AtomicLong()
    private val order = AtomicLong()
    private val preparation = AtomicReference<Deferred<com.choplab.engine.PcmAsset>?>(null)
    private val loaded = AtomicReference<Asset?>(null)
    private val controls = Mutex()

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
                val accepted = command { frame, id -> EngineCommand.SetOriginalSource(frame, id, OriginalSource(data)) }
                if (accepted && token == generation.get()) loaded.set(asset)
                accepted && token == generation.get()
            }
        } finally { preparation.compareAndSet(job, null) }
    }

    suspend fun play(asset: Asset): Boolean {
        cancelPreparation()
        val token = generation.get()
        if (!ensureSource(asset, token)) return false
        return controls.withLock { token == generation.get() && command { frame, id -> EngineCommand.PlayOriginalSource(frame, id) } }
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
    suspend fun originalGain(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.SetOriginalMonitorGain(frame, id, gain) } }
    }
    suspend fun songGain(gain: Float): Boolean {
        require(gain.isFinite() && gain in 0f..1f)
        return controls.withLock { command { frame, id -> EngineCommand.SetSongMonitorGain(frame, id, gain) } }
    }
    fun nativeFrame(): Long {
        val asset = loaded.get() ?: return 0
        return (driver.originalPlayback().sourceFrame * asset.sampleRate / 48_000).coerceIn(0, asset.frames)
    }
    private suspend fun command(factory: (Long, Long) -> EngineCommand): Boolean =
        driver.applyMonitoring(factory(driver.snapshot().frame, order.incrementAndGet()))

    override fun close() { cancelPreparation(); loaded.set(null); owner.cancel() }

    private object ProgramFrames {
        fun to48k(frame: Long, rate: Int): Long = (frame * 48_000 + rate - 1) / rate
    }
}
