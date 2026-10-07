package com.choplab.desktop.next

import com.choplab.ui.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NextDesktopFilesTest {
    @Test fun selectedFilesUseTheProductionImportHistoryArchiveAndRecoveryPaths() = runBlocking {
        val root = Files.createTempDirectory("next-desktop-files-")
        try { NextDesktopFileSelfTest.run(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun anEmptyMultiFileFolderOrUnsupportedDropNeverChoosesAnArbitrarySource() {
        val root = Files.createTempDirectory("next-desktop-drop-")
        try {
            val audio = Files.createFile(root.resolve("music.FLAC")).toFile()
            val project = Files.createFile(root.resolve("music.CHOPLAB")).toFile()
            val text = Files.createFile(root.resolve("music.txt")).toFile()
            assertFalse(assertNotNull(nextDroppedFile(listOf(audio))).project)
            assertTrue(assertNotNull(nextDroppedFile(listOf(project))).project)
            for (files in listOf(emptyList(), listOf(audio, project), listOf(text), listOf(root.toFile()),
                listOf(root.resolve("missing.wav").toFile()), listOf("https://example.invalid/music.wav"))) assertNull(nextDroppedFile(files))
            assertTrue(audio.exists()); assertTrue(project.exists()); assertTrue(text.exists())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun nativeFileCommandsCannotBypassRecordingOrUnavailableCapabilities() {
        val ready = ContinuousEditorState(capabilities = setOf(ContinuousCapability.IMPORT_AUDIO, ContinuousCapability.OPEN_PROJECT))
        assertTrue(canUseNextFile(ready, ContinuousCapability.IMPORT_AUDIO))
        assertFalse(canUseNextFile(ready, ContinuousCapability.EXPORT_WAV))
        for (state in listOf(ready.copy(recordingSource = true), ready.copy(recordingVoice = true), ready.copy(recordingHits = true),
            ready.copy(startingSourceRecording = true), ready.copy(startingVoiceRecording = true), ready.copy(recordingSystemAudio = true),
            ready.copy(liveChopping = true), ready.copy(capabilities = emptySet()))) {
            assertFalse(canUseNextFile(state, ContinuousCapability.IMPORT_AUDIO))
            assertFalse(canUseNextFile(state, ContinuousCapability.OPEN_PROJECT))
        }
    }
}
