package com.choplab.desktop.separation

import com.choplab.sampler.separation.*
import com.choplab.sampler.model.PcmAudio
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeparatorDspTest {
    @Test
    fun resampleKeepsSameRateUntouched() {
        val input = FloatArray(64) { it.toFloat() }
        val output = SeparatorDsp.resample(input, 44100, 44100)
        assertTrue(output.contentEquals(input))
        assertTrue(output !== input)
    }

    @Test
    fun resamplePreservesDcLevelAcrossRates() {
        val input = FloatArray(4000) { 0.5f }
        val output = SeparatorDsp.resample(input, 48000, 44100)
        assertEquals((4000L * 44100 / 48000).toInt(), output.size)
        val mean = output.average().toFloat()
        assertTrue(abs(mean - 0.5f) < 0.01f, "mean=$mean")
    }

    @Test
    fun resamplePreservesToneFrequency() {
        val rate = 48000
        val input = FloatArray(rate) { sin(2.0 * Math.PI * 440.0 * it / rate).toFloat() }
        val output = SeparatorDsp.resample(input, rate, SeparatorSpec.SAMPLE_RATE)
        fun crossings(signal: FloatArray): Int {
            var count = 0
            for (i in 1 until signal.size) {
                if ((signal[i - 1] < 0f) != (signal[i] < 0f)) count++
            }
            return count
        }
        // 440 Hz over 1 s => ~880 zero crossings regardless of sample rate.
        assertTrue(abs(crossings(output) - 880) <= 4, "crossings=${crossings(output)}")
    }

    @Test
    fun pcmConversionProducesStereo44100() {
        val mono8k = PcmAudio(name = "m", samples = ShortArray(800) { 1000 }, sampleRate = 8000, channelCount = 1)
        val (stereo, frames) = SeparatorDsp.pcmToFloatStereo44100(mono8k)
        assertEquals(4410, frames)
        assertEquals(2 * frames, stereo.size)
        assertTrue(abs(stereo[0] - 1000f / 32768f) < 0.01f)
        assertEquals(stereo[0], stereo[frames], 1e-6f)
    }

    @Test
    fun pcmConversionKeepsStereoIdentity() {
        val samples = ShortArray(2 * 44100) { if (it % 2 == 0) 3000 else -3000 }
        val audio = PcmAudio(name = "s", samples = samples, sampleRate = 44100, channelCount = 2)
        val (stereo, frames) = SeparatorDsp.pcmToFloatStereo44100(audio)
        assertEquals(44100, frames)
        assertTrue(stereo[0] > 0f && stereo[frames] < 0f)
    }

    @Test
    fun transitionWindowMatchesReferenceFades() {
        val window = SeparatorDsp.transitionWindow(100, 25)
        assertEquals(100, window.size)
        assertEquals(0f, window[0])
        assertEquals(0f, window[99])
        assertEquals(1f, window[25])
        assertEquals(1f, window[50])
        for (i in window.indices) {
            assertEquals(window[i], window[99 - i], 1e-6f)
        }
    }

    @Test
    fun chunkStartsCoverEverySample() {
        assertEquals(listOf(0, 3, 6, 9), SeparatorDsp.chunkStarts(10, 4, 3))
        assertEquals(listOf(0), SeparatorDsp.chunkStarts(100, 343980, 257985))
    }

    @Test
    fun floatToPcmClipsAndInterleaves() {
        // Channel-major [ch0: 1.5, -1.5], [ch1: 0.5, -0.25].
        val stereo = floatArrayOf(1.5f, -1.5f, 0.5f, -0.25f)
        val pcm = SeparatorDsp.floatStereoToPcm16(stereo, 2, "drums")
        assertEquals(2, pcm.channelCount)
        assertEquals(SeparatorSpec.SAMPLE_RATE, pcm.sampleRate)
        assertEquals(32767.toShort(), pcm.samples[0])
        assertEquals((0.5f * 32767f).roundToInt().toShort(), pcm.samples[1])
        assertEquals((-32767).toShort(), pcm.samples[2])
        assertEquals((-0.25f * 32767f).roundToInt().toShort(), pcm.samples[3])
    }
}
