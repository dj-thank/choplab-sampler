package com.choplab.apple

import com.choplab.core.Action
import com.choplab.core.ai.GoogleLyricSession
import com.choplab.core.inception.*
import com.choplab.core.model.*
import com.choplab.ui.inception.InceptionController
import com.choplab.ui.inception.InceptionDestination
import kotlinx.coroutines.*
import platform.posix.symlink
import kotlin.test.*

class AppleInceptionStoreTest {
    private fun directory() = (IosPaths.temporary() + "/" + uniqueName("inception-test-")).also(::ensureDirectory)
    private fun doc() = InceptionWorkspace("workspace", "Synthetic note", materials = frozenListOf(InceptionMaterial("material", "synthetic source")))
    private fun write(path: String, bytes: ByteArray) = FileWriter(path, exclusive = false).use { it.write(bytes); it.syncAndClose() }
    private fun envelope(payload: ByteArray) = ("CHOPLAB-INCEPTION-1\n" + sha256(payload) + "\n").encodeToByteArray() + payload
    private suspend fun problem(expected: InceptionProblem, operation: suspend () -> Unit) {
        assertEquals(expected, assertFailsWith<InceptionException> { operation() }.problem)
    }

    @Test fun saveReopenCompareRevisionAndDelete(): Unit = runBlocking {
        val root = directory(); val store = IosInceptionStore(root); val value = doc()
        store.save(value, null)
        assertEquals(value, IosInceptionStore(root).load(value.id))
        problem(InceptionProblem.STALE) { store.save(value, null) }
        store.save(value.copy(revision = 1, title = "Changed"), 0)
        problem(InceptionProblem.STALE) { store.save(value.copy(revision = 2), 0) }
        problem(InceptionProblem.STALE) { store.delete(value.id, 0) }
        assertEquals(1L, store.list().single().revision)
        store.delete(value.id, 1)
        assertNull(IosInceptionStore(root).load(value.id))
        assertTrue(store.list().isEmpty())
    }

    @Test fun interruptedPublicationPreservesOldBytesAndCleansOnlyItsPending(): Unit = runBlocking {
        val root = directory(); val value = doc(); IosInceptionStore(root).save(value, null)
        val path = "$root/${value.id}.inception"; val before = readFile(path, 300_000)
        val store = IosInceptionStore(root, beforePublish = { error("synthetic interruption") })
        problem(InceptionProblem.STORAGE) { store.save(value.copy(revision = 1), 0) }
        assertContentEquals(before, readFile(path, 300_000))
        assertEquals(listOf(".store.lock", "${value.id}.inception"), listFiles(root).sorted())
    }

    @Test fun hiddenBytesAndNoDiskSpaceRefuseWithoutReplacing(): Unit = runBlocking {
        val root = directory(); val value = doc(); IosInceptionStore(root).save(value, null)
        val path = "$root/${value.id}.inception"; val before = readFile(path, 300_000)
        write("$root/.unknown", ByteArray(4096) { 7 })
        problem(InceptionProblem.CAPACITY) { IosInceptionStore(root, maximumBytes = before.size * 2L).save(value.copy(revision = 1), 0) }
        problem(InceptionProblem.CAPACITY) { IosInceptionStore(root, usableBytes = { 0 }).save(value.copy(revision = 1), 0) }
        assertContentEquals(before, readFile(path, 300_000))
        assertEquals(4096, readFile("$root/.unknown", 4096).size)
    }

    @Test fun sameSizeCorruptionTruncationAndMalformedUtf8ArePreserved(): Unit = runBlocking {
        for (kind in 0..2) {
            val root = directory(); val store = IosInceptionStore(root); store.save(doc(), null)
            val path = "$root/workspace.inception"; val original = readFile(path, 300_000)
            val bad = when (kind) {
                0 -> original.copyOf().also { it[it.lastIndex - 2] = 'X'.code.toByte() }
                1 -> original.copyOf(original.size - 1)
                else -> envelope(byteArrayOf(0xc3.toByte(), 0x28))
            }
            write(path, bad)
            problem(InceptionProblem.CORRUPT) { store.load("workspace") }
            problem(InceptionProblem.CORRUPT) { store.save(doc().copy(revision = 1), 0) }
            assertContentEquals(bad, readFile(path, 300_000))
        }
    }

    @Test fun unknownVersionAndOversizeAreRejectedWithoutRepair(): Unit = runBlocking {
        val root = directory(); val store = IosInceptionStore(root)
        val path = "$root/workspace.inception"
        val future = envelope(InceptionJson.encode(doc()).replace("\"version\":1", "\"version\":2").encodeToByteArray())
        write(path, future)
        problem(InceptionProblem.UNKNOWN_VERSION) { store.load("workspace") }
        problem(InceptionProblem.UNKNOWN_VERSION) { store.save(doc(), null) }
        assertContentEquals(future, readFile(path, 300_000))
        write(path, ByteArray(InceptionLimits.MAX_BYTES + 129))
        problem(InceptionProblem.CORRUPT) { store.load("workspace") }
    }

    @Test fun independentStoreInstancesHaveOneCompareRevisionWinner(): Unit = runBlocking {
        val root = directory(); IosInceptionStore(root).save(doc(), null)
        val winners = coroutineScope {
            (1..8).map { index -> async(Dispatchers.Default) {
                try { IosInceptionStore(root).save(doc().copy(revision = 1, title = "Candidate $index"), 0); true }
                catch (failure: InceptionException) { assertEquals(InceptionProblem.STALE, failure.problem); false }
            } }.awaitAll()
        }
        assertEquals(1, winners.count { it })
        assertEquals(1L, IosInceptionStore(root).load("workspace")?.revision)
    }

