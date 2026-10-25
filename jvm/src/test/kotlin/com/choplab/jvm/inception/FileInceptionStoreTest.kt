package com.choplab.jvm.inception

import com.choplab.core.inception.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.test.*

class FileInceptionStoreTest {
    private fun doc() = InceptionWorkspace("w", "未定", materials = frozenListOf(InceptionMaterial("m", "synthetic material")))
    @Test fun saveReopenCasAndDeleteAreIndependentFromProjectSchema() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-store-"); val store = FileInceptionStore(root)
        val d = doc(); store.save(d, null); assertEquals(d, FileInceptionStore(root).load("w"))
        assertFailsWith<InceptionException> { store.save(d, null) }
        store.save(d.copy(revision = 1, title = "Changed"), 0)
        assertEquals(1, store.list().size)
        assertFailsWith<InceptionException> { store.save(d.copy(revision = 2), 0) }
        assertFailsWith<InceptionException> { store.delete("w", 0) }
        store.delete("w", 1); assertNull(store.load("w")); assertTrue(store.list().isEmpty())
    }
    @Test fun failureBeforeAtomicPublishKeepsOldBytesAndCleansOwnTemporary() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-atomic-"); val d = doc(); FileInceptionStore(root).save(d, null)
        val before = Files.readAllBytes(root.resolve("w.inception"))
        val store = FileInceptionStore(root, beforePublish = { error("synthetic interruption") })
        assertFailsWith<InceptionException> { store.save(d.copy(revision = 1), 0) }
        assertContentEquals(before, Files.readAllBytes(root.resolve("w.inception")))
        assertEquals(listOf(".store.lock", "w.inception"), Files.list(root).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
    }
    @Test fun quotaAndDiskSpaceRefuseBeforePublishing() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-quota-")
        assertEquals(InceptionProblem.CAPACITY, assertFailsWith<InceptionException> { FileInceptionStore(root, maximumBytes = 1).save(doc(), null) }.problem)
        assertEquals(InceptionProblem.CAPACITY, assertFailsWith<InceptionException> { FileInceptionStore(root, usableBytes = { 0 }).save(doc(), null) }.problem)
        assertNull(FileInceptionStore(root).load("w"))
    }
    @Test fun sameSizeCorruptionAndTruncationAreRejectedWithoutRepairingUserBytes() = runBlocking<Unit> {
        for (truncate in listOf(false, true)) {
            val root = Files.createTempDirectory("inception-corrupt-"); val store = FileInceptionStore(root); store.save(doc(), null)
            val file = root.resolve("w.inception"); val bytes = Files.readAllBytes(file)
            val changed = if (truncate) bytes.copyOf(bytes.size - 1) else bytes.clone().also { it[it.lastIndex - 2] = 'X'.code.toByte() }
            Files.write(file, changed)
            assertEquals(InceptionProblem.CORRUPT, assertFailsWith<InceptionException> { store.load("w") }.problem)
            assertFailsWith<InceptionException> { store.save(doc().copy(revision = 1), 0) }
            assertContentEquals(changed, Files.readAllBytes(file))
        }
    }
    @Test fun concurrentStoresOnlyOneRevisionCanWin() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-cas-"); FileInceptionStore(root).save(doc(), null)
        val successes = coroutineScope { (1..8).map { n -> async(Dispatchers.Default) {
            runCatching { FileInceptionStore(root).save(doc().copy(title = "candidate$n", revision = 1), 0) }.isSuccess
        } }.awaitAll() }
        assertEquals(1, successes.count { it }); assertEquals(1L, FileInceptionStore(root).load("w")!!.revision)
    }
    @Test fun deletionAndEditRemoveValidatedCrashedCopiesButPreserveUnknownFiles() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-remnant-"); val store = FileInceptionStore(root); store.save(doc(), null)
        Files.copy(root.resolve("w.inception"), root.resolve("w.pending-crash"))
        Files.writeString(root.resolve("user-note.txt"), "keep")
        store.save(doc().copy(revision = 1, materials = frozenListOf()), 0)
        assertFalse(Files.exists(root.resolve("w.pending-crash")))
        assertEquals("keep", Files.readString(root.resolve("user-note.txt")))
        Files.writeString(root.resolve("w.pending-unknown"), "unknown")
        assertFailsWith<InceptionException> { store.delete("w", 1) }
        assertTrue(Files.exists(root.resolve("w.inception")))
    }
    @Test fun pathsAndOversizedFilesAreRejected() = runBlocking<Unit> {
        val root = Files.createTempDirectory("inception-path-"); val store = FileInceptionStore(root)
        assertFails { store.load("../outside") }
        Files.write(root.resolve("w.inception"), ByteArray(InceptionLimits.MAX_BYTES + 129))
        assertFailsWith<InceptionException> { store.load("w") }
    }
}
