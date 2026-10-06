package com.choplab.jvm

import com.choplab.core.chop.*
import com.choplab.core.model.*
import com.choplab.engine.PagedPcm
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** All returned windows are covered until consumed by the host's one shared PCM reservation. */
class AutoChopWorker(private val pcm: WavPcmPort) : AutoChopPort {
    private val slot = Mutex()
    override suspend fun prepare(asset: Asset, range: FrameRange, settings: AutoChopSettings): AutoChopResult = withContext(Dispatchers.Default) {
        if (!slot.tryLock()) return@withContext AutoChopResult.Refused(AutoChopProblem.BUSY)
        try {
            require(range.end <= asset.frames)
            if (settings.mode == AutoChopMode.EQUAL) return@withContext AutoChop.equal(range, settings.slices)
            val first = (range.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate
            val end = range.end * 48_000 / asset.sampleRate
            if (end - first < settings.minimumGapMs * 96L) return@withContext AutoChopResult.Refused(AutoChopProblem.TOO_SHORT)
            try {
                pcm.acquire(asset).use { lease ->
                    pcm.memory.reserve(WINDOW_BYTES).use {
                        val detector = AttackChopDetector(first, end, settings)
                        val context = currentCoroutineContext()
                        var from = first.toInt()
                        require(end <= lease.pcm.frameCount)
                        while (from < end) {
                            context.ensureActive()
                            val until = minOf(end.toInt(), from + PagedPcm.PAGE_FRAMES)
                            detector.accept(pcm.readWindow(lease.pcm, from, until)) { context.ensureActive() }
                            from = until
                        }
                        context.ensureActive()
                        when (val result = detector.finish()) {
                            is AutoChopResult.Ready -> AutoChopResult.Ready(result.markers.map {
                                // A cut never leaves the selected native range. At 96 kHz the nearest representable
                                // analysis frame is 2 native samples; no new resampling or audio rewrite is performed.
                                (it * asset.sampleRate / 48_000).coerceIn(range.start + 1, range.end - 1)
                            }.distinct().sorted().frozen())
                            is AutoChopResult.Refused -> result
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: PcmMemoryLimit) { AutoChopResult.Refused(AutoChopProblem.NO_MEMORY) }
            catch (_: Exception) { AutoChopResult.Refused(AutoChopProblem.INVALID_AUDIO) }
        } finally { slot.unlock() }
    }
    companion object { const val WINDOW_BYTES = 4096 * 8L + 4096L }
}
