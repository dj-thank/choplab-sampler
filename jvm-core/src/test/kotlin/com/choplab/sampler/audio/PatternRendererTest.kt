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
            val pcm = readPcm16(file)
            assertTrue(pcm[60].toInt() in 7_500..9_000)
            assertTrue(pcm.copyOfRange(100, 1_900).all { it == pcm[100] })
            assertTrue(pcm.copyOfRange(vocal.size, pcm.size).all { it.toInt() == 0 })
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
            val pcm = readPcm16(file)
            listOf(64, summary.frameCount / 4, summary.frameCount / 2, summary.frameCount - 128).forEach { frame ->
                assertWindowHasEnergy(pcm, frame)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun eventFramesFollowStraightAndSwungSixteenthTiming() {
        val sampleRate = 8_000
        val hit = ShortArray(256) { 16_000 }
        val pad = PadModel(
            globalIndex = 0,
            audio = PcmAudio(name = "timing-hit", samples = hit, sampleRate = sampleRate),
            startFrame = 0,
            endFrame = hit.size,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { if (it == 0) pad else PadModel(it) }
        val active = setOf(stepKey(0, 0), stepKey(0, 1), stepKey(0, 4), stepKey(0, 8), stepKey(0, 12))

        val straight = renderPcm(pads, active, sampleRate, swing = 50f)
        val swung = renderPcm(pads, active, sampleRate, swing = 60f)

        listOf(0, 1_000, 4_000, 8_000, 12_000).forEach { onset ->
            assertWindowHasEnergy(straight, onset + 48)
        }
        listOf(0, 1_200, 4_000, 8_000, 12_000).forEach { onset ->
            assertWindowHasEnergy(swung, onset + 48)
        }
        assertTrue(straight.copyOfRange(1_040, 1_160).any { kotlin.math.abs(it.toInt()) > 2_000 })
        assertTrue(swung.copyOfRange(1_040, 1_160).all { kotlin.math.abs(it.toInt()) < 200 })
    }

    @Test
    fun fractionalTempoMatchesRealtimeCeilBoundariesAcrossBars() {
        val sampleRate = 48_000
        val hit = ShortArray(64) { 16_000 }
        val pad = PadModel(
            globalIndex = 0,
            audio = PcmAudio(name = "fractional-timing-hit", samples = hit, sampleRate = sampleRate),
            startFrame = 0,
            endFrame = hit.size,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { if (it == 0) pad else PadModel(it) }
        val file = File.createTempFile("choplab-fractional-timing", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = setOf(stepKey(0, 1)),
                bpm = 92f,
                swing = 54f,
                bars = 4,
                outputSampleRate = sampleRate,
            )
            val pcm = readPcm16(file)

            assertEquals(500_870, summary.frameCount)
            assertEquals(summary.frameCount, pcm.size)
            listOf(8_453, 133_670, 258_887, 384_105).forEach { eventFrame ->
                assertEquals("frame before event $eventFrame", 0, pcm[eventFrame - 1].toInt())
                assertEquals("event boundary $eventFrame", 0, pcm[eventFrame].toInt())
                assertTrue(
                    "Expected energy after event $eventFrame",
                    kotlin.math.abs(pcm[eventFrame + 1].toInt()) > 100,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun carriedCountdownDoesNotCollapseToRoundedAbsoluteDeadline() {
        val sampleRate = 48_000
        val hit = ShortArray(64) { 16_000 }
        val pad = PadModel(
            globalIndex = 0,
            audio = PcmAudio(name = "carried-countdown-hit", samples = hit, sampleRate = sampleRate),
            startFrame = 0,
            endFrame = hit.size,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { if (it == 0) pad else PadModel(it) }
        val file = File.createTempFile("choplab-carried-countdown", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = setOf(stepKey(0, 3)),
                bpm = 120f,
                swing = 55f,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            val pcm = readPcm16(file)

            assertEquals(96_000, summary.frameCount)
            assertEquals(0, pcm[18_600].toInt())
            assertEquals(0, pcm[18_601].toInt())
            assertTrue(kotlin.math.abs(pcm[18_602].toInt()) > 100)
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavLengthEndsAtTheNextRealtimeBarBoundary() {
        val file = File.createTempFile("choplab-carried-countdown-length", ".wav")

        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = List(SamplerConfig.PAD_COUNT) { PadModel(it) },
                activeSteps = emptySet(),
                bpm = 40f,
                swing = 56f,
                bars = 1,
                outputSampleRate = 48_000,
            )

            assertEquals(288_001, summary.frameCount)
            assertEquals(summary.frameCount, readPcm16(file).size)
            assertEquals(summary.frameCount * Short.SIZE_BYTES, littleEndianInt(file.readBytes(), 40))
        } finally {
            file.delete()
        }
    }

    @Test
    fun reversePitchGainAndToneChangeIndependentPcmObservations() {
        val sampleRate = 8_000
        val rising = ShortArray(512) { frame -> (2_000 + frame * 40).coerceAtMost(22_000).toShort() }
        val alternating = ShortArray(512) { frame -> if (frame % 2 == 0) 20_000 else -20_000 }

        fun render(
            samples: ShortArray = rising,
            reverse: Boolean = false,
            pitch: Float = 0f,
            gain: Float = 1f,
            tone: Float = 1f,
        ): ShortArray {
            val pad = PadModel(
                globalIndex = 0,
                audio = PcmAudio(name = "parameter-fixture", samples = samples, sampleRate = sampleRate),
                startFrame = 0,
                endFrame = samples.size,
                reverse = reverse,
                pitchSemitones = pitch,
                gain = gain,
                tone = tone,
            )
            return renderPcm(
                pads = List(SamplerConfig.PAD_COUNT) { if (it == 0) pad else PadModel(it) },
                activeSteps = setOf(stepKey(0, 0)),
                sampleRate = sampleRate,
                swing = 50f,
            )
        }

        val forward = render()
        val reversed = render(reverse = true)
        assertTrue(kotlin.math.abs(reversed[64].toInt()) > kotlin.math.abs(forward[64].toInt()) * 2)

        val halfGain = render(gain = 0.5f)
        assertTrue(kotlin.math.abs(forward[128].toInt()) > kotlin.math.abs(halfGain[128].toInt()))

        val pitched = render(pitch = 12f)
        assertTrue(lastEnergeticFrame(pitched) < lastEnergeticFrame(forward) - 150)

        val bright = render(samples = alternating, tone = 1f)
        val dark = render(samples = alternating, tone = 0.15f)
        assertTrue(windowEnergy(bright, 64, 256) > windowEnergy(dark, 64, 256) * 4)
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

    @Test
    fun nonFiniteParametersUseTheSameSafeOfflinePolicy() {
        val sampleRate = 8_000
        val audio = PcmAudio(
            name = "offline-non-finite",
            samples = ShortArray(512) { frame -> (frame * 37 - 8_000).toShort() },
            sampleRate = sampleRate,
        )
        fun pads(pad: PadModel): List<PadModel> =
            List(SamplerConfig.PAD_COUNT) { index -> if (index == 0) pad else PadModel(index) }
        val neutralPad = PadModel(0, audio, 0, audio.frameCount, gain = 1f)
        val nonFinitePad = neutralPad.copy(pitchSemitones = Float.NaN, tone = Float.NaN)

        val neutral = renderPcm(pads(neutralPad), setOf(stepKey(0, 0)), sampleRate, swing = 50f)
        val sanitized = renderPcm(pads(nonFinitePad), setOf(stepKey(0, 0)), sampleRate, swing = 50f)
        assertTrue(neutral.contentEquals(sanitized))

        val file = File.createTempFile("choplab-non-finite-policy", ".wav")
        try {
            val summary = PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads(neutralPad.copy(gain = Float.NaN)),
                activeSteps = setOf(stepKey(0, 0)),
                bpm = Float.NaN,
                swing = Float.POSITIVE_INFINITY,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            assertTrue(summary.peak.isFinite())
            assertEquals(0f, summary.peak, 0f)
            assertTrue(summary.frameCount > 0)
        } finally {
            file.delete()
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun readPcm16(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray((bytes.size - 44) / Short.SIZE_BYTES) { index ->
            ByteBuffer.wrap(bytes, 44 + index * Short.SIZE_BYTES, Short.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
        }
    }

    private fun renderPcm(
        pads: List<PadModel>,
        activeSteps: Set<Int>,
        sampleRate: Int,
        swing: Float,
    ): ShortArray {
        val file = File.createTempFile("choplab-render-oracle", ".wav")
        return try {
            PatternRenderer.renderToWav(
                outputFile = file,
                pads = pads,
                activeSteps = activeSteps,
                bpm = 120f,
                swing = swing,
                bars = 1,
                outputSampleRate = sampleRate,
            )
            readPcm16(file)
        } finally {
            file.delete()
        }
    }

    private fun assertWindowHasEnergy(pcm: ShortArray, frame: Int) {
        assertTrue(
            "Expected rendered energy around frame $frame",
            pcm.copyOfRange(frame, (frame + 32).coerceAtMost(pcm.size))
                .any { kotlin.math.abs(it.toInt()) > 2_000 },
        )
    }

    private fun lastEnergeticFrame(pcm: ShortArray): Int =
        pcm.indexOfLast { kotlin.math.abs(it.toInt()) > 200 }

    private fun windowEnergy(pcm: ShortArray, start: Int, end: Int): Long =
        pcm.copyOfRange(start, end).sumOf { kotlin.math.abs(it.toInt()).toLong() }
}
