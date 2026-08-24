package com.choplab.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAudioImportPolicyTest {
    @Test
    fun acceptsOnlyRealWavFilesWithoutPretendingMp3OrVideoSupport() {
        val directory = Files.createTempDirectory("choplab-audio-picker").toFile()
        try {
            val wav = File(directory, "sample.WAV").apply { writeBytes(byteArrayOf(1)) }
            val mp3 = File(directory, "sample.mp3").apply { writeBytes(byteArrayOf(1)) }
            val video = File(directory, "sample.mp4").apply { writeBytes(byteArrayOf(1)) }
            val text = File(directory, "notes.txt").apply { writeText("not audio") }

            assertTrue(DesktopAudioImportPolicy.accepts(wav))
            assertFalse(DesktopAudioImportPolicy.accepts(mp3))
            assertFalse(DesktopAudioImportPolicy.accepts(video))
            assertFalse(DesktopAudioImportPolicy.accepts(text))
            assertFalse(DesktopAudioImportPolicy.accepts(File(directory, "missing.wav")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
