package com.choplab.desktop.next

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NextLibraryTest {
    @Test fun unreadableLibraryIsRecoverableAndDoesNotReplaceTheLastReadableList() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-unreadable-")
        val directory = root.resolve("library")
        Files.writeString(directory, "not a directory")
        NextLibrary(directory) {}.use { library ->
            idle(library)
            assertEquals(NextLibrary.Status.LOAD_FAILED, library.state.value.status)
            assertTrue(library.state.value.readFailed)
            Files.delete(directory); Files.createDirectory(directory)
            assertTrue(library.refresh()); idle(library)
            assertEquals(NextLibrary.Status.READY, library.state.value.status)
            assertFalse(library.state.value.readFailed)
            val source = root.resolve("sample.wav").also { Files.writeString(it, "synthetic bytes") }
            library.add(listOf(source)); idle(library)
            val items = library.state.value.items
            val moved = root.resolve("preserved")
            Files.move(directory, moved); Files.writeString(directory, "read unavailable")
            library.refresh(); idle(library)
            assertEquals(NextLibrary.Status.LOAD_FAILED, library.state.value.status)
            assertEquals(items, library.state.value.items)
            Files.delete(directory); Files.move(moved, directory)
            library.refresh(); idle(library)
            assertEquals(items, library.state.value.items); assertFalse(library.state.value.readFailed)
        }
        root.toFile().deleteRecursively()
    }

    @Test fun partialFailureReportsTheFileAndRetryOnlyReadsFailedPaths() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-partial-")
        val good = root.resolve("good.wav").also { Files.writeString(it, "good") }
        val bad = root.resolve("empty.wav").also { Files.write(it, byteArrayOf()) }
        val missing = root.resolve("moved.wav")
        val validations = AtomicInteger()
        NextLibrary(root.resolve("library")) { validations.incrementAndGet() }.use { library ->
            idle(library); library.add(listOf(good, bad, missing)); idle(library)
            assertEquals(NextLibrary.Status.PARTLY_ADDED, library.state.value.status)
            assertEquals(1, library.state.value.completed); assertEquals(0, library.state.value.reused)
            assertEquals(listOf("empty.wav", "moved.wav"), library.state.value.failures.map { it.name })
            assertEquals(listOf(NextLibrary.FailureReason.EMPTY, NextLibrary.FailureReason.MISSING), library.state.value.failures.map { it.reason })
            val original = library.state.value.items.single()
            // A successful path is no longer available: retry must not attempt it.
            Files.delete(good); Files.writeString(bad, "repaired"); Files.writeString(missing, "found")
            assertTrue(library.retryFailures()); idle(library)
            assertEquals(NextLibrary.Status.ADDED, library.state.value.status)
            assertEquals(2, library.state.value.completed); assertEquals(3, validations.get())
            assertTrue(library.state.value.failures.isEmpty()); assertTrue(original in library.state.value.items)
            library.add(listOf(bad)); idle(library)
            assertEquals(0, library.state.value.completed); assertEquals(1, library.state.value.reused)
            assertEquals(3, validations.get()); assertNull(library.state.value.selection)
        }
        root.toFile().deleteRecursively()
    }

    @Test fun partialBundleAdoptionCountsMatchTheVisibleLibraryAndRetryHasNoPhantomAdds() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-bundle-partial-")
        try {
            val source = root.resolve("set.choplib")
            java.util.zip.ZipOutputStream(Files.newOutputStream(source)).use { output -> repeat(3) { n ->
                output.putNextEntry(java.util.zip.ZipEntry("Song $n.wav")); output.write(byteArrayOf(n.toByte(), 2, 3)); output.closeEntry()
            } }
            val directory = root.resolve("library")
            var commits = 0; var fail = true
            NextLibrary(directory) { file ->
                if (file.parentFile.toPath() == directory && ++commits == 2 && fail) throw java.io.IOException("Injected adoption failure")
            }.use { library ->
                idle(library); library.add(listOf(source)); idle(library)
                assertEquals(NextLibrary.Status.PARTLY_ADDED, library.state.value.status)
                assertEquals(2, library.state.value.completed); assertEquals(2, library.state.value.items.size)
                assertEquals(1, library.state.value.failed); assertNotNull(library.state.value.failures.single().entryTitle)
                fail = false; assertTrue(library.retryFailures()); idle(library)
                assertEquals(1, library.state.value.completed); assertEquals(2, library.state.value.reused)
                assertEquals(3, library.state.value.items.size); assertEquals(0, library.state.value.failed)
                assertNull(library.state.value.selection)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun damagedDuplicatesOfferExplicitRepairAndRetainTheDamagedBytes() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-repair-")
        try {
            val source = root.resolve("original.wav").also { Files.write(it, byteArrayOf(1, 2, 3)) }
            val directory = root.resolve("library")
            NextLibrary(directory) {}.use { library ->
                idle(library); library.add(listOf(source)); idle(library)
                val id = library.state.value.items.single().id
                val saved = directory.resolve("$id.wav")
                Files.write(saved, byteArrayOf(3, 2, 1))
                library.add(listOf(source)); idle(library)
                assertEquals(NextLibrary.Status.FAILED, library.state.value.status)
                assertEquals(0, library.state.value.completed); assertEquals(0, library.state.value.reused)
                assertEquals(NextLibrary.FailureReason.CORRUPT, library.state.value.failures.single().reason)
                assertContentEquals(byteArrayOf(3, 2, 1), Files.readAllBytes(saved))
                assertTrue(library.repairFailures()); idle(library)
                assertEquals(1, library.state.value.repaired); assertEquals(0, library.state.value.reused)
                assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(saved))
                assertTrue(directory.resolve(".recovery").toFile().listFiles()!!.any { it.readBytes().contentEquals(byteArrayOf(3, 2, 1)) })
                assertTrue(library.select(id)); idle(library); assertNotNull(library.state.value.selection)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun browseSessionSurvivesDialogWorkerRecreationAndSeparatesProfilesAndCatalogWindows() {
        val root = Files.createTempDirectory("next-library-navigation-")
        try {
            val directory = root.resolve("library")
            val session = NextLibraryBrowseSessions.forDirectory(directory)
            val browser = session.browser(0)
            val items = (0..80).map { com.choplab.sampler.source.AudioLibraryItem("id$it", "Song $it", "file", 10, "Artist", "Album") }
            browser.open(browser.page(items).groups.single()); browser.open(browser.page(items).groups.single())
            browser.search("Song"); browser.next(items)
            browser.rememberViewport(browser.location, 7, 18)
            val again = NextLibraryBrowseSessions.forDirectory(directory)
            assertSame(session, again); assertSame(browser, again.browser(0))
            assertEquals(40, again.browser(0).page(items).offset)
            assertEquals(com.choplab.sampler.source.LibraryBrowser.Viewport(7, 18), again.browser(0).viewport(browser.location))
            assertEquals("Song", again.browser(0).query)
            assertNotSame(browser, again.browser(2000)); assertNotSame(session, NextLibraryBrowseSessions.forDirectory(root.resolve("other")))
            browser.page(emptyList())
            assertEquals(com.choplab.sampler.source.LibraryBrowser.Section.ARTISTS, browser.section)
            assertEquals(0, browser.location.offset)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun selectedBundleRemainsBoundedAcrossListsAndAnOversizedSelectionPreservesTheExistingChoice() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-selection-")
        val sources = (0 until 40).map { n -> root.resolve("$n.wav").also { Files.writeString(it, "bytes $n") } }
        NextLibrary(root.resolve("library")) {}.use { library ->
            idle(library); library.add(sources); idle(library)
            val chosen = library.state.value.items.take(20)
            assertTrue(library.addExportItems(chosen)); assertEquals(chosen, library.state.value.exportItems)
            assertFalse(library.addExportItems(library.state.value.items)); assertEquals(chosen, library.state.value.exportItems)
            assertEquals(NextLibrary.Status.BUNDLE_LIMIT, library.state.value.status)
            assertTrue(library.toggleExport(chosen.last())); assertEquals(NextLibrary.Status.READY, library.state.value.status)
            assertTrue(library.addExportItems(chosen)); assertEquals(chosen.map { it.id }.toSet(), library.state.value.exportItems.map { it.id }.toSet())
            val target = root.resolve("selected.choplib")
            library.export(target, chosen.map { it.id }); idle(library)
            assertEquals(NextLibrary.Status.EXPORTED, library.state.value.status)
            assertTrue(library.state.value.exportItems.isEmpty()); assertNull(library.state.value.selection)
            NextLibrary(root.resolve("restored")) {}.use { restored ->
                idle(restored); restored.add(listOf(target)); idle(restored)
                assertEquals(chosen.map { it.id }.toSet(), restored.state.value.items.map { it.id }.toSet())
            }
        }
        root.toFile().deleteRecursively()
    }

    @Test fun actualLibraryValidationThroughProductionAndArchiveReopen() = runBlocking<Unit> {
        NextLibrarySelfTest.run(Files.createTempDirectory("next-library-self-test-"))
    }

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
            val originalBundle = Files.readAllBytes(bundle)
            Files.write(selected.path, byteArrayOf(1, 2, 3))
            assertTrue(library.export(bundle)); idle(library)
            assertEquals(NextLibrary.Status.FAILED, library.state.value.status)
            assertContentEquals(originalBundle, Files.readAllBytes(bundle), "Failed export must not replace an existing bundle")
            assertTrue(library.select(item.id)); idle(library)
            assertEquals(NextLibrary.Status.FAILED, library.state.value.status)
            assertNull(library.state.value.selection)
        }
    }

    @Test fun namedSelectionImportsAsOneUndoAndReopensWithTheSameOriginalAndTitle() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-library-production-")
        val input = root.resolve("A song.wav").also { NextSelfTest.writeDemo(it) }
        val hash = com.choplab.jvm.sha256(Files.readAllBytes(input))
        val profile = root.resolve("profile")
        lateinit var saved: com.choplab.core.model.Project
        suspend fun ready(backend: NextBackend) = withTimeout(5_000) {
            while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
        }
        NextBackend.create(profile, sinkFactory = { error("No native audio") }, microphone = { null }).use { backend ->
            val original = backend.studio.document.value.project
            val location = backend.files.registerNamed(input, "Library title", hash)
            assertTrue(backend.studio.dispatch(com.choplab.core.Action.Import(location)).accepted); ready(backend)
            val project = backend.studio.document.value.project
            assertEquals("Library title", project.assets.single().name)
            assertEquals(hash, project.source?.assetHash)
            assertContentEquals(Files.readAllBytes(input), backend.assets.read(project.assets.single()))
            assertTrue(backend.studio.dispatch(com.choplab.core.Action.Undo).accepted)
            assertEquals(original, backend.studio.document.value.project)
            assertTrue(backend.studio.dispatch(com.choplab.core.Action.Redo).accepted)
            assertTrue(backend.saveProject(root.resolve("library.choplab")).accepted); ready(backend)
            saved = backend.studio.document.value.project
            // A changed selection must never replace the document with different bytes.
            val changed = backend.files.registerNamed(input, "Wrong identity", "0".repeat(64))
            assertTrue(backend.studio.dispatch(com.choplab.core.Action.Import(changed)).accepted); ready(backend)
            assertEquals(saved, backend.studio.document.value.project)
        }
        NextBackend.create(profile, sinkFactory = { error("No native audio") }, microphone = { null }).use { backend ->
            assertEquals(saved, backend.studio.document.value.project)
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
