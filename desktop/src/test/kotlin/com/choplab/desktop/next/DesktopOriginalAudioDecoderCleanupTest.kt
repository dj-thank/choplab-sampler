package com.choplab.desktop.next

import com.choplab.jvm.PcmMemoryBudget
import com.choplab.jvm.PcmScratchBudget
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopOriginalAudioDecoderCleanupTest {
    @Test fun cancellationWaitsForOwnedChildClosesStreamsAndRetriesTransientOwnedFileSharing() = runBlocking {
        val fixture = Fixture()
        try {
            val sharing = AtomicInteger()
            fixture.decoder.deleteTemporaryFile = { path ->
                assertFalse(fixture.process.isAlive, "Deletion must follow actual child termination")
                if (!fixture.process.streamsClosed || sharing.getAndIncrement() < 2) {
                    throw FileSystemException(path.fileName.toString(), null, "Controlled sharing violation")
                }
                Files.deleteIfExists(path)
            }
            val failure = assertFailsWith<CancellationException> { fixture.cancel() }
            assertTrue(fixture.process.killed)
            assertTrue(fixture.process.waitedForExit)
            assertTrue(fixture.process.streamsClosed)
            assertTrue(sharing.get() >= 3)
            assertTrue(failure.suppressed.isEmpty())
            assertFalse(Files.exists(fixture.temporary))
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
        } finally { fixture.close() }
    }

    @Test fun terminalCleanupFailureIsSuppressedOnTheOriginalCancellation() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.decoder.deleteTemporaryFile = { throw IOException("Controlled terminal cleanup failure") }
            val failure = assertFailsWith<CancellationException> { fixture.cancel() }
            assertEquals("Audio import cancelled", failure.message)
            assertEquals("Controlled terminal cleanup failure", failure.suppressed.single().message)
            assertTrue(fixture.process.streamsClosed)
            assertFalse(fixture.process.isAlive)
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
            // A refused filesystem deletion is explicit failure evidence, never a clean-directory pass.
            assertTrue(Files.exists(fixture.temporary))
        } finally { fixture.close() }
    }

    @Test fun unfinishedOwnedChildKeepsMemoryAndFilesUntilItsActualExit() = runBlocking {
        val fixture = Fixture(exitOnKill = false)
        try {
            val deletions = AtomicInteger()
            fixture.decoder.deleteTemporaryFile = { path ->
                assertFalse(fixture.process.isAlive)
                deletions.incrementAndGet()
                Files.deleteIfExists(path)
            }
            val failure = assertFailsWith<CancellationException> { fixture.cancel() }
            assertTrue(fixture.process.killed)
            assertTrue(fixture.process.isAlive)
            assertTrue(failure.suppressed.any { it.message == "Audio decoder process did not terminate" })
            assertEquals(0, deletions.get())
            assertTrue(Files.exists(fixture.temporary))
            assertEquals(256 * 1024L, PcmMemoryBudget.shared.statistics().usedBytes)
            fixture.process.finish()
            fixture.process.exitCallbacks.join()
            assertFalse(Files.exists(fixture.temporary))
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
        } finally { fixture.close() }
    }

    @Test fun failingStdinCloseStillTerminatesTheOwnedChildAndReleasesItsFiles() = runBlocking {
        val fixture = Fixture(failStdinClose = true)
        try {
            val failure = assertFailsWith<IOException> { fixture.cancel() }
            assertEquals("Controlled stdin close failure", failure.message)
            assertTrue(fixture.process.killed)
            assertTrue(fixture.process.streamsClosed)
            assertFalse(fixture.process.isAlive)
            assertFalse(Files.exists(fixture.temporary))
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
        } finally { fixture.close() }
    }

    @Test fun interruptedCleanupWaitStillFinishesTheChildBeforeDeletionAndRestoresTheFlag() = runBlocking {
        val fixture = Fixture(interruptWait = true)
        try {
            assertFailsWith<CancellationException> { fixture.cancel() }
            assertTrue(Thread.interrupted(), "Cleanup must restore the caller's interrupt flag")
            assertTrue(fixture.process.waitCalls >= 2)
            assertFalse(fixture.process.isAlive)
            assertTrue(fixture.process.streamsClosed)
            assertFalse(Files.exists(fixture.temporary))
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
        } finally { Thread.interrupted(); fixture.close() }
    }

    @Test fun unfinishedDecodeKeepsScratchQuotaUntilTheExactChildExits() = runBlocking {
        val fixture = Fixture(exitOnKill = false, decodePhase = true)
        try {
            assertFailsWith<CancellationException> { fixture.cancel() }
            assertTrue(fixture.process.isAlive)
            assertFailsWith<IllegalArgumentException> { PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).close() }
            assertEquals(256 * 1024L, PcmMemoryBudget.shared.statistics().usedBytes)
            fixture.process.finish()
            assertFalse(Files.exists(fixture.temporary))
            PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).close()
            assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
        } finally { fixture.close() }
    }

    private class Fixture(exitOnKill: Boolean = true, failStdinClose: Boolean = false,
                          interruptWait: Boolean = false, private val decodePhase: Boolean = false) {
        val root = Files.createTempDirectory("codec-cleanup-fixture-")
        val original = root.resolve("source.flac").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val process = OwnedProcess(exitOnKill, failStdinClose, interruptWait)
        lateinit var temporary: Path
        val decoder = DesktopOriginalAudioDecoder(tools = { root.resolve("ffmpeg") to root.resolve("ffprobe") }).also { decoder ->
            decoder.processStarter = { builder ->
                temporary = builder.redirectOutput().file().toPath().parent
                Files.writeString(builder.redirectOutput().file().toPath(), "sample_rate=48000\nchannels=2\n")
                Files.writeString(builder.redirectError().file().toPath(), "")
                if (decodePhase && builder.command().first().endsWith("ffprobe")) {
                    OwnedProcess(true, false, false).also { it.finish() }
                } else {
                    if (decodePhase) Files.write(Path.of(builder.command().last()), ByteArray(8))
                    process
                }
            }
        }
        fun cancel() {
            var checks = 0
            decoder.inspect(original, "unchanged-original") { ++checks >= if (decodePhase) 7 else 4 }
        }
        fun close() {
            process.finish()
            decoder.close()
            if (::temporary.isInitialized) temporary.toFile().deleteRecursively()
            root.toFile().deleteRecursively()
        }
    }

    /** Controlled Process contract; no OS process, device, codec or timing success is claimed. */
    private class OwnedProcess(private val exitOnKill: Boolean, failStdinClose: Boolean,
                               private val interruptWait: Boolean) : Process() {
        private val alive = AtomicBoolean(true)
        private val failStdin = AtomicBoolean(failStdinClose)
        private val closed = BooleanArray(3)
        var killed = false
        var waitedForExit = false
        var waitCalls = 0
        val exitCallbacks = CompletableFuture<Process>()
        val streamsClosed get() = closed.all { it }
        private val stdin = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                closed[0] = true
                if (failStdin.getAndSet(false)) throw IOException("Controlled stdin close failure")
            }
        }
        private fun stream(index: Int) = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed[index] = true }
        }
        private val stdout = stream(1)
        private val stderr = stream(2)
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() { destroyForcibly() }
        override fun destroyForcibly(): Process {
            killed = true
            if (exitOnKill) finish()
            if (interruptWait) Thread.currentThread().interrupt()
            return this
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls++
            if (Thread.interrupted()) throw InterruptedException("Controlled cleanup wait interrupt")
            waitedForExit = true
            return !isAlive
        }
        override fun waitFor(): Int { exitCallbacks.join(); return 0 }
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun onExit(): CompletableFuture<Process> = exitCallbacks
        fun finish() { alive.set(false); exitCallbacks.complete(this) }
    }
}
