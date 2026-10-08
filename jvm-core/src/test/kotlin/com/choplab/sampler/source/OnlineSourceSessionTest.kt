package com.choplab.sampler.source

import com.choplab.sampler.source.newpipe.DetailedYoutubeBackend
import com.choplab.sampler.source.newpipe.OnlineSourceException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnlineSourceSessionTest {
    private val audio = ByteArray(1024) { (it * 7).toByte() }
    private val format = YoutubeAudioFormat("format", "webm", "opus", 48_000, 2, 128_000, false,
        audio.size.toLong(), "ja", null, null, false)
    private val candidate = YoutubeSource("abcdefghijk", "Synthetic original", "Uploader", 1.0,
        YoutubeMetadata(artist = "Observed artist", album = "Observed album", formats = listOf(format, format.copy(id = "alternate"))))

    private open inner class Backend : DetailedYoutubeBackend, AutoCloseable {
        val downloads = AtomicInteger()
        val cancellations = AtomicInteger()
        val closes = AtomicInteger()
        var kind: YoutubeSearchKind? = null
        override fun search(query: String, jobId: String) = search(query, jobId, YoutubeSearchKind.VIDEOS)
        override fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource> {
            this.kind = kind; return listOf(candidate)
        }
        override fun info(url: String, jobId: String) = candidate
        override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File {
            assertEquals(format.id, source.selectedFormat)
            downloads.incrementAndGet(); progress(100f)
            return folder.resolve("audio.webm").also { it.writeBytes(audio) }
        }
        override fun cancel(jobId: String) { cancellations.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }
    private fun waitFor(label: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!check()) { assertTrue("Timed out: $label", System.nanoTime() < deadline); Thread.sleep(2) }
    }
    private fun idle(session: OnlineSourceSession) = waitFor("owned worker: ${session.state.value}") { !session.state.value.busy }
    private fun ready(session: OnlineSourceSession) {
        assertTrue(session.search(candidate.url)); idle(session)
        assertTrue(session.inspect(candidate.id)); idle(session)
        assertTrue(session.selectFormat(format.id))
    }
    private fun files(path: Path): Map<String, List<Byte>> = if (!Files.isDirectory(path)) emptyMap() else
        path.toFile().listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
    private fun holdIgnoringInterrupts(release: CountDownLatch) {
        while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
    }

    @Test fun detailsAndExplicitFormatAreRequiredAndSavingDoesNotImplicitlyUseTheSource() {
        val root = Files.createTempDirectory("online-details-")
        val backend = Backend()
        try {
            OnlineSourceSession(root, { assertArrayEquals(audio, it.readBytes()) }, backend).use { session ->
                assertTrue(session.search("synthetic", YoutubeSearchKind.MUSIC)); idle(session)
                assertEquals(YoutubeSearchKind.MUSIC, backend.kind)
                assertEquals(listOf(candidate), session.state.value.candidates)
                assertNull(session.state.value.details); assertNull(session.state.value.saved)
                assertFalse(session.acquire(candidate.id)); assertEquals(0, backend.downloads.get())
                assertTrue(session.inspect(candidate.id)); idle(session)
                assertNull(session.state.value.details!!.selectedFormat)
                assertFalse(session.acquire(candidate.id)); assertFalse(session.selectFormat("unknown"))
                assertTrue(session.selectFormat(format.id)); assertTrue(session.acquire(candidate.id)); idle(session)
                assertEquals(OnlineSourcePhase.SAVED, session.state.value.phase)
                val saved = session.state.value.saved!!
                assertArrayEquals(audio, Files.readAllBytes(saved.path)); assertEquals(candidate.title, saved.title)
                val item = LocalAudioLibrary(root.toFile()) {}.list().single()
                assertEquals("Observed artist", item.artist); assertEquals("Observed album", item.album)
                assertNull(item.trackNumber); assertNull(item.discNumber)
                assertEquals(1, backend.downloads.get())
                // SAVED is only a library receipt. A new format invalidates that receipt for explicit use.
                assertTrue(session.selectFormat("alternate")); assertNull(session.state.value.saved)
                assertTrue(Files.exists(saved.path))
            }
            waitFor("one backend close") { backend.closes.get() == 1 }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun savedIdentityRejectsSecondDownloadAndSameFormatSelectionKeepsReceipt() {
        val root = Files.createTempDirectory("online-reuse-")
        val backend = Backend()
        try {
            OnlineSourceSession(root, {}, backend).use { session ->
                ready(session); assertTrue(session.acquire(candidate.id)); idle(session)
                val receipt = session.state.value.saved
                assertFalse(session.acquire(candidate.id)); assertEquals(1, backend.downloads.get())
                assertTrue(session.selectFormat(format.id)); assertEquals(receipt, session.state.value.saved)
                assertFalse(session.acquire(candidate.id)); assertEquals(receipt, session.state.value.saved)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun slowArtworkPublishesDetailsFirstAndRequiredDownloadWaitsForItsActualRelease() {
        val root = Files.createTempDirectory("online-artwork-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val backend = object : Backend() {
            override fun artwork(source: YoutubeSource, jobId: String): ByteArray {
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try { entered.countDown(); holdIgnoringInterrupts(release); return byteArrayOf(1, 2, 3) }
                finally { active.decrementAndGet() }
            }
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File {
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try { return super.download(source, folder, jobId, progress) } finally { active.decrementAndGet() }
            }
        }
        try {
            OnlineSourceSession(root, {}, backend).use { session ->
                ready(session); assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNotNull(session.state.value.details); assertFalse(session.state.value.busy)
                assertTrue(session.state.value.artworkLoading); assertEquals(format.id, session.state.value.details!!.selectedFormat)
                assertTrue(session.acquire(candidate.id))
                assertEquals(0, backend.downloads.get())
                release.countDown(); idle(session)
                assertEquals(OnlineSourcePhase.SAVED, session.state.value.phase)
                assertNull(session.state.value.artwork); assertFalse(session.state.value.artworkLoading)
                assertEquals(1, maximum.get()); assertEquals(1, backend.downloads.get())
            }
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }

    @Test fun cancellingOptionalArtworkRetainsConfirmedDetailsAndRejectsLateImage() {
        val root = Files.createTempDirectory("online-artwork-cancel-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val backend = object : Backend() {
            override fun artwork(source: YoutubeSource, jobId: String): ByteArray {
                entered.countDown(); holdIgnoringInterrupts(release); return byteArrayOf(9)
            }
        }
        try {
            OnlineSourceSession(root, {}, backend).use { session ->
                ready(session); assertTrue(entered.await(5, TimeUnit.SECONDS))
                val detail = session.state.value.details
                session.cancel(); assertEquals(detail, session.state.value.details)
                assertFalse(session.state.value.artworkLoading); assertFalse(session.state.value.busy)
                release.countDown()
                assertTrue(session.acquire(candidate.id)); idle(session)
                assertNull(session.state.value.artwork); assertNotNull(session.state.value.saved)
            }
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }

    @Test fun unknownTransferProgressStaysUnknownUntilObservedAndSavingHasItsOwnPhase() {
        val root = Files.createTempDirectory("online-progress-")
        val receiving = CountDownLatch(1); val received = CountDownLatch(1)
        val measured = CountDownLatch(1); val downloadFinish = CountDownLatch(1)
        val saving = CountDownLatch(1); val saveFinish = CountDownLatch(1)
        val backend = object : Backend() {
            override fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File {
                progress(Float.NaN); receiving.countDown(); received.await(5, TimeUnit.SECONDS)
                progress(42f); measured.countDown(); downloadFinish.await(5, TimeUnit.SECONDS)
                progress(100f); return folder.resolve("audio.webm").also { it.writeBytes(audio) }
            }
        }
        try {
            OnlineSourceSession(root, { saving.countDown(); saveFinish.await(5, TimeUnit.SECONDS) }, backend).use { session ->
                ready(session); session.acquire(candidate.id); assertTrue(receiving.await(5, TimeUnit.SECONDS))
                assertNull(session.state.value.progress)
                received.countDown(); assertTrue(measured.await(5, TimeUnit.SECONDS)); assertEquals(42, session.state.value.progress)
                downloadFinish.countDown(); assertTrue(saving.await(5, TimeUnit.SECONDS))
                assertEquals(OnlineSourcePhase.SAVING, session.state.value.phase); assertNull(session.state.value.progress)
                saveFinish.countDown(); idle(session); assertNull(session.state.value.progress)
            }
        } finally { received.countDown(); downloadFinish.countDown(); saveFinish.countDown(); root.toFile().deleteRecursively() }
    }

    @Test fun knownOversizeAndInvalidFormatsAreRefusedBeforeProviderDownloadButUnknownAndBoundaryStaySelectable() {
        val root = Files.createTempDirectory("online-capability-")
        val observed = listOf(format.copy(id = "large", bytes = OnlineSourceLimits.MAX_AUDIO_BYTES + 1),
            format.copy(id = "zero", bytes = 0), format.copy(id = "negative", bytes = -1),
            format.copy(id = "boundary", bytes = OnlineSourceLimits.MAX_AUDIO_BYTES), format.copy(id = "unknown", bytes = null))
        val backend = object : Backend() {
            override fun info(url: String, jobId: String) = candidate.copy(metadata = YoutubeMetadata(formats = observed))
        }
        try {
            OnlineSourceSession(root, {}, backend).use { session ->
                session.search("synthetic"); idle(session); session.inspect(candidate.id); idle(session)
                assertFalse(session.selectFormat("large")); assertEquals(OnlineSourceProblem.TOO_LARGE, session.state.value.problem)
                assertFalse(session.acquire(candidate.id))
                for (id in listOf("zero", "negative")) {
                    assertFalse(session.selectFormat(id)); assertEquals(OnlineSourceProblem.MALFORMED_RESPONSE, session.state.value.problem)
                }
                assertTrue(session.selectFormat("boundary")); assertTrue(session.selectFormat("unknown"))
                assertNull(session.state.value.problem); assertEquals(0, backend.downloads.get())
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationWaitsForBothWriterAndDisconnectAndRejectsLateResults() {
        val root = Files.createTempDirectory("online-cancel-")
        val entered = CountDownLatch(1); val writerRelease = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val disconnectRelease = CountDownLatch(1)
        val backend = object : Backend() {
            override fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource> {
                if (query == "old") { entered.countDown(); holdIgnoringInterrupts(writerRelease) }
                return super.search(query, jobId, kind)
            }
            override fun cancel(jobId: String) {
                super.cancel(jobId); cancelEntered.countDown(); holdIgnoringInterrupts(disconnectRelease)
            }
        }
        try {
            OnlineSourceSession(root, {}, backend).use { session ->
                assertTrue(session.search("old")); assertTrue(entered.await(5, TimeUnit.SECONDS))
                session.cancel(); session.cancel()
                assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
                assertTrue(session.state.value.busy); assertFalse(session.search("overlap"))
                writerRelease.countDown()
                assertEquals(OnlineSourcePhase.CANCELLED, session.state.value.phase)
                assertFalse(session.search("disconnect still owns provider"))
                disconnectRelease.countDown(); idle(session)
                assertEquals(1, backend.cancellations.get())
                assertTrue(session.state.value.candidates.isEmpty()); assertNull(session.state.value.saved)
                assertTrue(session.search("retry")); idle(session)
                assertEquals(listOf(candidate), session.state.value.candidates)
            }
        } finally { writerRelease.countDown(); disconnectRelease.countDown(); root.toFile().deleteRecursively() }
    }

    @Test fun cancelAfterANonCooperativeDecoderReturnsCannotAdoptItsLibraryFiles() {
        val root = Files.createTempDirectory("online-decode-cancel-")
        val original = root.resolve("original.webm").toFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val library = root.resolve("library")
        LocalAudioLibrary(library.toFile()) {}.importFile(original, "Existing")
        val before = files(library)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var block = true
        val backend = Backend()
        try {
            OnlineSourceSession(library, {
                if (block) { block = false; entered.countDown(); holdIgnoringInterrupts(release) }
            }, backend).use { session ->
                ready(session); assertTrue(session.acquire(candidate.id)); assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(OnlineSourcePhase.SAVING, session.state.value.phase)
                session.cancel(); release.countDown(); idle(session)
                assertEquals(before, files(library)); assertNull(session.state.value.saved)
                assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
                ready(session); assertTrue(session.acquire(candidate.id)); idle(session)
                assertNotNull(session.state.value.saved)
            }
        } finally { release.countDown(); root.toFile().deleteRecursively() }
    }

    @Test fun invalidAudioRateLimitAndEmptySearchStayTypedAndAllowRetryWithoutTouchingExistingLibrary() {
        val root = Files.createTempDirectory("online-errors-")
        val original = root.resolve("existing.webm").toFile().also { it.writeBytes(byteArrayOf(4, 5, 6)) }
        val library = root.resolve("library")
        LocalAudioLibrary(library.toFile()) {}.importFile(original)
        val before = files(library)
        val backend = object : Backend() {
            override fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource> = when (query) {
                "rate" -> throw OnlineSourceException(OnlineSourceProblem.RATE_LIMITED)
                "empty" -> emptyList()
                else -> super.search(query, jobId, kind)
            }
        }
        try {
            OnlineSourceSession(library, { throw java.io.IOException("Invalid synthetic audio") }, backend).use { session ->
                assertTrue(session.search("rate")); idle(session)
                assertEquals(OnlineSourceProblem.RATE_LIMITED, session.state.value.problem)
                assertTrue(session.search("empty")); idle(session)
                assertEquals(OnlineSourcePhase.CANDIDATES, session.state.value.phase)
                assertNull(session.state.value.problem); assertTrue(session.state.value.candidates.isEmpty())
                ready(session); assertTrue(session.acquire(candidate.id)); idle(session)
                assertEquals(OnlineSourceProblem.INVALID_AUDIO, session.state.value.problem)
                assertNull(session.state.value.saved); assertEquals(before, files(library))
                assertArrayEquals(byteArrayOf(4, 5, 6), original.readBytes())
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun closeReturnsBeforeBlockingProviderCleanupAndIsIdempotent() {
        val root = Files.createTempDirectory("online-close-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1); val cleanupRelease = CountDownLatch(1)
        val backend = object : Backend() {
            override fun search(query: String, jobId: String, kind: YoutubeSearchKind): List<YoutubeSource> {
                entered.countDown(); holdIgnoringInterrupts(release)
                return super.search(query, jobId, kind)
            }
            override fun cancel(jobId: String) {
                super.cancel(jobId); cleanupEntered.countDown(); holdIgnoringInterrupts(cleanupRelease)
            }
        }
        val callers = Executors.newSingleThreadExecutor()
        val session = OnlineSourceSession(root, {}, backend)
        try {
            assertTrue(session.search("synthetic")); assertTrue(entered.await(5, TimeUnit.SECONDS))
            callers.submit { session.close(); session.close() }.get(5, TimeUnit.SECONDS)
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertEquals(OnlineSourcePhase.CLOSED, session.state.value.phase)
            assertFalse(session.search("closed")); assertNull(session.state.value.saved)
            release.countDown(); cleanupRelease.countDown()
            waitFor("exactly one close") { backend.closes.get() == 1 }
            assertEquals(1, backend.cancellations.get())
            assertTrue(files(root).isEmpty()); assertTrue(session.state.value.candidates.isEmpty())
        } finally { release.countDown(); cleanupRelease.countDown(); session.close(); callers.shutdownNow(); root.toFile().deleteRecursively() }
    }

    @Test fun aMetadataCommitFailureRemovesOnlyTheNewPayloadAndPreservesPriorLibraryBytes() {
        val root = Files.createTempDirectory("online-library-commit-")
        try {
            val original = root.resolve("original.webm").toFile().also { it.writeBytes(audio) }
            val library = root.resolve("library")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it.toInt() and 255) }
            val metadata = library.resolve("$hash.properties")
            Files.createDirectories(metadata)
            val marker = metadata.resolve("existing")
            Files.write(marker, byteArrayOf(7, 8, 9))
            val storage = LocalAudioLibrary(library.toFile()) {}
            try { storage.importFile(original); fail("The metadata destination cannot be replaced") }
            catch (_: java.io.IOException) {}
            assertArrayEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(marker))
            assertArrayEquals(audio, original.readBytes())
            assertFalse(Files.exists(library.resolve("$hash.webm")))
            assertEquals(listOf("$hash.properties"), library.toFile().list()!!.toList())
            // A pre-existing orphan at the expected hash is neither replaced nor deleted on failure.
            val orphan = library.resolve("$hash.webm")
            Files.write(orphan, byteArrayOf(9, 9, 9))
            try { storage.importFile(original); fail("A different orphan must remain untouched") }
            catch (_: java.io.IOException) {}
            assertArrayEquals(byteArrayOf(9, 9, 9), Files.readAllBytes(orphan))
        } finally { root.toFile().deleteRecursively() }
    }
}
