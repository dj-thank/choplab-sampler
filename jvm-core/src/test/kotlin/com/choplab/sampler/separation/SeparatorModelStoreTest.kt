package com.choplab.sampler.separation

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeparatorModelStoreTest {
    private val bytes = ByteArray(300_000).also { Random(5).nextBytes(it) }
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun store(directory: java.io.File, payload: ByteArray = bytes, opened: AtomicInteger = AtomicInteger()) =
        SeparatorModelStore(directory, expectedSha256 = digest, expectedBytes = bytes.size.toLong()) { url ->
            assertEquals(SeparatorSpec.MODEL_URL, url)
            opened.incrementAndGet()
            SeparatorModelStore.Download(payload.size.toLong(), ByteArrayInputStream(payload))
        }

    @Test
    fun downloadsVerifiesAndReusesThePinnedModel() {
        val directory = Files.createTempDirectory("separator-store").toFile()
        try {
            val opened = AtomicInteger()
            val progress = mutableListOf<Float>()
            val models = store(directory, opened = opened)
            assertFalse(models.isInstalled())
            val file = models.ensure(onProgress = { progress += it })
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(models.isInstalled())
            assertEquals(1f, progress.last(), 0f)
            assertEquals(file, models.ensure())
            assertEquals(1, opened.get())
            // A new store (next app launch) re-verifies the existing file without downloading.
            assertEquals(file, store(directory, opened = opened).ensure())
            assertEquals(1, opened.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun digestMismatchLeavesNoModelBehind() {
        val directory = Files.createTempDirectory("separator-store-bad").toFile()
        try {
            val tampered = bytes.copyOf().also { it[42] = (it[42] + 1).toByte() }
            try {
                store(directory, payload = tampered).ensure()
                fail("Expected verification failure")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("SHA-256"))
            }
            assertFalse(directory.resolve(SeparatorSpec.MODEL_FILE).exists())
            assertFalse(directory.resolve(SeparatorSpec.MODEL_FILE + ".part").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedAndTruncatedBodiesAreRejected() {
        val directory = Files.createTempDirectory("separator-store-size").toFile()
        try {
            for (payload in listOf(bytes + byteArrayOf(1), bytes.copyOf(bytes.size - 1))) {
                try {
                    store(directory, payload = payload).ensure()
                    fail("Expected size failure")
                } catch (expected: IOException) {
                    assertFalse(directory.resolve(SeparatorSpec.MODEL_FILE).exists())
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptExistingFileIsReplacedAndCancellationCleansUp() {
        val directory = Files.createTempDirectory("separator-store-corrupt").toFile()
        try {
            directory.resolve(SeparatorSpec.MODEL_FILE).writeBytes(ByteArray(bytes.size))
            val opened = AtomicInteger()
            assertArrayEquals(bytes, store(directory, opened = opened).ensure().readBytes())
            assertEquals(1, opened.get())

            directory.resolve(SeparatorSpec.MODEL_FILE).delete()
            try {
                store(directory).ensure(isCancelled = { true })
                fail("Expected cancellation")
            } catch (expected: CancellationException) {
                assertFalse(directory.resolve(SeparatorSpec.MODEL_FILE).exists())
                assertFalse(directory.resolve(SeparatorSpec.MODEL_FILE + ".part").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
