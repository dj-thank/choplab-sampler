package com.choplab.desktop.next

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NextLibraryTest {
    private suspend fun idle(library: NextLibrary) = withTimeout(5_000) { while (library.state.value.busy) delay(10) }

    @Test fun addSelectBundleRoundtripKeepsOriginalBytesAndNamesAndDoesNotAutoSelect() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-")
        val input = root.resolve("A song.wav").also { NextSelfTest.writeDemo(it) }
        val validations = AtomicInteger()
        val validate: (java.io.File) -> Unit = { require(it.length() > 44); validations.incrementAndGet() }
        NextLibrary(root.resolve("library"), validate).use { library ->
            idle(library)
            assertTrue(library.add(listOf(input))); idle(library)
            assertNull(library.state.value.selection)
            val item = library.state.value.items.single()
            assertEquals("A song", item.title)
            assertTrue(library.add(listOf(input))); idle(library)
            assertEquals(1, validations.get(), "Identical originals are not decoded twice")
            assertTrue(library.select(item.id)); idle(library)
            val selected = assertNotNull(library.state.value.selection)
            assertContentEquals(Files.readAllBytes(input), Files.readAllBytes(selected.path))
            assertEquals(item.title, selected.title)
            val bundle = root.resolve("library.choplib")
            assertTrue(library.export(bundle)); idle(library)
            assertEquals(NextLibrary.Status.EXPORTED, library.state.value.status)
            NextLibrary(root.resolve("other"), validate).use { other ->
                idle(other); assertTrue(other.add(listOf(bundle))); idle(other)
                assertEquals(item.id, other.state.value.items.single().id)
                assertTrue(other.select(item.id)); idle(other)
                assertContentEquals(Files.readAllBytes(input), Files.readAllBytes(assertNotNull(other.state.value.selection).path))
            }
            Files.write(selected.path, byteArrayOf(1, 2, 3))
            assertTrue(library.select(item.id)); idle(library)
            assertEquals(NextLibrary.Status.FAILED, library.state.value.status)
            assertNull(library.state.value.selection)
        }
    }

    @Test fun cancellationRejectsLateCompletionAndAllowsRetryWithoutSelectingOrLosingSavedItems() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-cancel-")
        val input = root.resolve("a.wav").also { NextSelfTest.writeDemo(it) }
        val entered = CountDownLatch(1)
        val waiting = java.util.concurrent.atomic.AtomicBoolean(true)
        NextLibrary(root.resolve("library")) {
            if (waiting.get()) { entered.countDown(); Thread.sleep(20_000) }
        }.use { library ->
            idle(library); assertTrue(library.add(listOf(input)))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertFalse(library.add(listOf(input)), "Only one writer")
            library.cancel(); idle(library)
            assertEquals(NextLibrary.Status.CANCELLED, library.state.value.status)
            assertTrue(library.state.value.items.isEmpty())
            assertNull(library.state.value.selection)
            waiting.set(false)
            assertTrue(library.add(listOf(input))); idle(library)
            assertEquals(1, library.state.value.items.size)
            val bad = root.resolve("bad.wav").also { Files.write(it, byteArrayOf()) }
            assertTrue(library.add(listOf(input, bad))); idle(library)
            assertEquals(NextLibrary.Status.PARTLY_ADDED, library.state.value.status)
            assertEquals(1, library.state.value.failed)
            assertEquals(1, library.state.value.items.size)
        }
    }
}
