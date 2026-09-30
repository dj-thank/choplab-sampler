package com.choplab.jvm

import com.choplab.core.StemSampleFormat
import com.choplab.core.StemHeadroomExceeded
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.*
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** One live/export graph, all requested post-insert stems, bounded worker storage, no master applied twice. */
object StreamingStemRenderer {
    fun render(program: EngineProgram, outputs: List<Pair<Int, OutputStream>>, frames: Int, tailFrames: Int,
        format: StemSampleFormat = StemSampleFormat.FLOAT32, seed: Int = 1, blockFrames: Int = 480,
        cancelled: () -> Boolean = { false },
        prepared: (List<PcmWindow>, () -> Unit) -> Unit = { windows, render -> require(windows.isEmpty()); render() },
        progress: (Long) -> Unit = {}): Long {
        require(frames.toLong() in 1..ProjectLimits.MAX_TIMELINE_FRAMES && tailFrames in 0..MixerProgram.MAX_TAIL_FRAMES)
        require(blockFrames in 1..4096 && outputs.size in 1..MixerProgram.STEM_COUNT &&
            outputs.map { it.first }.distinct().size == outputs.size && outputs.all { it.first in 0 until MixerProgram.STEM_COUNT })
        val bytes = MixerDsp.PCM_BYTES + blockFrames * (MixerProgram.STEM_COUNT + 2L) * 8 +
            outputs.size * maxOf(16_384L, blockFrames * 8L) + 8192
        runBlocking { PcmMemoryBudget.shared.reserve(bytes) }.use {
            val engine = EngineCore(program, EngineConfig(controlCapacity = 4, eventCapacity = 8, outputMode = EngineOutputMode.EXPORT))
            try {
                require(engine.controls.offer(EngineCommand.StartSequence(0, 1)) == OfferResult.ACCEPTED)
                require(engine.controls.offer(EngineCommand.Stop(frames.toLong(), 2)) == OfferResult.ACCEPTED)
                val total = frames.toLong() + tailFrames
                val writers = outputs.map { (bus, output) ->
                    if (format == StemSampleFormat.FLOAT32) {
                        val writer = WavCodec.FloatWriter(output, total, assetBounded = false)
                        Writer(bus, writer::write, writer::finish)
                    } else {
                        val writer = WavCodec.PcmWriter(output, total, bits = format.bits, seed = seed + bus, bufferFrames = blockFrames)
                        Writer(bus, writer::write, writer::finish)
                    }
                }
                val master = FloatArray(blockFrames * 2)
                val stems = FloatArray(blockFrames * MixerProgram.STEM_COUNT * 2)
                val window = FloatArray(blockFrames * 2)
                var written = 0L
                while (written < total) {
                    if (cancelled()) throw CancellationException("Stem export cancelled")
                    val plan = engine.prepareOfflineBlock(minOf(blockFrames.toLong(), total - written).toInt())
                    prepared(plan.windows) { engine.render(master, frameCount = plan.frames, stemOutput = stems) }
                    check(engine.pcmUnderrunFrames == 0L) { "PCM missing during stem export" }
                    for (writer in writers) {
                        for (frame in 0 until plan.frames) {
                            val at = (frame * MixerProgram.STEM_COUNT + writer.bus) * 2
                            val left = stems[at]; val right = stems[at + 1]
                            require(left.isFinite() && right.isFinite()) { "Non-finite stem" }
                            if (format != StemSampleFormat.FLOAT32 && (abs(left) >= 1f || abs(right) >= 1f))
                                throw StemHeadroomExceeded(writer.bus, written + frame, maxOf(abs(left), abs(right)))
                            window[frame * 2] = left; window[frame * 2 + 1] = right
                        }
                        writer.write(window, 0, plan.frames)
                    }
                    written += plan.frames
                    progress(written)
                }
                if (cancelled()) throw CancellationException("Stem export cancelled")
                writers.forEach { it.finish() }
                return total
            } finally { engine.close() }
        }
    }
    private class Writer(val bus: Int, val write: (FloatArray, Int, Int) -> Unit, val finish: () -> Unit)
}
