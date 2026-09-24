package com.choplab.desktop.separation

import com.choplab.sampler.separation.*
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DrumSeparatorPipelineTest {
    private val segment = SeparatorSpec.SEGMENT_SAMPLES
    private val stride = SeparatorSpec.STRIDE_SAMPLES

    /** Pass-through backend: drums stem echoes the input chunk. */
    private val identity = ChunkInference { chunk ->
        FloatArray(4 * 2 * segment).also { out ->
            chunk.copyInto(out, SeparatorSpec.DRUM_STEM_INDEX * 2 * segment)
        }
    }

    @Test
    fun overlapAddReconstructsTheMixAwayFromEdges() {
        val frames = segment + stride + 1000
        val mix = FloatArray(2 * frames) { i ->
            val frame = i % frames
            (0.6 * sin(2.0 * Math.PI * 440.0 * frame / 44100.0)).toFloat()
        }
        val progress = mutableListOf<Float>()
        val out = DrumSeparatorPipeline.separate(mix, frames, identity, onProgress = progress::add)
        assertEquals(2 * frames, out.size)
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 1e-6f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        val margin = SeparatorSpec.OVERLAP_SAMPLES
        var worst = 0f
        for (ch in 0..1) {
            for (i in margin until frames - margin) {
                worst = maxOf(worst, abs(out[ch * frames + i] - mix[ch * frames + i]))
            }
        }
        assertTrue(worst < 1e-3f, "worst=$worst")
    }

    @Test
    fun shortMixBelowOneSegmentStillReconstructs() {
        val frames = 5000
        val mix = FloatArray(2 * frames) { 0.25f }
        val out = DrumSeparatorPipeline.separate(mix, frames, identity)
        for (ch in 0..1) {
            for (i in 1000 until frames - 1000) {
                assertEquals(0.25f, out[ch * frames + i], 1e-3f)
            }
        }
    }

    @Test
    fun cancellationAbortsBetweenChunks() {
        val frames = segment + stride + 10
        val mix = FloatArray(2 * frames)
        var calls = 0
        assertFailsWith<CancellationException> {
            DrumSeparatorPipeline.separate(mix, frames, identity, isCancelled = { ++calls > 1 })
        }
        assertTrue(calls >= 1)
    }

    @Test
    fun emptyMixIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DrumSeparatorPipeline.separate(FloatArray(0), 0, identity)
        }
    }
}
