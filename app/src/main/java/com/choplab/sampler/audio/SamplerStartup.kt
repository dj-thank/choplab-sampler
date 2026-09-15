package com.choplab.sampler.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Prepare output and read recovery data concurrently, without blocking the UI thread.
 * The caller keeps its loading gate closed until both finish. On success the existing
 * owner takes over output shutdown. On cancellation/failure, join any in-flight open
 * before closing so a late AudioTrack creation cannot survive ViewModel teardown.
 */
internal suspend fun <T> prepareSamplerStartup(
    startAudio: () -> Unit,
    stopAudio: () -> Unit,
    loadProject: suspend () -> T,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): T = coroutineScope {
    val audioReady = async(dispatcher) { startAudio() }
    var handedOff = false
    try {
        val project = withContext(dispatcher) { loadProject() }
        audioReady.await()
        handedOff = true
        project
    } finally {
        if (!handedOff) {
            withContext(NonCancellable + dispatcher) {
                audioReady.cancel()
                audioReady.join()
                stopAudio()
            }
        }
    }
}
