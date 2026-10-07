package com.choplab.desktop.next

import com.choplab.jvm.AutosaveRecoveryException
import com.choplab.jvm.EditorBackend
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.*

class NextStartupTest {
    @Test fun classifiesRecoveryAccessStorageAndUnknownWithoutExposingExceptionText() {
        assertEquals(NextStartupFailure.RECOVERY, nextStartupFailure(AutosaveRecoveryException()))
        assertEquals(NextStartupFailure.ACCESS, nextStartupFailure(IllegalStateException("wrapper", AccessDeniedException("private"))))
        assertEquals(NextStartupFailure.ACCESS, nextStartupFailure(SecurityException("private")))
        assertEquals(NextStartupFailure.STORAGE, nextStartupFailure(FileSystemException("private", null, "No space left")))
        assertEquals(NextStartupFailure.OTHER, nextStartupFailure(IllegalStateException("private")))
    }

    @Test fun showingFolderDoesNotRetryAndOnlyExplicitRetryStartsAgain() {
        var starts = 0; var shown = 0
        val choices = ArrayDeque(listOf(NextStartupChoice.SHOW_FOLDER, NextStartupChoice.RETRY))
        val value = recoverNextStartup({ if (++starts == 1) throw AccessDeniedException("profile") else "ready" },
            { assertEquals(NextStartupFailure.ACCESS, it); choices.removeFirst() }, { shown++; assertEquals(1, starts) })
        assertEquals("ready", value); assertEquals(2, starts); assertEquals(1, shown)
    }

    @Test fun damagedRealAutosaveOffersRecoveryWithoutStartingAudioOrChangingSavedBytes() {
        val directory = Files.createTempDirectory("next-startup-test-")
        try {
            val autosave = Files.createDirectories(directory.resolve("autosave")).resolve("autosave.0.json")
            val original = "{ damaged project".toByteArray()
            Files.write(autosave, original)
            var decisions = 0
            val backend = recoverNextStartup({ EditorBackend.create(directory,
                { error("No audio may start before recovery") }, { _, _ -> error("No services before recovery") }) },
                { failure -> decisions++; assertEquals(NextStartupFailure.RECOVERY, failure); NextStartupChoice.QUIT },
                { error("No implicit folder action") })
            assertNull(backend); assertEquals(1, decisions)
            assertContentEquals(original, Files.readAllBytes(autosave))
        } finally { directory.toFile().deleteRecursively() }
    }
}
