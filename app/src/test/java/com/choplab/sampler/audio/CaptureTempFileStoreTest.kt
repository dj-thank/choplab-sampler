package com.choplab.sampler.audio

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class CaptureTempFileStoreTest {
    @Test
    fun ownedCaptureIsDeletedAfterSuccessfulOrFailedConsumption() = runBlocking {
        val directory = Files.createTempDirectory("choplab-capture-consume").toFile()
        try {
            val store = CaptureTempFileStore(directory)
            val success = store.create("microphone", nowMillis = 10L).apply { writeBytes(byteArrayOf(1)) }
            val failure = store.create("system", nowMillis = 20L).apply { writeBytes(byteArrayOf(2)) }

            assertEquals("decoded", store.consume(success) { "decoded" })
            assertFalse(success.exists())
            runCatching { store.consume(failure) { error("decode failed") } }
            assertFalse(failure.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleCleanupDeletesOnlyOldAppOwnedCaptureNames() {
        val directory = Files.createTempDirectory("choplab-capture-stale").toFile()
        try {
            val store = CaptureTempFileStore(directory)
            val oldOwned = store.create("vocal", nowMillis = 1L).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1L)
            }
            val recentOwned = store.create("system", nowMillis = 9_500L).apply {
                writeBytes(byteArrayOf(2))
                setLastModified(9_500L)
            }
            val userLikeFile = directory.resolve("imported_song.wav").apply {
                writeBytes(byteArrayOf(3))
                setLastModified(1L)
            }
            val outside = Files.createTempFile("choplab-user", ".wav").toFile().apply {
                writeBytes(byteArrayOf(4))
                setLastModified(1L)
            }

            assertEquals(1, store.cleanupStale(nowMillis = 10_000L, maxAgeMillis = 1_000L))
            assertFalse(oldOwned.exists())
            assertTrue(recentOwned.exists())
            assertTrue(userLikeFile.exists())
            assertTrue(outside.exists())
            outside.delete()
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun cleanupPreservesDirectoriesAndNonOwnedNames() {
        val directory = Files.createTempDirectory("choplab-cleanup-names").toFile()
        try {
            val nested = directory.resolve("microphone_1.wav").apply { mkdir() }
            val names = listOf("microphone_x.wav", "user.wav", "system_1.WAV", "vocal_1.wav.tmp")
            names.forEach { directory.resolve(it).writeText("synthetic") }
            assertEquals(0, CaptureTempFileStore(directory).cleanupStale(Long.MAX_VALUE, 0L))
            assertTrue(nested.isDirectory)
            names.forEach { assertTrue(directory.resolve(it).isFile) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun missingDirectoryRemainsMissing() {
        val parent = Files.createTempDirectory("choplab-cleanup-missing").toFile()
        try {
            val missing = parent.resolve("missing")
            assertEquals(0, CaptureTempFileStore(missing).cleanupStale(10_000, 1_000))
            assertFalse(missing.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun freshCaptureDoesNotGetDeletedAtTheCutoffBoundary() {
        val directory = Files.createTempDirectory("choplab-cleanup-cutoff").toFile()
        try {
            val store = CaptureTempFileStore(directory)
            val exact = store.create("microphone", 1).apply { writeText("a"); setLastModified(9_000) }
            val newer = store.create("microphone", 2).apply { writeText("b"); setLastModified(9_001) }
            assertEquals(1, store.cleanupStale(10_000, 1_000))
            assertFalse(exact.exists())
            assertTrue(newer.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun staleOwnedNameSymlinkCannotDeleteAnOutsideFile() {
        val parent = Files.createTempDirectory("choplab-cleanup-symlink").toFile()
        try {
            val directory = parent.resolve("cache").apply { mkdir() }
            val outside = parent.resolve("outside.bin").apply { writeText("synthetic"); setLastModified(1L) }
            val link = directory.resolve("microphone_1.wav")
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (unsupported: Exception) {
                assumeNoException("Symbolic links require platform support/permission", unsupported)
                return
            }
            assertEquals(0, CaptureTempFileStore(directory).cleanupStale(10_000, 1_000))
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("synthetic", outside.readText())
        } finally { parent.deleteRecursively() }
    }

}
