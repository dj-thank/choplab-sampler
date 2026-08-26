package com.choplab.desktop.persistence

import com.choplab.sampler.audio.PatternRenderSummary
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopBeatFilesTest {
    @Test
    fun successfulRenderPublishesOnlyCompletedBytesAndReturnsItsSummary() {
        val directory = Files.createTempDirectory("choplab-beat-export-success").toFile()
        val target = directory.resolve("beat.wav").apply { writeText("old", Charsets.UTF_8) }
        val replacement = shortArrayOf(1_200, -1_200)
        val expected = PatternRenderSummary(4, 48_000, replacement.size, 1, 0.5f)

        try {
            val actual = DesktopBeatFiles.export(
                target = target,
                pads = emptyList(),
                patternSequence = emptyList(),
                bpm = 120f,
                swing = 50f,
                renderer = DesktopPatternSequenceRenderer { output, _, _, _, _ ->
                    WavFileWriter(output, expected.sampleRate, expected.channelCount).use { writer ->
                        writer.writePcm16(replacement)
                    }
                    expected
                },
            )

            assertEquals(expected, actual)
            assertEquals(44L + replacement.size * Short.SIZE_BYTES, target.length())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun productionRendererPublishesAValidOneBarWav() {
        val directory = Files.createTempDirectory("choplab-beat-export-production").toFile()
        val target = directory.resolve("beat.wav").apply { writeText("old", Charsets.UTF_8) }
        val audio = PcmAudio(
            id = 13L,
            name = "atomic-export-fixture",
            samples = shortArrayOf(12_000, -12_000, 6_000, -6_000),
            sampleRate = 48_000,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == 0) PadModel(index, audio, 0, audio.frameCount) else PadModel(index)
        }

        try {
            val summary = DesktopBeatFiles.export(
                target = target,
                pads = pads,
                patternSequence = listOf(setOf(stepKey(0, 0))),
                bpm = 240f,
                swing = 50f,
            )
            val bytes = target.readBytes()

            assertEquals(1, summary.bars)
            assertEquals(1, summary.channelCount)
            assertTrue(bytes.size > 44)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
            assertEquals("WAVE", bytes.copyOfRange(8, 12).decodeToString())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulRendererResultWithInvalidWavDoesNotReplaceThePreviousFile() {
        val directory = Files.createTempDirectory("choplab-beat-export-invalid-success").toFile()
        val target = directory.resolve("beat.wav")
        val previous = "previous-valid-wav".encodeToByteArray()
        target.writeBytes(previous)

        try {
            assertFailsWith<IllegalArgumentException> {
                DesktopBeatFiles.export(
                    target = target,
                    pads = emptyList(),
                    patternSequence = emptyList(),
                    bpm = 120f,
                    swing = 50f,
                    renderer = DesktopPatternSequenceRenderer { output, _, _, _, _ ->
                        output.writeText("not a complete wav", Charsets.UTF_8)
                        PatternRenderSummary(1, 48_000, 2, 1, 0f)
                    },
                )
            }

            assertContentEquals(previous, target.readBytes())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedRenderUsesATemporaryPathAndPreservesThePreviousWav() {
        val directory = Files.createTempDirectory("choplab-beat-export-failure").toFile()
        val target = directory.resolve("beat.wav")
        val previous = "previous-valid-wav".encodeToByteArray()
        target.writeBytes(previous)
        var renderedPath: File? = null

        try {
            val failure = assertFailsWith<IllegalStateException> {
                DesktopBeatFiles.export(
                    target = target,
                    pads = emptyList(),
                    patternSequence = emptyList(),
                    bpm = 120f,
                    swing = 50f,
                    renderer = DesktopPatternSequenceRenderer { output, _, _, _, _ ->
                        renderedPath = output
                        output.writeText("partial replacement", Charsets.UTF_8)
                        error("test render failure")
                    },
                )
            }

            assertEquals("test render failure", failure.message)
            assertNotEquals(target.absoluteFile, renderedPath)
            assertContentEquals(previous, target.readBytes())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }
}
