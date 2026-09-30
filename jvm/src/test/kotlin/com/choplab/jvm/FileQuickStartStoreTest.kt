package com.choplab.jvm

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class FileQuickStartStoreTest {
    @Test fun completingGuideIsProfileLocalPersistentAndLeavesProductionFilesUntouched() = runBlocking {
        val root = Files.createTempDirectory("quick-start-store-")
        try {
            val profile = root.resolve("next-profile")
            val autosave = profile.resolve("autosave/existing.json"); Files.createDirectories(autosave.parent)
            val original = byteArrayOf(7, 11, 29); Files.write(autosave, original)
            val store = FileQuickStartStore(profile.resolve("ui"))
            assertFalse(store.completed()); store.complete(); store.complete()
            assertTrue(FileQuickStartStore(profile.resolve("ui")).completed())
            assertFalse(FileQuickStartStore(root.resolve("other-profile/ui")).completed())
            assertContentEquals(original, Files.readAllBytes(autosave))
            assertEquals(listOf("quick-start-v1"), Files.list(profile.resolve("ui")).use { it.map { p -> p.fileName.toString() }.toList() })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun corruptPreferenceIsReportedAndAValidExplicitCompletionRepairsIt() = runBlocking {
        val directory = Files.createTempDirectory("quick-start-corrupt-")
        try {
            Files.writeString(directory.resolve("quick-start-v1"), "unfinished")
            val store = FileQuickStartStore(directory)
            assertFailsWith<IllegalArgumentException> { store.completed() }
            store.complete(); assertTrue(store.completed())
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun failedPreferenceWritePreservesExistingProfileBytes() = runBlocking {
        val root = Files.createTempDirectory("quick-start-readonly-")
        try {
            val existing = root.resolve("ui"); val bytes = byteArrayOf(5, 3, 1)
            Files.write(existing, bytes)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { FileQuickStartStore(existing).complete() }
            assertContentEquals(bytes, Files.readAllBytes(existing))
            assertEquals(1L, Files.list(root).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }
}
