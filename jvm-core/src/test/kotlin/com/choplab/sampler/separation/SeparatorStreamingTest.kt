package com.choplab.sampler.separation

import com.choplab.sampler.model.PcmAudio
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeparatorStreamingTest {
    private val segment = SeparatorSpec.SEGMENT_SAMPLES

    /** Deterministic, content-dependent fake model that mixes both channels into every stem. */
    private val nonlinear = ChunkInference { chunk ->
        val out = FloatArray(4 * 2 * segment)
        for (stem in 0 until 4) {
            for (ch in 0..1) {
                val base = (stem * 2 + ch) * segment
                val other = (1 - ch) * segment
                for (i in 0 until segment) {
                    out[base + i] = sin(chunk[ch * segment + i] * 3f + stem).toFloat() * 0.5f + chunk[other + i] * 0.1f
                }
            }
        }
        out
    }

    private fun randomAudio(frames: Int, rate: Int, channels: Int, seed: Long): PcmAudio {
        val random = Random(seed)
        return PcmAudio(
            name = "fixture",
            samples = ShortArray(frames * channels) { (random.nextInt(65536) - 32768).toShort() },
            sampleRate = rate,
            channelCount = channels,
        )
    }

    private fun streamed(audio: PcmAudio, infer: ChunkInference): Pair<FloatArray, FloatArray> {
        val reader = SeparatorSourceReader(audio)
        val left = FloatArray(reader.frames)
        val right = FloatArray(reader.frames)
        var cursor = 0
        DrumSeparatorPipeline.separateStreaming(reader, infer, emit = { l, r, count ->
            l.copyInto(left, cursor, 0, count)
            r.copyInto(right, cursor, 0, count)
            cursor += count
        })
        assertEquals(reader.frames, cursor)
        return left to right
    }

    @Test
    fun floatSourceKeepsSubPcm16PrecisionHeadroomAndChannelIdentity() {
        // The existing separation window tapers its first frame to zero; precision assertions use following frames.
        val samples = floatArrayOf(0f, 0f, 0.000001f, -0.000002f, 1.25f, -1.5f, 0.1234567f, -0.7654321f)
        val reader = SeparatorSourceReader(samples, 44_100, 2)
        val window = FloatArray(8)
        reader.read(0, 4, window, 4)
        assertArrayEquals(floatArrayOf(samples[0], samples[2], samples[4], samples[6],
            samples[1], samples[3], samples[5], samples[7]), window, 0f)
        var count = 0
        val passthrough = ChunkInference { chunk -> FloatArray(4 * chunk.size).also {
            chunk.copyInto(it, SeparatorSpec.DRUM_STEM_INDEX * chunk.size)
        } }
        DrumSeparatorPipeline.separateStreaming(reader, passthrough, emit = { left, right, size ->
            for (i in 0 until size) {
                assertEquals(samples[(count + i) * 2], left[i], 0.0000002f)
                assertEquals(samples[(count + i) * 2 + 1], right[i], 0.0000002f)
            }
            count += size
        })
        assertEquals(4, count)
    }

    @Test
    fun floatSourceRejectsInvalidPcmAndDuplicatesMonoWithoutQuantization() {
        for ((samples, rate, channels) in listOf(
            Triple(floatArrayOf(Float.NaN), 44_100, 1), Triple(floatArrayOf(Float.POSITIVE_INFINITY), 44_100, 1),
            Triple(floatArrayOf(1f), 44_100, 2), Triple(floatArrayOf(), 44_100, 1),
            Triple(floatArrayOf(1f), 0, 1), Triple(floatArrayOf(1f), 44_100, 3))) {
            try { SeparatorSourceReader(samples, rate, channels); fail("Invalid source accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val samples = floatArrayOf(0.000001f, -1.25f)
        val window = FloatArray(4)
        SeparatorSourceReader(samples, 44_100, 1).read(0, 2, window, 2)
        assertArrayEquals(samples + samples, window, 0f)
    }

    @Test
    fun streamingSeparationMatchesTheWholeBufferPipelineBitForBit() {
        // Three chunks, including a short final chunk and a non-final chunk shorter than a segment.
        val audio = randomAudio(frames = SeparatorSpec.STRIDE_SAMPLES * 2 + 12_345, rate = 44_100, channels = 2, seed = 7)
        val (mix, frames) = SeparatorDsp.pcmToFloatStereo44100(audio)
        val whole = DrumSeparatorPipeline.separate(mix, frames, nonlinear)
        val (left, right) = streamed(audio, nonlinear)
        assertArrayEquals(whole.copyOfRange(0, frames), left, 0f)
        assertArrayEquals(whole.copyOfRange(frames, 2 * frames), right, 0f)
    }

    @Test
    fun resampledMonoReaderMatchesTheWholeSourceConversion() {
        val audio = randomAudio(frames = 57_600, rate = 48_000, channels = 1, seed = 11)
        val (mix, frames) = SeparatorDsp.pcmToFloatStereo44100(audio)
        val reader = SeparatorSourceReader(audio)
        assertEquals(frames, reader.frames)
        val window = FloatArray(2 * segment)
        reader.read(0, frames, window, segment)
        assertArrayEquals(mix.copyOfRange(0, frames), window.copyOfRange(0, frames), 0f)
        assertArrayEquals(mix.copyOfRange(frames, 2 * frames), window.copyOfRange(segment, segment + frames), 0f)
        val whole = DrumSeparatorPipeline.separate(mix, frames, nonlinear)
        val (left, right) = streamed(audio, nonlinear)
        assertArrayEquals(whole.copyOfRange(0, frames), left, 0f)
        assertArrayEquals(whole.copyOfRange(frames, 2 * frames), right, 0f)
    }

    @Test
    fun streamingCancellationStopsBeforeTheNextChunk() {
        val audio = randomAudio(frames = segment * 2, rate = 44_100, channels = 2, seed = 3)
        var calls = 0
        var cancelled = false
        try {
            DrumSeparatorPipeline.separateStreaming(
                SeparatorSourceReader(audio),
                ChunkInference { chunk -> calls++; cancelled = true; FloatArray(4 * chunk.size) },
                emit = { _, _, _ -> },
                isCancelled = { cancelled },
            )
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun pcmConversionHelperMatchesTheWholeBufferConversion() {
        val values = floatArrayOf(-2f, -1f, -0.5f, 0f, 0.25f, 0.99999f, 1f, 3f)
        val pcm = SeparatorDsp.floatStereoToPcm16(values + values, values.size, "x")
        values.forEachIndexed { index, value ->
            assertEquals(pcm.samples[2 * index], SeparatorDsp.toPcm16(value))
        }
        assertTrue(SeparatorDsp.toPcm16(3f) == Short.MAX_VALUE)
    }
}
