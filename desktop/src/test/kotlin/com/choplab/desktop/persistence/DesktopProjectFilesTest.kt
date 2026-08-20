package com.choplab.desktop.persistence

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DesktopProjectFilesTest {
    @Test
    fun saveAndLoadUseTheSharedArchiveSchema() {
        val directory = Files.createTempDirectory("choplab-desktop-project").toFile()
        try {
            val audio = PcmAudio(id = 42L, name = "source", samples = shortArrayOf(1, -2, 3, -4), sampleRate = 48_000)
            val state = SamplerUiState(
                currentAudio = audio,
                rangeEndFrame = audio.frameCount,
                pads = List(SamplerConfig.PAD_COUNT) { index ->
                    if (index == 0) PadModel(index, audio, 0, audio.frameCount) else PadModel(index)
                },
                bpm = 127f,
                swing = 63f,
            )

            val written = DesktopProjectFiles.save(directory.resolve("session"), state)
            val restored = DesktopProjectFiles.load(written)

            assertEquals("session.choplab", written.name)
            assertEquals(127f, restored.bpm)
            assertEquals(63f, restored.swing)
            assertContentEquals(audio.samples, restored.currentAudio?.samples)
            assertTrue(restored.pads[0].isAssigned)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedReplacementLeavesThePreviousProjectReadable() {
        val directory = Files.createTempDirectory("choplab-desktop-replace").toFile()
        try {
            val target = directory.resolve("session.choplab")
            DesktopProjectFiles.save(target, SamplerUiState(bpm = 111f))
            assertFails { DesktopProjectFiles.save(target, SamplerUiState(pads = emptyList())) }
            assertEquals(111f, DesktopProjectFiles.load(target).bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedInputDoesNotProduceState() {
        val malformed = Files.createTempFile("choplab-malformed", ".choplab").toFile()
        try {
            malformed.writeText("not a project", Charsets.UTF_8)
            assertFails { DesktopProjectFiles.load(malformed) }
        } finally {
            malformed.delete()
        }
    }
}
