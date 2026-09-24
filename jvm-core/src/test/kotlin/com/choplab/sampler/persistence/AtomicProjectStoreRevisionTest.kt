package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact work counts and negative paths, never machine-dependent timing assertions. */
class AtomicProjectStoreRevisionTest {
    @Test fun healthyGenerationsHashOnlyTheHighestRevision() = inDirectory { directory ->
        seedFour(directory)
        val hashed = mutableListOf<String>()
        assertEquals(4L, newest(directory, hashed))
        assertEquals(listOf("autosave.choplab"), hashed)
    }

    @Test fun pendingCanRankAboveThePrimaryWithoutHashingOlderFiles() = inDirectory { directory ->
        generation(directory, "autosave", 6L)
        generation(directory, "autosave.pending", 8L)
        val hashed = mutableListOf<String>()
        assertEquals(8L, newest(directory, hashed))
        assertEquals(listOf("autosave.pending.choplab"), hashed)
    }

    @Test fun badHighestDigestFallsBackButDoesNotScanBelowTheWinner() = inDirectory { directory ->
        seedFour(directory)
        File(directory, "autosave.choplab").appendBytes(byteArrayOf(1))
        val hashed = mutableListOf<String>()
        assertEquals(3L, newest(directory, hashed))
        assertEquals(listOf("autosave.choplab", "autosave.pending.choplab"), hashed)
    }

    @Test fun revisionExtremesAndEqualRevisionPriorityDoNotOverflow() = inDirectory { directory ->
        generation(directory, "autosave", Long.MIN_VALUE)
        generation(directory, "autosave.previous", Long.MAX_VALUE)
        generation(directory, "autosave.pending", Long.MAX_VALUE)
        val hashed = mutableListOf<String>()
        assertEquals(Long.MAX_VALUE, newest(directory, hashed))
        assertEquals(listOf("autosave.pending.choplab"), hashed)
    }

    @Test fun orphanAndInvalidSidecarsCannotHideAValidRevision() = inDirectory { directory ->
        generation(directory, "autosave.previous", 5L)
        File(directory, "autosave.revision").writeText("99\t${"0".repeat(64)}\n")
        File(directory, "autosave.pending.revision").writeText("100\tnot-a-hash")
        val hashed = mutableListOf<String>()
        assertEquals(5L, newest(directory, hashed))
        assertEquals(listOf("autosave.choplab", "autosave.previous.choplab"), hashed)
    }

    @Test fun uppercaseDigestAndSurroundingWhitespaceRemainAccepted() = inDirectory { directory ->
        generation(directory, "autosave", -5L)
        val file = File(directory, "autosave.revision")
        file.writeText(" \n" + file.readText().uppercase() + "\r\n ")
        assertEquals(-5L, newest(directory, mutableListOf()))
        assertEquals(-5L, AtomicProjectStore(directory).loadWithRevision()?.revision)
    }

    @Test fun noSidecarMeansNoRevisionHashAndKeepsLegacyLoading() = inDirectory { directory ->
        generation(directory, "autosave", 3L)
        File(directory, "autosave.revision").delete()
        val hashed = mutableListOf<String>()
        assertEquals(Long.MIN_VALUE, newest(directory, hashed))
        assertTrue(hashed.isEmpty())
        assertEquals(null, AtomicProjectStore(directory).loadWithRevision()?.revision)
    }

    @Test fun oversizedSidecarIsRejectedBeforeHashOrDecode() = inDirectory { directory ->
        generation(directory, "autosave.previous", 2L)
        generation(directory, "autosave", 3L)
        val sidecar = File(directory, "autosave.revision")
        sidecar.writeText(sidecar.readText() + " ".repeat(1_000_000))
        val hashed = mutableListOf<String>()
        assertEquals(2L, newest(directory, hashed))
        assertEquals(listOf("autosave.previous.choplab"), hashed)
        assertEquals(2L, AtomicProjectStore(directory).loadWithRevision()?.revision)
    }

    @Test fun knownStaleSaveDoesNotRecreateADeletedDirectory() = inDirectory { parent ->
        val directory = File(parent, "projects")
        val store = AtomicProjectStore(directory)
        assertTrue(store.save(SamplerUiState(), 7L))
        assertTrue(directory.deleteRecursively())
        assertFalse(store.save(SamplerUiState(), 6L))
        assertFalse(directory.exists())
    }

    @Test fun knownStaleSaveDoesNotTouchAnInaccessibleReplacement() = inDirectory { parent ->
        val directory = File(parent, "projects")
        val store = AtomicProjectStore(directory)
        assertTrue(store.save(SamplerUiState(), 7L))
        assertTrue(directory.deleteRecursively())
        directory.writeText("replacement sentinel")
        assertFalse(store.save(SamplerUiState(), 7L))
        assertEquals("replacement sentinel", directory.readText())
    }

