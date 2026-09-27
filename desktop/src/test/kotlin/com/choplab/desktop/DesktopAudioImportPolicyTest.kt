package com.choplab.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAudioImportPolicyTest {
    @Test
    fun acceptsSupportedFilesForBundledDecoderAndRejectsMissingOrUnrelatedFiles() {
        val directory = Files.createTempDirectory("choplab-audio-picker").toFile()
        try {
            val wav = File(directory, "sample.WAV").apply { writeBytes(byteArrayOf(1)) }
            val mp3 = File(directory, "sample.mp3").apply { writeBytes(byteArrayOf(1)) }
            val video = File(directory, "sample.mp4").apply { writeBytes(byteArrayOf(1)) }
            val text = File(directory, "notes.txt").apply { writeText("not audio") }

            assertTrue(DesktopAudioImportPolicy.accepts(wav))
            assertTrue(DesktopAudioImportPolicy.accepts(mp3))
            assertTrue(DesktopAudioImportPolicy.accepts(video))
            assertFalse(DesktopAudioImportPolicy.accepts(text))
            assertFalse(DesktopAudioImportPolicy.accepts(File(directory, "missing.wav")))
            val selection = selectAudioImports(listOf(wav, mp3, text, File(directory, "missing.wav")))
            assertEquals(listOf(wav, mp3), selection.accepted)
            assertEquals(listOf(text), selection.rejected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun singleFileChooserSelectionIsKeptWhenTheMultiSelectionListIsEmpty() {
        val file = File("song.m4a")
        assertEquals(listOf(file), chooserAudioFiles(emptyArray(), file))
        assertEquals(listOf(file), chooserAudioFiles(arrayOf(file), null))
        assertEquals(emptyList(), chooserAudioFiles(emptyArray(), null))
    }
}
