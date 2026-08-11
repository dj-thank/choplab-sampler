package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternRendererTest {
    @Test
    fun vocalTakeRendersOnceFromTheStartWithoutStepEvents() {
        val sampleRate = 8_000
        val vocal = ShortArray(2_000) { 12_000 }
        val audio = PcmAudio(name = "voice-take", samples = vocal, sampleRate = sampleRate)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 48) {
                PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = vocal.size,
                    contentKind = PadContentKind.VOCAL,
                )
            } else {
                PadModel(index)
            }
        }
        val file = File.createTempFile("choplab-vocal", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = emptySet(),
                bpm = 120f,
                swing = 50f,
                bars = 1,
                outputSampleRate = sampleRate,
            )

            assertTrue(summary.peak > 0f)
            val pcm = file.readBytes().copyOfRange(44, 44 + vocal.size * Short.SIZE_BYTES)
            assertTrue(pcm.any { it.toInt() != 0 })
        } finally {
            file.delete()
        }
    }

    @Test
    fun wholeChopLoopRendersContinuouslyWithoutBeatGridEvents() {
        val sampleRate = 8_000
        val sample = ShortArray(256) { 16_000 }
        val audio = PcmAudio(name = "beat-loop", samples = sample, sampleRate = sampleRate)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) {
                PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 0,
                    endFrame = sample.size,
                    playMode = PadPlayMode.LOOP,
                )
            } else {
                PadModel(index)
            }
        }
        val file = File.createTempFile("choplab-loop", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = emptySet(),
                bpm = 120f,
                swing = 50f,
                bars = 1,
                outputSampleRate = sampleRate,
            )

            assertTrue(summary.peak > 0f)
            val bytes = file.readBytes()
            val finalQuarter = bytes.copyOfRange(44 + summary.frameCount * 3 / 2, bytes.size)
            assertTrue(finalQuarter.any { it.toInt() != 0 })
        } finally {
            file.delete()
        }
    }

    @Test
    fun rendersOneBarWithExpectedTempoLength() {
        val sampleRate = 48_000
        val sample = ShortArray(4_800) { frame ->
            if (frame < 240) (Short.MAX_VALUE * (1f - frame / 240f)).toInt().toShort() else 0
        }
        val audio = PcmAudio(name = "hit", samples = sample, sampleRate = sampleRate)
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) {
                PadModel(
                    globalIndex = 0,
                    audio = audio,
                    startFrame = 0,
                    endFrame = sample.size,
                    pitchSemitones = 2f,
                    tone = 0.75f,
                    reverse = true,
                    chokeGroup = 1,
                )
            } else {
                PadModel(index)
            }
        }
        val file = File.createTempFile("choplab-pattern", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = setOf(stepKey(0, 0), stepKey(0, 4), stepKey(0, 8), stepKey(0, 12)),
                bpm = 120f,
                swing = 60f,
                bars = 1,
                outputSampleRate = sampleRate,
            )

            assertEquals(96_000, summary.frameCount)
            assertTrue(summary.peak > 0f)
            val bytes = file.readBytes()
            assertEquals(summary.frameCount * Short.SIZE_BYTES, littleEndianInt(bytes, 40))
            assertEquals(44 + summary.frameCount * Short.SIZE_BYTES, bytes.size)
        } finally {
            file.delete()
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
}
