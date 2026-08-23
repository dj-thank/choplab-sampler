package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternMasterParityTest {
    @Test
    fun singleEventFullBarMatchesRealtimeVoiceAndSharedMasterLimiter() {
        val sampleRate = 8_000
        val audio = PcmAudio(
            name = "full-bar-parity.wav",
            samples = ShortArray(512) { frame ->
                when {
                    frame % 5 == 0 -> 12_000
                    frame % 3 == 0 -> -9_000
                    else -> (frame * 29 - 6_000).coerceIn(-12_000, 12_000).toShort()
                }
            },
            sampleRate = sampleRate,
        )
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 16,
            endFrame = 496,
            pitchSemitones = 3f,
            tone = 0.35f,
            gain = 0.7f,
            reverse = true,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index -> if (index == 0) pad else PadModel(index) }
        val output = File.createTempFile("choplab-pattern-master-parity", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = output,
                pads = pads,
                activeSteps = setOf(stepKey(0, 0)),
                bpm = 120f,
                swing = 60f,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            val actual = readPcm16(output)
            val voice = SamplerEngine.Voice(SamplerEngine.PadSnapshot.from(pad), sampleRate)
            val expected = ShortArray(summary.frameCount) {
                val mix = if (voice.finished) 0f else voice.render(sampleRate)
                val limited = SamplerDspPrimitives.softLimit(mix)
                (limited.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }

            assertEquals(summary.frameCount, actual.size)
            val maximumDeltaFrame = actual.indices.maxBy { index ->
                kotlin.math.abs(actual[index].toInt() - expected[index].toInt())
            }
            val maximumDelta = kotlin.math.abs(
                actual[maximumDeltaFrame].toInt() - expected[maximumDeltaFrame].toInt(),
            )
            assertTrue(
                "Full-bar delta $maximumDelta at frame $maximumDeltaFrame: " +
                    "offline=${actual[maximumDeltaFrame]}, realtime=${expected[maximumDeltaFrame]}",
                maximumDelta <= 1,
            )
            assertEquals(
                expected.indexOfLast { it != 0.toShort() },
                actual.indexOfLast { it != 0.toShort() },
            )
        } finally {
            output.delete()
        }
    }

    private fun readPcm16(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray((bytes.size - 44) / Short.SIZE_BYTES) { index ->
            ByteBuffer.wrap(bytes, 44 + index * Short.SIZE_BYTES, Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
        }
    }
}