    @Test fun deletionPurgesValidatedCopiesAndRefusesUnknownPendingBytes(): Unit = runBlocking {
        val root = directory(); val store = IosInceptionStore(root); store.save(doc(), null)
        write("$root/workspace.pending-crash", readFile("$root/workspace.inception", 300_000))
        write("$root/user-note.txt", "keep".encodeToByteArray())
        store.save(doc().copy(revision = 1, materials = frozenListOf()), 0)
        assertNull(regularFileSize("$root/workspace.pending-crash"))
        assertEquals("keep", readFile("$root/user-note.txt", 10).decodeToString())
        write("$root/workspace.pending-unknown", byteArrayOf(1, 2, 3))
        problem(InceptionProblem.CORRUPT) { store.delete("workspace", 1) }
        assertNotNull(store.load("workspace"))
        assertContentEquals(byteArrayOf(1, 2, 3), readFile("$root/workspace.pending-unknown", 10))
    }

    @Test fun unsafePathsLinksAndDocumentCountAreRefused(): Unit = runBlocking {
        val root = directory(); val store = IosInceptionStore(root)
        problem(InceptionProblem.INVALID) { store.load("../outside") }
        val target = directory() + "/keep"; write(target, byteArrayOf(4, 5, 6))
        assertEquals(0, symlink(target, "$root/workspace.inception"))
        problem(InceptionProblem.CORRUPT) { store.load("workspace") }
        assertContentEquals(byteArrayOf(4, 5, 6), readFile(target, 10))
        val bounded = IosInceptionStore(directory())
        repeat(32) { bounded.save(doc().copy(id = "note-$it"), null) }
        problem(InceptionProblem.CAPACITY) { bounded.save(doc().copy(id = "extra"), null) }
        assertEquals(32, bounded.list().size)
    }

    @Test fun controllerDoesNotPublishTextWhenStorageIsFull(): Unit = runBlocking {
        val root = directory(); val value = doc(); IosInceptionStore("$root/notes").save(value, null)
        val host = IosBackend.create("$root/profile", openOutput = { AudioOutputOpen.disabled() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = InceptionController(IosInceptionStore("$root/notes", usableBytes = { 0 }), scope, host.studio, GoogleLyricSession { 1000L })
        try {
            assertTrue(controller.open())
            assertFalse(controller.addMaterial("must not be published"))
            assertEquals(InceptionProblem.CAPACITY, controller.state.value.problem)
            assertEquals(value, controller.state.value.workspace)
            assertEquals(value, IosInceptionStore("$root/notes").load(value.id))
        } finally { controller.close(); scope.cancel(); host.shutdown() }
    }

    @Test fun realControllerPersistsEvidenceAppliesWithOneUndoAndPurgesDeletedMaterial(): Unit = runBlocking {
        withTimeout(40_000) {
            val root = directory()
            val host = IosBackend.create("$root/profile", openOutput = { AudioOutputOpen.disabled() })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = IosInceptionStore("$root/profile/inception-v1")
            fun controller() = InceptionController(store, scope, host.studio, GoogleLyricSession { 1000L })
            var current = controller()
            try {
                val before = host.studio.document.value.project
                assertTrue(current.open()); assertTrue(current.create("Synthetic ideas"))
                assertTrue(current.addMaterial("synthetic original"))
                val material = current.state.value.workspace!!.materials.single()
                assertTrue(current.correctMaterial(material.id, "synthetic correction"))
                assertTrue(current.select(material.id))
                assertTrue(current.putConcept(null, "Adopted title", "Synthetic lyric"))
                val concept = current.state.value.workspace!!.concepts.single()
                assertTrue(current.decide(concept.id, DecisionKind.ADOPT))
                val saved = current.state.value.workspace!!
                assertEquals("synthetic original", saved.materials.single().original)
                assertTrue(current.preview(concept.id, InceptionDestination.PRODUCTION_TITLE)); assertTrue(current.apply())
                assertEquals(concept.title, host.studio.document.value.project.title)
                assertTrue(host.studio.dispatch(Action.Undo).accepted)
                assertEquals(before, host.studio.document.value.project)
                assertTrue(current.preview(concept.id, InceptionDestination.LYRICS)); assertTrue(current.apply())
                assertEquals(concept.statement, host.studio.document.value.project.lyrics.single().text)
                assertTrue(host.studio.dispatch(Action.Undo).accepted)
                assertEquals(before, host.studio.document.value.project)
                current.close(); current = controller(); assertTrue(current.open())
                assertEquals(saved, current.state.value.workspace)
                assertFalse(current.voiceAvailable)
                assertFalse(current.generate())
                assertTrue(current.deleteMaterial(material.id))
                current.close(); current = controller(); assertTrue(current.open())
                val purged = current.state.value.workspace!!
                assertTrue(purged.materials.isEmpty()); assertTrue(purged.concepts.isEmpty()); assertTrue(purged.decisions.isEmpty())
                assertFalse(current.state.value.canUndo)
            } finally { current.close(); scope.cancel(); host.shutdown() }
        }
    }
}