    @Test fun freshSaveStillChecksAnExternallyNewerDiskRevision() = inDirectory { directory ->
        val store = AtomicProjectStore(directory)
        assertTrue(store.save(SamplerUiState(bpm = 90f), 3L))
        assertTrue(AtomicProjectStore(directory).save(SamplerUiState(bpm = 110f), 10L))
        assertFalse(store.save(SamplerUiState(bpm = 100f), 8L))
        assertEquals(110f, store.load()?.bpm)
    }

    @Test fun implicitSavesRemainMonotonicAcrossStoreRecreation() = inDirectory { directory ->
        generation(directory, "autosave", 4L)
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 100f))
        assertEquals(5L, store.loadWithRevision()?.revision)
        AtomicProjectStore(directory).save(SamplerUiState(bpm = 110f))
        assertEquals(6L, store.loadWithRevision()?.revision)
    }

    @Test fun saturatedRevisionDoesNotRewriteArchives() = inDirectory { directory ->
        generation(directory, "autosave", Long.MAX_VALUE)
        val before = bytes(directory)
        val store = AtomicProjectStore(directory)
        store.save(SamplerUiState(bpm = 140f))
        assertFalse(store.save(SamplerUiState(bpm = 80f), Long.MAX_VALUE))
        assertEquals(before, bytes(directory))
    }

    @Test fun cancellationDuringDecodeIsNotConvertedToBackupRecovery() = inDirectory { directory ->
        seedFour(directory)
        val failure = CancellationException("synthetic cancellation")
        var calls = 0
        val actual = assertThrows(CancellationException::class.java) {
            AtomicProjectStore(directory).loadWithRevision { calls++; throw failure }
        }
        assertSame(failure, actual)
        assertEquals(1, calls)
    }

    @Test fun fatalDecodeErrorIsNotRetriedAcrossAllBackups() = inDirectory { directory ->
        seedFour(directory)
        val failure = LinkageError("synthetic fatal error; no real exhaustion")
        var calls = 0
        val actual = assertThrows(LinkageError::class.java) {
            AtomicProjectStore(directory).loadWithRevision { calls++; throw failure }
        }
        assertSame(failure, actual)
        assertEquals(1, calls)
    }

    @Test fun cancellationAndFatalDigestFailuresEscapeImmediately() = inDirectory { directory ->
        seedFour(directory)
        for (failure in listOf(CancellationException("synthetic"), LinkageError("synthetic"))) {
            var calls = 0
            val actual = assertThrows(failure.javaClass) {
                AtomicProjectStore(directory).newestRevisionOnDisk { calls++; throw failure }
            }
            assertSame(failure, actual)
            assertEquals(1, calls)
        }
    }

    @Test fun revisionScanNeverDeletesOrRewritesAnyGeneration() = inDirectory { directory ->
        seedFour(directory)
        File(directory, "autosave.choplab").appendBytes(byteArrayOf(1))
        val before = bytes(directory)
        assertEquals(3L, newest(directory, mutableListOf()))
        assertEquals(before, bytes(directory))
    }

    @Test fun digestHexMatchesEveryPossibleByteValue() {
        for (block in 0 until 8) {
            val digest = ByteArray(32) { (block * 32 + it).toByte() }
            val expected = digest.joinToString("") { "%02x".format(it) }
            assertEquals(expected, sha256DigestHex(digest))
        }
    }

    @Test fun digestHexMatchesKnownSha256AndRejectsWrongLength() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256DigestHex(MessageDigest.getInstance("SHA-256").digest(ByteArray(0))),
        )
        for (size in listOf(0, 31, 33)) {
            assertThrows(IllegalArgumentException::class.java) { sha256DigestHex(ByteArray(size)) }
        }
    }

    private fun newest(directory: File, calls: MutableList<String>): Long =
        AtomicProjectStore(directory).newestRevisionOnDisk { file -> calls += file.name; hash(file) }

    private fun seedFour(directory: File) {
        generation(directory, "autosave.previous2", 1L)
        generation(directory, "autosave.previous", 2L)
        generation(directory, "autosave.pending", 3L)
        generation(directory, "autosave", 4L)
    }

    private fun generation(directory: File, name: String, revision: Long) {
        val archive = File(directory, "$name.choplab")
        archive.outputStream().use { ProjectArchiveCodec.write(SamplerUiState(), it) }
        File(directory, "$name.revision").writeText("$revision\t${hash(archive)}\n")
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bytes(directory: File) = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("choplab-revision-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
