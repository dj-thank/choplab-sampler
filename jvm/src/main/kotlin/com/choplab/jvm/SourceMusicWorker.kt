package com.choplab.jvm

import com.choplab.core.analysis.*
import com.choplab.core.model.Asset
import com.choplab.core.model.FrameRange
import com.choplab.engine.PagedPcm
import kotlinx.coroutines.*

/** Shares the host PCM budget and verified decoder; no second full-source audio array is created. */
internal suspend fun analyseSourceMusic(pcm: WavPcmPort, asset: Asset, range: FrameRange): SourceMusicResult = withContext(Dispatchers.Default) {
    require(range.end <= asset.frames && range.length <= asset.sampleRate.toLong() * SourceMusicAnalysis.MAX_SECONDS)
    val first = ((range.start * SourceMusicAnalysis.RATE + asset.sampleRate - 1) / asset.sampleRate).toInt()
    val end = (range.end * SourceMusicAnalysis.RATE / asset.sampleRate).toInt()
    val context = currentCoroutineContext()
    pcm.acquire(asset).use { lease ->
        require(end <= lease.pcm.frameCount)
        pcm.memory.reserve(SourceMusicAnalysis.WORK_BYTES).use {
            val analysis = SourceMusicAnalysis()
            var from = first
            while (from < end) {
                context.ensureActive()
                val until = minOf(end, from + PagedPcm.PAGE_FRAMES)
                analysis.accept(pcm.readWindow(lease.pcm, from, until)) { context.ensureActive() }
                from = until
            }
            analysis.finish { context.ensureActive() }
        }
    }
}
