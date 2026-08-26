package com.choplab.sampler.persistence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifiedDocumentPublisherTest {
    @Test
    fun exactDestinationReadBackReturnsThePublishedFingerprint() {
        val directory = Files.createTempDirectory("choplab-verified-document-exact").toFile()
        val source = directory.resolve("source.bin")
        val expected = ByteArray(150_000) { index -> (index * 31).toByte() }
        val destination = MemoryDestination()

        try {
            source.writeBytes(expected)

            publishVerifiedDocument(
                source = source,
                openDestinationOutput = destination::openOutput,
                openDestinationReadBack = destination::openInput,
            )

            assertArrayEquals(expected, destination.stored)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun silentlyTruncatedDestinationIsRejectedAfterOutputClose() {
        val directory = Files.createTempDirectory("choplab-verified-document-short").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        val destination = MemoryDestination { bytes -> bytes.copyOf(7) }

        try {
            val failure = assertThrows(IOException::class.java) {
                publishVerifiedDocument(source, destination::openOutput, destination::openInput)
            }

            assertEquals("保存先の内容が書き込んだデータと一致しません", failure.message)
            assertEquals(7, destination.stored.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sameSizeDestinationCorruptionIsRejectedByDigest() {
        val directory = Files.createTempDirectory("choplab-verified-document-corrupt").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(ByteArray(64) { (it + 1).toByte() }) }
        val destination = MemoryDestination { bytes -> bytes.clone().also { it[31] = (it[31] + 1).toByte() } }

        try {
            assertThrows(IOException::class.java) {
                publishVerifiedDocument(source, destination::openOutput, destination::openInput)
            }
            assertEquals(source.length(), destination.stored.size.toLong())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun extraDestinationBytesAreRejected() {
        val directory = Files.createTempDirectory("choplab-verified-document-extra").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val destination = MemoryDestination { bytes -> bytes + byteArrayOf(5) }

        try {
            assertThrows(IOException::class.java) {
                publishVerifiedDocument(source, destination::openOutput, destination::openInput)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unavailableOutputOrReadBackFailsClosed() {
        val directory = Files.createTempDirectory("choplab-verified-document-open").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val destination = MemoryDestination()

        try {
            assertEquals(
                "保存先を開けません",
                assertThrows(IOException::class.java) {
                    publishVerifiedDocument(source, openDestinationOutput = { null }, destination::openInput)
                }.message,
            )
            assertEquals(
                "保存先を再読できません",
                assertThrows(IOException::class.java) {
                    publishVerifiedDocument(source, destination::openOutput, openDestinationReadBack = { null })
                }.message,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingSourceNeverOpensOrChangesTheDestination() {
        val directory = Files.createTempDirectory("choplab-verified-document-missing-source").toFile()
        val source = directory.resolve("missing.bin")
        var outputOpened = false

        try {
            assertThrows(DocumentPublicationException::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = {
                        outputOpened = true
                        ByteArrayOutputStream()
                    },
                    openDestinationReadBack = { ByteArrayInputStream(byteArrayOf()) },
                )
            }

            assertFalse(outputOpened)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun outputWriteFailureDoesNotAttemptReadBack() {
        val directory = Files.createTempDirectory("choplab-verified-document-write").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var readBackOpened = false

        try {
            val failure = assertThrows(DocumentPublicationException::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = {
                        object : OutputStream() {
                            override fun write(value: Int) {
                                throw IOException("test write failure")
                            }
                        }
                    },
                    openDestinationReadBack = {
                        readBackOpened = true
                        ByteArrayInputStream(byteArrayOf())
                    },
                )
            }

            assertEquals("保存先へ書き込めません", failure.message)
            assertFalse(readBackOpened)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun readBackFailureIsARecoverablePublicationFailure() {
        val directory = Files.createTempDirectory("choplab-verified-document-read").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val destination = MemoryDestination()

        try {
            val failure = assertThrows(DocumentPublicationException::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = destination::openOutput,
                    openDestinationReadBack = {
                        object : InputStream() {
                            override fun read(): Int = throw IOException("test read failure")
                        }
                    },
                )
            }

            assertEquals("保存先を再読できません", failure.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyValidatedSourceCanBeVerifiedExactly() {
        val directory = Files.createTempDirectory("choplab-verified-document-empty").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf()) }
        val destination = MemoryDestination()

        try {
            publishVerifiedDocument(source, destination::openOutput, destination::openInput)

            assertArrayEquals(byteArrayOf(), destination.stored)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun outputCloseFailureDoesNotAttemptReadBack() {
        val directory = Files.createTempDirectory("choplab-verified-document-close").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var readBackOpened = false

        try {
            assertThrows(IOException::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = {
                        object : ByteArrayOutputStream() {
                            override fun close() {
                                super.close()
                                throw IOException("test close failure")
                            }
                        }
                    },
                    openDestinationReadBack = {
                        readBackOpened = true
                        ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    },
                )
            }

            assertFalse(readBackOpened)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationIsRethrownWithoutOpeningReadBack() {
        val directory = Files.createTempDirectory("choplab-verified-document-cancel").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1)) }
        val expected = CancellationException("test publication cancellation")
        var readBackOpened = false

        try {
            val actual = assertThrows(CancellationException::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = { throw expected },
                    openDestinationReadBack = {
                        readBackOpened = true
                        ByteArrayInputStream(byteArrayOf(1))
                    },
                )
            }

            assertEquals(expected, actual)
            assertFalse(readBackOpened)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fatalErrorIsNeverConvertedIntoPublicationFailure() {
        val directory = Files.createTempDirectory("choplab-verified-document-fatal").toFile()
        val source = directory.resolve("source.bin").apply { writeBytes(byteArrayOf(1)) }

        try {
            val failure = assertThrows(AssertionError::class.java) {
                publishVerifiedDocument(
                    source = source,
                    openDestinationOutput = { throw AssertionError("test fatal publication error") },
                    openDestinationReadBack = { ByteArrayInputStream(byteArrayOf(1)) },
                )
            }

            assertEquals("test fatal publication error", failure.message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun zeroProgressReadBackStillCompletesWithoutSpinning() {
        val directory = Files.createTempDirectory("choplab-verified-document-zero-progress").toFile()
        val expected = ByteArray(257) { (it * 7).toByte() }
        val source = directory.resolve("source.bin").apply { writeBytes(expected) }
        val destination = MemoryDestination()

        try {
            publishVerifiedDocument(
                source = source,
                openDestinationOutput = destination::openOutput,
                openDestinationReadBack = { IntermittentZeroInputStream(destination.stored) },
            )

            assertArrayEquals(expected, destination.stored)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class MemoryDestination(
    private val transformOnClose: (ByteArray) -> ByteArray = { it },
) {
    var stored: ByteArray = byteArrayOf()
        private set

    fun openOutput(): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            super.close()
            stored = transformOnClose(toByteArray())
        }
    }

    fun openInput(): InputStream = ByteArrayInputStream(stored)
}

private class IntermittentZeroInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private var returnZero = true

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (returnZero) {
            returnZero = false
            return 0
        }
        returnZero = true
        return delegate.read(buffer, offset, length)
    }
}
