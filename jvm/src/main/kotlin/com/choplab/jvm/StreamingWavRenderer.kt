package com.choplab.jvm

import com.choplab.engine.*
import com.choplab.core.model.ProjectLimits
import java.io.OutputStream
import java.util.concurrent.CancellationException

data class StreamingRenderStats(val outputFrames: Long, val renderedFrames: Long, val floatBufferBytes: Int, val quantizerBufferBytes: Int, val latencyFrames: Int)

/** Worker-side export. Storage is O(blockFrames), independent of the selected song length. */
object StreamingWavRenderer {
    fun render(
        program: EngineProgram,
        output: OutputStream,
        frames: Int,
        tailFrames: Int = 0,
        bits: Int = 24,
        seed: Int = 1,
        blockFrames: Int = 480,
        cancelled: () -> Boolean = { false },
    ): StreamingRenderStats {
        require(frames.toLong() in 1..ProjectLimits.MAX_TIMELINE_FRAMES && tailFrames in 0..480_000 && blockFrames in 1..65_536)
        val engine = EngineCore(program, EngineConfig(controlCapacity = 4, eventCapacity = 8, outputMode = EngineOutputMode.EXPORT))
        require(engine.controls.offer(EngineCommand.StartSequence(0, 1)) == OfferResult.ACCEPTED)
        require(engine.controls.offer(EngineCommand.Stop(frames.toLong(), 2)) == OfferResult.ACCEPTED)
        val latency = engine.latencyFrames
        val outputFrames = frames.toLong() + tailFrames
        val writer = WavCodec.PcmWriter(output, outputFrames, bits = bits, seed = seed, bufferFrames = blockFrames)
        val buffer = FloatArray(blockFrames * 2)
        val total = outputFrames + latency
        var rendered = 0L
        while (rendered < total) {
            if (cancelled()) throw CancellationException("WAV export cancelled")
            val count = minOf(blockFrames.toLong(), total - rendered).toInt()
            engine.render(buffer, frameCount = count)
            val skipped = minOf(count.toLong(), (latency - rendered).coerceAtLeast(0)).toInt()
            if (count > skipped) writer.write(buffer, skipped, count - skipped)
            rendered += count
        }
        if (cancelled()) throw CancellationException("WAV export cancelled")
        writer.finish()
        return StreamingRenderStats(outputFrames, rendered, buffer.size * 4, writer.bufferBytes, latency)
    }
}
