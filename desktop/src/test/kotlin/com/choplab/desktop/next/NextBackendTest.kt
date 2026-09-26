package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.Asset
import com.choplab.engine.*
import com.choplab.jvm.WavCodec
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.test.*

/** UI-independent, fake-output integration tests. Never opens a native audio device or window. */
class NextBackendTest {
    private fun temporary(): Path {
        val parent = Path.of(System.getProperty("choplab.next.testDir", "build/tmp/next-tests"))
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, "run-")
    }
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No file loading in driver-only tests")
    })
    private fun program(revision: Long = 1): EngineProgram {
        val samples = FloatArray(8192) { if (it % 2 == 0) .3f else -.09f }
        return EngineProgram(listOf(Pad(0, PcmAsset.fromInterleaved(samples), mode = PlayMode.LOOP, attackFrames = 0, releaseFrames = 96)), revision = revision)
    }

    @Test fun actualFilesStudioEngineSaveReopenUndoAndBothWavDepths() = runBlocking<Unit> {
        val receipt = NextSelfTest.run(temporary())
        assertTrue(receipt.exportFrames > 0 && receipt.renderedFrames > 0)
        assertTrue(receipt.leftEnergy > receipt.rightEnergy && receipt.rightEnergy > 0)
        assertNotEquals(receipt.wav16Hash, receipt.wav24Hash)
        val projectFile = receipt.runDirectory.resolve("roundtrip.choplab")
        ZipFile(projectFile.toFile()).use { archive ->
            val json = archive.getInputStream(archive.getEntry("project.json")).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertFalse(json.contains(receipt.runDirectory.toAbsolutePath().toString()))
            assertFalse(json.contains("handle"))
            assertTrue(archive.entries().asSequence().any { it.name.startsWith("assets/") })
        }
    }

    @Test fun floatOutputKeepsStereoAndAcceptsAppliedLateAcknowledgement() = runBlocking<Unit> {
        val sink = CaptureSink(SinkEncoding.FLOAT32)
        val driver = JavaSoundEnginePort(compiler(), { sink })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            waitUntil { driver.snapshot().frame >= 512 }
            assertTrue(driver.apply(EngineCommand.Trigger(0, 2, 0)))
            val receipt = requireNotNull(driver.lastReceipt)
            assertTrue(receipt.acknowledged && receipt.appliedLate && receipt.appliedFrame > receipt.requestedFrame)
            waitUntil { sink.leftEnergy > 1 && sink.rightEnergy > .1 }
            assertTrue(sink.leftEnergy > sink.rightEnergy * 5)
            assertTrue(driver.apply(EngineCommand.Stop(driver.snapshot().frame, 3)))
            waitUntil { driver.snapshot().activeVoices == 0 }
            assertFalse(driver.snapshot().playing)
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 3, 0)), "Duplicate command order must be refused")
        } finally { driver.close() }
        assertTrue(sink.closed)
        assertEquals(1, sink.owners.size, "One thread owns output write and close")
    }

    @Test fun pcm16DitherFallbackHandlesPartialFrameAlignedWrites() = runBlocking<Unit> {
        val sink = CaptureSink(SinkEncoding.PCM16, partial = true)
        val driver = JavaSoundEnginePort(compiler(), { sink })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            waitUntil { sink.leftEnergy > 1 && sink.rightEnergy > .1 }
            assertTrue(sink.leftEnergy > sink.rightEnergy * 5)
            assertTrue(sink.frames > 0)
            assertEquals(SinkEncoding.PCM16, driver.status.value.encoding)
        } finally { driver.close() }
    }

    @Test fun cancelledFutureCommandCannotSoundLaterAndEditingRetainsMonotonicClock() = runBlocking<Unit> {
        val sink = CaptureSink(SinkEncoding.FLOAT32)
        val driver = JavaSoundEnginePort(compiler(), { sink })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            val before = driver.snapshot().frame
            val pending = async { driver.apply(EngineCommand.Trigger(before + 48_000 * 600, 2, 0)) }
            delay(30)
            pending.cancelAndJoin()
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertFalse(driver.snapshot().outputAttached)
            assertEquals(0, driver.snapshot().activeVoices)
            assertTrue(driver.snapshot().frame >= before)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 3, program(2))))
            assertEquals(2, driver.snapshot().programRevision)
            assertFalse(driver.apply(EngineCommand.StartSequence(driver.snapshot().frame, 4)))
            assertTrue(sink.leftEnergy < .001, "The cancelled future trigger must not leak into a later program")
        } finally { driver.close() }
    }

    @Test fun outputFailureDetachesButKeepsDocumentEditsAvailable() = runBlocking<Unit> {
        val driver = JavaSoundEnginePort(compiler(), { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = error("Injected write failure")
            override fun close() = Unit
        } })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(DriverFault.WRITE_FAILED, driver.status.value.fault)
            assertTrue(driver.apply(EngineCommand.SwapProgram(driver.snapshot().frame, 1, program())))
            assertEquals(1, driver.snapshot().programRevision)
            assertFalse(driver.snapshot().outputAttached)
        } finally { driver.close() }
    }

    @Test fun missingOutputStillAllowsRealImportAssignmentSaveAndExport() = runBlocking<Unit> {
        val dir = temporary()
        val demo = dir.resolve("Demo.wav")
        NextSelfTest.writeDemo(demo)
        val backend = NextBackend.create(dir.resolve("profile"), sinkFactory = { error("No device in test") })
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            assertTrue(backend.importAudio(demo).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetNote("pattern-1", com.choplab.core.model.Note(0, 0), true))).accepted)
            assertFalse(backend.studio.dispatch(Action.Play).accepted)
            val archive = dir.resolve("silent-edit.choplab")
            assertTrue(backend.saveProject(archive).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(archive) }
            val output = dir.resolve("offline.wav")
            assertTrue(backend.exportPattern(output, 24).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(output) }
            val audio = Files.newInputStream(output).use { WavCodec.read(it) }
            assertTrue(audio.samples.any { it > .01f })
        } finally { backend.shutdown() }
    }

    @Test fun emergencyStopIsNotBlockedBehindFutureAcknowledgement() = runBlocking<Unit> {
        val sink = CaptureSink(SinkEncoding.FLOAT32)
        val driver = JavaSoundEnginePort(compiler(), { sink })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            val future = async { driver.apply(EngineCommand.Trigger(driver.snapshot().frame + 48_000 * 600, 2, 0)) }
            delay(20)
            assertTrue(withTimeout(500) { driver.apply(EngineCommand.Stop(driver.snapshot().frame, 3)) })
            assertFalse(future.await())
            assertTrue(sink.leftEnergy < .001)
        } finally { driver.close() }
    }

    @Test fun monitoringAndStudioUseIndependentLogicalIdsButEachRejectsReplay() = runBlocking<Unit> {
        val driver = JavaSoundEnginePort(compiler(), { CaptureSink(SinkEncoding.FLOAT32) })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            assertTrue(driver.applyMonitoring(EngineCommand.SetSongMonitorGain(driver.snapshot().frame, 1, .5f)))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            assertFalse(driver.applyMonitoring(EngineCommand.SetSongMonitorGain(driver.snapshot().frame, 1, 1f)))
            assertFalse(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            assertFailsWith<IllegalArgumentException> {
                driver.applyMonitoring(EngineCommand.SwapProgram(driver.snapshot().frame, 2, program(99)))
            }
        } finally { driver.close() }
    }

    @Test fun cancelledOriginalDecodeCannotLoadOrStartAfterStop() = runBlocking<Unit> {
        val driver = JavaSoundEnginePort(compiler(), { CaptureSink(SinkEncoding.FLOAT32) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audition = com.choplab.jvm.SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = withContext(NonCancellable) {
                entered.complete(Unit); release.await()
                PcmAsset.fromInterleaved(FloatArray(2048) { .2f })
            }
        }, scope)
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            val pending = async { runCatching { audition.play(Asset("c".repeat(64), "wav", 44, 48_000, 2, 1024, "source")) } }
            entered.await()
            audition.cancelPreparation()
            assertTrue(audition.pause())
            release.complete(Unit)
            pending.await()
            delay(30)
            assertFalse(driver.originalPlayback().loaded)
            assertFalse(driver.originalPlayback().playing)
        } finally { release.complete(Unit); audition.close(); scope.cancel(); driver.close() }
    }

    @Test fun monitoringMuteIsTrueZeroAndLeavesProgramAndVoicesIntact() = runBlocking<Unit> {
        val sink = CaptureSink(SinkEncoding.PCM16)
        val driver = JavaSoundEnginePort(compiler(), { sink })
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(driver.apply(EngineCommand.SwapProgram(0, 1, program())))
            assertTrue(driver.apply(EngineCommand.Trigger(driver.snapshot().frame, 2, 0)))
            waitUntil { sink.leftEnergy > 1 }
            driver.setMonitorGain(0f)
            waitUntil { sink.lastWriteZero }
            assertTrue(driver.snapshot().activeVoices > 0)
            assertEquals(1L, driver.snapshot().programRevision)
            driver.setMonitorGain(1f)
            waitUntil { !sink.lastWriteZero }
        } finally { driver.close() }
    }

    @Test fun autosaveRestoresTheActualDocumentAndRevisionWithoutAddingStarterMusic() = runBlocking<Unit> {
        val dir = temporary()
        val input = dir.resolve("Original.wav")
        NextSelfTest.writeDemo(input)
        val profile = dir.resolve("profile")
        val first = NextBackend.create(profile, sinkFactory = { error("No device") })
        val saved: DocumentState
        try {
            assertTrue(first.importAudio(input).accepted)
            waitUntil { first.studio.work.value.jobId == null && first.studio.document.value.project.source != null }
            assertTrue(first.studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
            first.flushAutosave()
            saved = first.studio.document.value
        } finally { first.shutdown() }
        val second = NextBackend.create(profile, sinkFactory = { error("No device") })
        try {
            assertEquals(saved.project, second.studio.document.value.project)
            assertEquals(saved.revision, second.studio.document.value.revision)
        } finally { second.shutdown() }
    }

    @Test fun originalAuditionKeepsItsCursorAcrossSongStopAndCannotChangeOfflineWav() = runBlocking<Unit> {
        val dir = temporary()
        val input = dir.resolve("Original.wav")
        NextSelfTest.writeDemo(input)
        val backend = NextBackend.create(dir.resolve("profile"), { CaptureSink(SinkEncoding.FLOAT32) })
        suspend fun export(name: String): ByteArray {
            val file = dir.resolve(name)
            assertTrue(backend.exportPattern(file, 24, 4096).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.exists(file) }
            return Files.readAllBytes(file)
        }
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(backend.importAudio(input).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetNote("pattern-1", com.choplab.core.model.Note(0, 0), true))).accepted)
            val before = export("before.wav")
            val p = backend.studio.document.value.project
            assertTrue(backend.audition.play(p.asset(requireNotNull(p.source).assetHash)))
            waitUntil { backend.audition.nativeFrame() > 256 }
            assertTrue(backend.studio.dispatch(Action.Stop).accepted)
            assertTrue(backend.engine.originalPlayback().playing)
            assertTrue(backend.audition.originalGain(.25f))
            assertTrue(backend.audition.songGain(0f))
            assertContentEquals(before, export("after.wav"))
            assertTrue(backend.audition.pause())
            val position = backend.audition.nativeFrame()
            delay(30)
            assertEquals(position, backend.audition.nativeFrame())
        } finally { backend.shutdown() }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    private class CaptureSink(override val encoding: SinkEncoding, private val partial: Boolean = false) : AudioSink {
        @Volatile var frames = 0L
        @Volatile var leftEnergy = 0.0
        @Volatile var rightEnergy = 0.0
        @Volatile var closed = false
        @Volatile var lastWriteZero = false
        val owners = ConcurrentHashMap.newKeySet<Long>()
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            owners += Thread.currentThread().threadId()
            check(!closed)
            val frameBytes = encoding.bytesPerSample * 2
            val count = if (partial) minOf(length, 64 * frameBytes) else length
            fun sample(at: Int): Float = if (encoding == SinkEncoding.PCM16) {
                (((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort()).toFloat() / 32768
            } else Float.fromBits((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24))
            var left = leftEnergy; var right = rightEnergy
            for (at in offset until offset + count step frameBytes) {
                val a = sample(at); val b = sample(at + encoding.bytesPerSample)
                assertTrue(a.isFinite() && b.isFinite())
                left += a.toDouble() * a; right += b.toDouble() * b
            }
            leftEnergy = left; rightEnergy = right; frames += count / frameBytes
            lastWriteZero = (offset until offset + count).all { bytes[it] == 0.toByte() }
            LockSupport.parkNanos(count.toLong() / frameBytes * 1_000_000_000L / 48_000)
            return count
        }
        override fun close() { owners += Thread.currentThread().threadId(); closed = true }
    }
}
