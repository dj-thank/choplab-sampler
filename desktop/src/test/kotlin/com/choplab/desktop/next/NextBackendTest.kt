package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.Asset
import com.choplab.core.model.frozen
import com.choplab.core.model.frozenListOf
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

    @Test fun aRecordedTakePlaysInTheSongAndSurvivesSaveAndAutosave() = runBlocking<Unit> {
        val dir = temporary()
        val profile = dir.resolve("profile")
        // A microphone that hears a tenth of a second of tone, then nothing until it is stopped.
        val mic = object : com.choplab.jvm.MicInput {
            override val sampleRate = 48_000
            @Volatile var stopped = false
            @Volatile var delivered = false
            override fun read(buffer: FloatArray): Int {
                if (!delivered) {
                    Thread.sleep(100)
                    val count = minOf(buffer.size, 4_800)
                    for (i in 0 until count) buffer[i] = (.3 * kotlin.math.sin(2 * Math.PI * 440 * i / 48_000)).toFloat()
                    delivered = true
                    return count
                }
                while (!stopped) Thread.sleep(2)
                return -1
            }
            override fun stop() { stopped = true }
            override fun close() = Unit
        }
        val first = NextBackend.create(profile, sinkFactory = { error("No device") }, microphone = { mic })
        val saved: DocumentState
        try {
            assertEquals(com.choplab.jvm.VoiceTakes.Start.STARTED, first.voice.start(5))
            first.voice.cue()
            waitUntil { mic.delivered }
            val take = assertNotNull(first.voice.stop("VOICE 1"))
            assertEquals(com.choplab.core.model.AssetRole.ORIGINAL, take.asset.role)
            val track = com.choplab.core.model.Track("voice", "VOICE", com.choplab.core.model.TrackKind.VOCAL)
            // This stub delivers its first frames late, so the lead-in may be negative: nothing to cut then.
            val range = com.choplab.core.model.FrameRange(take.leadFrames.coerceAtLeast(0), take.asset.frames)
            val pad = com.choplab.core.model.Pad(48, take.asset.hash, com.choplab.core.model.FrameRange(0, take.asset.frames), "VOICE 1", gain = .9f)
            val clip = com.choplab.core.model.Clip("take-1", track.id, take.asset.hash, range, timelineStartFrame = 0)
            // The real compiler loads the float take for its PAD and the song.
            assertTrue(first.studio.dispatch(Action.Edit(Intent.AddVoiceTake(take.asset, pad, clip, track))).accepted)
            assertTrue(first.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
            val song = dir.resolve("song.wav")
            assertTrue(first.studio.dispatch(Action.Export(ExportRequest(first.files.register(song), range.length.toInt(), bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            waitUntil { first.studio.work.value.jobId == null && Files.isRegularFile(song) }
            val audio = Files.newInputStream(song).use { WavCodec.read(it) }
            val peak = audio.samples.maxOf { kotlin.math.abs(it) }
            assertTrue(peak in .2f..1f, "The take sounds in the song: peak $peak")
            val archive = dir.resolve("voice.choplab")
            assertTrue(first.saveProject(archive).accepted)
            waitUntil { first.studio.work.value.jobId == null && Files.isRegularFile(archive) }
            ZipFile(archive.toFile()).use { zip -> assertNotNull(zip.getEntry("assets/${take.asset.hash}.wav"), "The take travels with the project") }
            first.flushAutosave()
            saved = first.studio.document.value
        } finally { first.shutdown() }
        assertEquals(0, Files.list(profile.resolve("voice-scratch")).use { it.count() }, "No scratch file is left")
        val second = NextBackend.create(profile, sinkFactory = { error("No device") }, microphone = { null })
        try { assertEquals(saved.project, second.studio.document.value.project) } finally { second.shutdown() }
    }

    @Test fun aPitchedPadIsRenderedPlacedHeardInTheSongAndSaved() = runBlocking<Unit> {
        val dir = temporary()
        val backend = NextBackend.create(dir.resolve("profile"), sinkFactory = { error("No device") })
        try {
            val kick = backend.prepareDrumKit("boom-bap").first()
            val pad = com.choplab.core.model.Pad(0, kick.hash, com.choplab.core.model.FrameRange(0, kick.frames), "KICK", pitchSemitones = 12.0, gain = .8f)
            val rendered = backend.renderPad(pad, kick)
            assertEquals(com.choplab.core.model.AssetRole.RENDERED, rendered.role)
            assertEquals(kick.hash, rendered.derivedFrom)
            assertEquals(48_000, rendered.sampleRate)
            assertEquals(2, rendered.channels)
            assertEquals((kick.frames + 1) / 2, rendered.frames, "An octave up halves its length")
            assertEquals(rendered, backend.renderPad(pad, kick), "The same PAD renders to the same sound")
            // Placed on the song as plain audio, with the PAD's level, next to the PAD's own sound.
            val track = com.choplab.core.model.Track("drums", "B", com.choplab.core.model.TrackKind.BANK)
            val clip = com.choplab.core.model.Clip("kick-up", track.id, rendered.hash, com.choplab.core.model.FrameRange(0, rendered.frames),
                timelineStartFrame = 0, gain = pad.gain)
            val start = backend.studio.document.value.project
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.ApplyKit(frozenListOf(kick), frozenListOf(pad)))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement((start.tracks + track).frozen(), frozenListOf(clip),
                start.takes, frozenListOf(rendered)))).accepted)
            val song = dir.resolve("song.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(song), rendered.frames.toInt(), bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(song) }
            val peak = Files.newInputStream(song).use { WavCodec.read(it) }.samples.maxOf { kotlin.math.abs(it) }
            assertTrue(peak > .1f, "The rendered kick sounds in the song: peak $peak")
            val archive = dir.resolve("rendered.choplab")
            assertTrue(backend.saveProject(archive).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(archive) }
            ZipFile(archive.toFile()).use { zip -> assertNotNull(zip.getEntry("assets/${rendered.hash}.wav"), "The rendered sound travels with the project") }
        } finally { backend.shutdown() }
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
