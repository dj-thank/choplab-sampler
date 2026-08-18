package com.choplab.sampler.audio

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
