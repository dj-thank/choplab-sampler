package com.choplab.jvm.separation

import com.choplab.core.separation.SeparationProblem
import kotlinx.coroutines.runBlocking
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.*

class FourStemPipelineTest {
    @Test fun allHeadsRetainStereoHeadroomEdgesAndTheAbsoluteClockAcrossEveryOverlap() = runBlocking<Unit> {
        for (total in listOf(1, 31, 48, 64, 65, 112, 193, 20_001)) {
            var inputIdentity: FloatArray? = null
            var outputIdentity: FloatArray? = null
            val written = IntArray(4)
            var calls = 0
            fun source(frame: Int, channel: Int) = if (channel == 0) 1.2f + (frame % 19) * .001f else -.000001f * (frame % 13 + 1)
            val inference = object : FourStemInference {
                override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                    if (inputIdentity == null) inputIdentity = channelMajor else assertSame(inputIdentity, channelMajor)
                    val output = FloatArray(64 * 8) { i -> channelMajor[i % 128] * (i / 128 + 1) * .1f }
                    calls++; consume(FloatBuffer.wrap(output))
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
            var completed = 0
            FourStemPipeline(64, 16).render(total, read = { start, count, buffer ->
                for (frame in 0 until count) { buffer[frame] = source(start + frame, 0); buffer[64 + frame] = source(start + frame, 1) }
                assertTrue((count until 64).all { buffer[it] == 0f && buffer[64 + it] == 0f })
            }, inference, emit = { stem, buffer, count ->
                if (outputIdentity == null) outputIdentity = buffer else assertSame(outputIdentity, buffer)
                for (frame in 0 until count) for (channel in 0..1) {
                    val expected = source(written[stem] + frame, channel) * (stem + 1) * .1f
                    assertTrue(abs(expected - buffer[frame * 2 + channel]) < 2e-7, "stem=$stem frame=${written[stem] + frame} channel=$channel")
                }
                written[stem] += count
            }, check = {}, progress = { assertTrue(it > completed); completed = it })
            assertEquals(total, completed); assertTrue(written.all { it == total }); assertEquals((total + 47) / 48, calls)
        }
    }

    @Test fun invalidShapeNonfiniteOutputAndCancellationNeverEmitThatChunk() = runBlocking<Unit> {
        for (invalid in listOf("shape", "finite", "cancel")) {
            var emitted = 0
            val inference = object : FourStemInference {
                override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                    if (invalid == "cancel") throw SeparationException(SeparationProblem.CANCELLED)
                    consume(FloatBuffer.wrap(FloatArray(if (invalid == "shape") 20 else 64 * 8) { if (invalid == "finite") Float.NaN else 0f }))
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
            assertFailsWith<SeparationException> {
                FourStemPipeline(64, 16).render(100, { _, _, _ -> }, inference, { _, _, _ -> emitted++ }, {}, {})
            }
            assertEquals(0, emitted)
        }
    }
}
