package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.vocalCompanionPadIndicesForLoopStart
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternMasterParityTest {
    @Test
    fun sameGroupVocalExportMatchesRealtimeLoopOwnership() {
        val sampleRate = 8_000
        val loopAudio = PcmAudio(
            name = "loop-owner.wav",
            samples = ShortArray(512) { frame ->
                if (frame % 3 == 0) 9_000.toShort()
                else (-5_000 + frame * 17).coerceIn(-9_000, 9_000).toShort()
            },
            sampleRate = sampleRate,
        )
        val vocalAudio = PcmAudio(
            name = "conflicting-vocal.wav",
            samples = ShortArray(4_000) { 14_000 },
            sampleRate = sampleRate,
        )
        val loopOwner = PadModel(
            globalIndex = 0,
            audio = loopAudio,
            startFrame = 0,
            endFrame = loopAudio.frameCount,
            gain = 0.55f,
            playMode = PadPlayMode.LOOP,
            chokeGroup = 1,
        )
        val conflictingVocal = PadModel(
            globalIndex = 48,
            audio = vocalAudio,
            startFrame = 0,
            endFrame = vocalAudio.frameCount,
            gain = 0.65f,
            contentKind = PadContentKind.VOCAL,
            chokeGroup = 1,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                loopOwner.globalIndex -> loopOwner
                conflictingVocal.globalIndex -> conflictingVocal
                else -> PadModel(index)
            }
        }
        val output = File.createTempFile("choplab-choke-owner-parity", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = output,
                pads = pads,
                activeSteps = emptySet(),
                bpm = 120f,
                swing = 50f,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            val actual = readPcm16(output)
            val expected = renderRealtimeLoopSession(
                pads = pads,
                loopOwnerIndex = loopOwner.globalIndex,
                frameCount = summary.frameCount,
                sampleRate = sampleRate,
            )

            assertFullPcmParity(expected, actual, label = "same-group vocal ownership")
        } finally {
            output.delete()
        }
    }

    @Test
    fun otherGroupVocalExportMatchesRealtimeIntentionalLayering() {
        val sampleRate = 8_000
        val loopAudio = PcmAudio(
            name = "layer-loop.wav",
            samples = ShortArray(384) { frame -> (frame * 31 - 5_000).coerceIn(-8_000, 8_000).toShort() },
            sampleRate = sampleRate,
        )
        val vocalAudio = PcmAudio(
            name = "layer-vocal.wav",
            samples = ShortArray(3_000) { frame -> if (frame % 2 == 0) 5_000 else -3_000 },
            sampleRate = sampleRate,
        )
        val loopOwner = PadModel(
            globalIndex = 0,
            audio = loopAudio,
            startFrame = 0,
            endFrame = loopAudio.frameCount,
            gain = 0.45f,
            playMode = PadPlayMode.LOOP,
            chokeGroup = 1,
        )
        val layeredVocal = PadModel(
            globalIndex = 48,
            audio = vocalAudio,
            startFrame = 0,
            endFrame = vocalAudio.frameCount,
            gain = 0.4f,
            contentKind = PadContentKind.VOCAL,
            chokeGroup = 2,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                loopOwner.globalIndex -> loopOwner
                layeredVocal.globalIndex -> layeredVocal
                else -> PadModel(index)
            }
        }
        val output = File.createTempFile("choplab-choke-layer-parity", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = output,
                pads = pads,
                activeSteps = emptySet(),
                bpm = 120f,
                swing = 50f,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            val actual = readPcm16(output)
            val expected = renderRealtimeLoopSession(
                pads = pads,
                loopOwnerIndex = loopOwner.globalIndex,
                frameCount = summary.frameCount,
                sampleRate = sampleRate,
            )

            assertFullPcmParity(expected, actual, label = "other-group vocal layering")
        } finally {
            output.delete()
        }
    }

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

    private fun renderRealtimeLoopSession(
        pads: List<PadModel>,
        loopOwnerIndex: Int,
        frameCount: Int,
        sampleRate: Int,
    ): ShortArray {
        val activePadIndices = buildList {
            add(loopOwnerIndex)
            addAll(pads.vocalCompanionPadIndicesForLoopStart(loopOwnerIndex))
        }
        val voices = activePadIndices.map { padIndex ->
            SamplerEngine.Voice(SamplerEngine.PadSnapshot.from(pads[padIndex]), sampleRate)
        }
        return ShortArray(frameCount) {
            var mix = 0f
            voices.forEach { voice ->
                if (!voice.finished) mix += voice.render(sampleRate)
            }
            val limited = SamplerDspPrimitives.softLimit(mix)
            (limited.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun assertFullPcmParity(expected: ShortArray, actual: ShortArray, label: String) {
        assertEquals(expected.size, actual.size)
        val maximumDeltaFrame = actual.indices.maxBy { index ->
            kotlin.math.abs(actual[index].toInt() - expected[index].toInt())
        }
        val maximumDelta = kotlin.math.abs(
            actual[maximumDeltaFrame].toInt() - expected[maximumDeltaFrame].toInt(),
        )
        assertTrue(
            "$label delta $maximumDelta at frame $maximumDeltaFrame: " +
                "offline=${actual[maximumDeltaFrame]}, realtime=${expected[maximumDeltaFrame]}",
            maximumDelta <= 1,
        )
    }
}
