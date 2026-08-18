package com.choplab.sampler.audio

/** Generation gate for one system-audio capture session at a time. */
internal class PlaybackCaptureLifecycle {
    private enum class Phase { IDLE, STARTING, RECORDING, STOPPING }

    private var nextGeneration = 0L
    private var activeGeneration = 0L
    private var phase = Phase.IDLE

    @Synchronized
    fun beginStart(): Long? {
        if (phase != Phase.IDLE) return null
        nextGeneration = if (nextGeneration == Long.MAX_VALUE) 1L else nextGeneration + 1L
        activeGeneration = nextGeneration
        phase = Phase.STARTING
        return activeGeneration
    }

    @Synchronized
    fun markRecording(generation: Long): Boolean {
        if (generation != activeGeneration || phase != Phase.STARTING) return false
        phase = Phase.RECORDING
        return true
    }

    @Synchronized
    fun requestStop(generation: Long): Boolean {
        if (generation != activeGeneration || phase == Phase.IDLE) return false
        phase = Phase.STOPPING
        return true
    }

    @Synchronized
    fun finish(generation: Long): Boolean {
        if (generation != activeGeneration || phase == Phase.IDLE) return false
        activeGeneration = 0L
        phase = Phase.IDLE
        return true
    }
}

internal fun stopCaptureWorkerBounded(
    worker: Thread?,
    stopInput: () -> Unit,
    releaseInput: () -> Unit,
    stopTimeoutMillis: Long,
    releaseTimeoutMillis: Long,
): Boolean {
    runCatching(stopInput)
    if (awaitRecorderWorker(worker, stopTimeoutMillis)) return true
    runCatching(releaseInput)
    return awaitRecorderWorker(worker, releaseTimeoutMillis)
}
