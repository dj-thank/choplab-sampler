package com.choplab.desktop.next

import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavCodec
import com.choplab.sampler.separation.ChunkInference
import com.choplab.sampler.separation.DrumSeparatorPipeline
import com.choplab.sampler.separation.SeparatorSourceReader
import com.choplab.sampler.separation.SeparatorSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException

/** Worker-only float route. The caller owns the model session and an exclusive scratch destination. */
internal object NextDrumSeparation {
    fun render(audio: WavAudio, destination: Path, inference: ChunkInference,
               progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        checkCancelled()
        require(audio.info.channels in 1..2)
        require(audio.info.frames == audio.samples.size.toLong() / audio.info.channels)
        val reader = SeparatorSourceReader(audio.samples, audio.info.sampleRate, audio.info.channels)
        require(reader.frames.toLong() <= SeparatorSpec.SAMPLE_RATE.toLong() * SeparatorSpec.MAX_JOB_SECONDS)
        // CREATE_NEW prevents failure cleanup from ever removing a caller's pre-existing file.
        val output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            output.use {
                val writer = WavCodec.FloatWriter(it, reader.frames.toLong(), SeparatorSpec.SAMPLE_RATE)
                val interleaved = FloatArray(2 * SeparatorSpec.SEGMENT_SAMPLES)
                DrumSeparatorPipeline.separateStreaming(reader, inference, emit = { left, right, count ->
                    checkCancelled()
                    for (i in 0 until count) {
                        interleaved[2 * i] = left[i]
                        interleaved[2 * i + 1] = right[i]
                    }
                    writer.write(interleaved, frameCount = count)
                }, onProgress = progress, isCancelled = { cancelled() || Thread.currentThread().isInterrupted })
                checkCancelled()
                writer.finish()
            }
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(destination) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
