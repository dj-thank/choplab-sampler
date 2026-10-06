package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.Asset
import com.choplab.core.model.Note
import com.choplab.core.model.Project
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Shared composition root without a device, window or dialog. */
class EditorBackendTest {
    private fun directory(): Path = Files.createTempDirectory("choplab-backend-")
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    /** Path-backed services that count how often the backend reaches each host port. */
    private class CountingFiles {
        val paths = ConcurrentHashMap<String, Path>()
        val imports = AtomicInteger()
        val saves = AtomicInteger()
        val opens = AtomicInteger()
        val exports = AtomicInteger()
        fun register(path: Path) = Location(UUID.randomUUID().toString()).also { paths[it.handle] = path }
        fun resolve(location: Location): Path = requireNotNull(paths[location.handle])
        fun services(assets: FileAssetStore, compiler: ProgramCompiler): HostFileServices {
            val importer = WavImportPort(assets, ::resolve)
            val projects = FileProjectPort(assets, ::resolve)
            val exporter = WavExportPort(compiler, ::resolve)
            return HostFileServices(
                object : ImportPort { override suspend fun import(location: Location): Asset = importer.import(location).also { imports.incrementAndGet() } },
                object : ProjectPort {
                    override suspend fun save(project: Project, revision: Long, location: Location) { projects.save(project, revision, location); saves.incrementAndGet() }
                    override suspend fun open(location: Location): Project = projects.open(location).also { opens.incrementAndGet() }
                },
                object : ExportPort {
                    override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt =
                        exporter.export(project, patternId, request).also { exports.incrementAndGet() }
                    override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt =
                        exporter.export(project, target, request).also { exports.incrementAndGet() }
                })
        }
    }

    private fun silentEngine(compiler: ProgramCompiler) = StreamingEnginePort(compiler, { error("No device in test") })

    @Test fun hostFileServicesCarryImportSaveOpenAndExport() = runBlocking<Unit> {
        val dir = directory()
        val input = dir.resolve("Loop.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(4096) { (it * 37 % 2000 - 1000).toShort() })) }
        val files = CountingFiles()
        val backend = EditorBackend.create(dir.resolve("profile"), ::silentEngine, files::services)
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            assertTrue(backend.studio.dispatch(Action.Import(files.register(input))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetNote("pattern-1", Note(0, 0), true))).accepted)
            val archive = dir.resolve("song.choplab")
            assertTrue(backend.studio.dispatch(Action.Save(files.register(archive))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(archive) }
            assertTrue(backend.studio.dispatch(Action.Open(files.register(archive))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && files.opens.get() == 1 }
            val wav = dir.resolve("beat.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(files.register(wav), 4096, bits = 16))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && Files.isRegularFile(wav) }
            assertEquals(listOf(1, 1, 1, 1), listOf(files.imports.get(), files.saves.get(), files.opens.get(), files.exports.get()))
            assertTrue(backend.loadPeaks(backend.studio.document.value.project.assets.first(), 16).any { it > 0f })
        } finally { backend.shutdown() }
        assertEquals(DriverPhase.CLOSED, backend.engine.status.value.phase)
    }

    @Test fun aPerformedGatePublishesFloatAudioWithoutChangingTheOriginalOrDocument() = runBlocking<Unit> {
        val dir = directory()
        val input = dir.resolve("gate.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(4096) { (it * 37 % 2000 - 1000).toShort() })) }
        val files = CountingFiles()
        val backend = EditorBackend.create(dir.resolve("profile"), ::silentEngine, files::services)
        try {
            assertTrue(backend.studio.dispatch(Action.Import(files.register(input))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            val before = backend.studio.document.value.project
            val source = before.asset(requireNotNull(before.source).assetHash)
            val original = backend.assets.read(source)
            val pad = com.choplab.core.model.Pad(0, source.hash, com.choplab.core.model.FrameRange(0, source.frames),
                mode = com.choplab.engine.PlayMode.GATE, gain = .6f, pan = -.2f)
            val result = backend.renderPerformance(pad, source, 480, 2_000)
            assertEquals(576L, result.frames, "480 held frames plus the live 96-frame release")
            assertEquals(com.choplab.core.model.AssetRole.RENDERED, result.role)
            assertEquals(source.hash, result.derivedFrom)
            assertEquals(48_000, result.sampleRate); assertEquals(2, result.channels)
            val rendered = WavCodec.read(java.io.ByteArrayInputStream(backend.assets.read(result)))
            assertEquals(32, rendered.info.bits)
            assertTrue(rendered.info.floatingPoint)
            assertTrue(backend.assets.containsVerified(result))
            assertEquals(result, backend.renderPerformance(pad, source, 480, 2_000), "Identical performance reuses content-addressed bytes")
            assertContentEquals(original, backend.assets.read(source))
            assertEquals(before, backend.studio.document.value.project, "Publication alone never edits the song")
        } finally { backend.shutdown() }
    }

    @Test fun placedPadPanPublishesBeforeBankMixAndPreservesSourceAndSharedBudget() = runBlocking<Unit> {
        val dir = directory()
        val input = dir.resolve("pan.wav")
        val samples = FloatArray(4096 * 2) { i ->
            (kotlin.math.sin(i / 2 * .07) * if (i % 2 == 0) .16 else -.07).toFloat()
        }
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples) }
        val files = CountingFiles()
        val beforeBudget = PcmMemoryBudget.shared.statistics().usedBytes
        val backend = EditorBackend.create(dir.resolve("profile"), ::silentEngine, files::services)
        try {
            assertTrue(backend.studio.dispatch(Action.Import(files.register(input))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            val before = backend.studio.document.value
            val source = before.project.asset(requireNotNull(before.project.source).assetHash)
            val original = backend.assets.read(source)
            val pad = com.choplab.core.model.Pad(0, source.hash, com.choplab.core.model.FrameRange(100, 3_000),
                gain = .23f, pan = .8f, reverse = true, pitchSemitones = -3.0, tone = .4f,
                attackFrames = 500, decayFrames = 300, sustainLevel = .2f, releaseFrames = 900)
            val result = backend.renderPad(pad, source)
            val rendered = WavCodec.read(java.io.ByteArrayInputStream(backend.assets.read(result)))
            val voice = ProgramCompiler.enginePad(pad.copy(gain = 1f, attackFrames = 0, decayFrames = 0,
                sustainLevel = 1f, releaseFrames = 1), source, com.choplab.engine.PcmAsset.fromInterleaved(samples))
            val live = com.choplab.engine.OfflineRender.render(com.choplab.engine.EngineProgram(listOf(voice)),
                listOf(com.choplab.engine.EngineCommand.Trigger(0, 0, 0)), rendered.samples.size / 2)
            for (i in 0 until live.size - 4) assertEquals(live[i], rendered.samples[i], 1e-6f, "Pan sample $i")
            assertTrue(rendered.info.floatingPoint); assertEquals(32, rendered.info.bits)
            assertEquals(com.choplab.core.model.AssetRole.RENDERED, result.role); assertEquals(source.hash, result.derivedFrom)
            assertEquals(result, backend.renderPad(pad.copy(gain = .9f, attackFrames = 0, releaseFrames = 1,
                decayFrames = 0, sustainLevel = 1f), source), "Gain and envelope remain outside the placed audio")
            val otherPan = backend.renderPad(pad.copy(pan = -.8f), source)
            assertNotEquals(result.hash, otherPan.hash, "Each direction retains its own stereo pan")
            val count = Files.list(dir.resolve("profile/assets")).use { it.count() }
            val memory = PcmMemoryBudget.shared
            memory.reserve(memory.limitBytes - memory.statistics().usedBytes).use {
                assertFailsWith<PcmMemoryLimit> { backend.renderPad(pad.copy(pan = .4f), source) }
            }
            assertEquals(count, Files.list(dir.resolve("profile/assets")).use { it.count() }, "Admission refusal publishes no partial asset")
            assertEquals(result, backend.renderPad(pad, source), "Refusal returns the borrowed source and output reservation")
            assertContentEquals(original, backend.assets.read(source)); assertContentEquals(original, Files.readAllBytes(input))
            assertEquals(before, backend.studio.document.value)
        } finally { backend.shutdown(); dir.toFile().deleteRecursively() }
        assertEquals(beforeBudget, PcmMemoryBudget.shared.statistics().usedBytes)
    }

    @Test fun noteRepeatReadsLateLongSourceWindowsAndRefusesOversizedOutputBeforePublishing() = runBlocking<Unit> {
        val dir = directory()
        val files = CountingFiles()
        var reads = 0; var opens = 0; var closes = 0
        val info = WavInfo(48_000, 2, 19_200_000, 32, true)
        fun sample(frame: Int, channel: Int) = ((frame % 257 - 128) / 2048f) * if (channel == 0) 1f else -.4f
        val decoder = object : OriginalAudioDecoder {
            override fun inspect(path: Path, hash: String, cancelled: () -> Boolean) = info
            override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = error("Long source cannot be resident")
            override fun openPcm(path: Path, hash: String, cancelled: () -> Boolean): PcmFrameSource {
                opens++
                return object : PcmFrameSource {
                    override val info = info
                    override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean): FloatArray {
                        assertTrue(frameCount in 1..4096, "Bounded native read: $frameCount")
                        reads++
                        return FloatArray(frameCount * 2) { sample(firstFrame + it / 2, it % 2) }
                    }
                    override fun close() { closes++ }
                }
            }
        }
        val backend = EditorBackend.create(dir.resolve("profile"), ::silentEngine, files::services, decoder)
        try {
            val original = byteArrayOf(9)
            val source = Asset(sha256(original), "flac", 1, info.sampleRate, info.channels, info.frames, "Long original")
            backend.assets.publish(source, java.io.ByteArrayInputStream(original))
            val before = backend.studio.document.value
            val first = 18_000_000L
            val pad = com.choplab.core.model.Pad(0, source.hash, com.choplab.core.model.FrameRange(first, first + 4_800),
                mode = com.choplab.engine.PlayMode.ONE_SHOT, gain = .6f, pan = -.2f, reverse = true, pitchSemitones = -3.0, tone = .4f)
            val tempo = com.choplab.engine.Tempo(97_125, 710)
            val rendered = backend.renderNoteRepeat(pad, source, tempo, 160, 48_000, 60_000)
            assertEquals(48_096, rendered.frames)
            assertEquals(source.hash, rendered.derivedFrom)
            val pcm = WavCodec.read(java.io.ByteArrayInputStream(backend.assets.read(rendered)))
            // Keep the same absolute fractional source coordinates as live playback, including
            // their double precision at a late position; a rebased short sample is a different oracle.
            val pages = com.choplab.engine.PagedPcm(info.frames.toInt())
            for (page in (first.toInt() / 4096 - 1)..((first.toInt() + 4_800) / 4096 + 1)) {
                pages.publish(page, FloatArray(4096 * 2) { sample(page * 4096 + it / 2, it % 2) })
            }
            val expected = com.choplab.engine.NoteRepeatRender.render(ProgramCompiler.enginePad(
                pad, source, com.choplab.engine.PcmAsset.paged(pages)), tempo, 160, 48_000, 60_000) { _, render -> render() }
            assertContentEquals(expected, pcm.samples)
            assertTrue(pcm.info.floatingPoint)
            val count = Files.list(dir.resolve("profile/assets")).use { it.count() }
            val maximum = com.choplab.engine.PadRender.MAX_FRAMES
            assertFailsWith<PcmMemoryLimit> { backend.renderNoteRepeat(pad, source, tempo, 160, maximum, maximum) }
            assertEquals(count, Files.list(dir.resolve("profile/assets")).use { it.count() }, "Refusal publishes no partial asset")
            assertEquals(rendered, backend.renderNoteRepeat(pad, source, tempo, 160, 48_000, 60_000), "A failed admission returns its leases")
            assertTrue(reads > 0); assertEquals(1, opens)
            assertEquals(before, backend.studio.document.value)
            assertContentEquals(original, backend.assets.read(source))
        } finally { backend.shutdown() }
        assertEquals(opens, closes)
    }

    @Test fun routedOverdubReservesOnePeriodAndForwardQuantizeOnlyAndRefusesBeforePartialAllocation() = runBlocking<Unit> {
        val files = CountingFiles()
        val backend = EditorBackend.create(directory().resolve("profile"), ::silentEngine, files::services)
        val captures = mutableListOf<LoopOverdubCapture>()
        val routes = List(4) { com.choplab.engine.LoopOverdubRoute("bank-$it") }
        try {
            val before = backend.studio.document.value
            val maximum = com.choplab.engine.LoopOverdub.MAX_FRAMES
            val grid = IntArray(33) { it * 72_000 }
            assertEquals(75_142_176, com.choplab.engine.LoopOverdub.memoryBytes(maximum, grid, 4))
            captures += backend.createLoopOverdub(0, maximum, grid, routes)
            assertFailsWith<PcmMemoryLimit> { backend.createLoopOverdub(0, maximum, grid, routes) }
            assertEquals(before, backend.studio.document.value)
            captures.removeAt(0).close()
            captures += backend.createLoopOverdub(0, maximum, grid, routes)
            assertFailsWith<PcmMemoryLimit> { backend.createLoopOverdub(0, maximum, grid, routes) }
            captures.forEach { it.close() }; captures.clear()
            val eight = List(8) { com.choplab.engine.LoopOverdubRoute("bank-$it") }
            assertFailsWith<PcmMemoryLimit> { backend.createLoopOverdub(0, maximum, grid, eight) }
            val small = backend.createLoopOverdub(0, 48_000, intArrayOf(), eight)
            assertEquals(8, small.take.routeCount)
            small.close(); small.close()
            assertEquals(before, backend.studio.document.value)
        } finally { captures.forEach { it.close() }; backend.shutdown() }
    }

    @Test fun importsCompleteWhileTheOutputKeepsStallingAndReopening() = runBlocking<Unit> {
        // Like an emulator or a flaky route: every device fills up and stalls, and a recovery policy keeps reopening it.
        val stalling = { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            private var accepted = 0
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                if (accepted >= 16_384) 0 else length.also { accepted += it; java.util.concurrent.locks.LockSupport.parkNanos(1_000_000) }
            override fun close() = Unit
        } }
        val dir = directory()
        val files = CountingFiles()
        val backend = EditorBackend.create(dir.resolve("profile"), { StreamingEnginePort(it, stalling, acknowledgementMillis = 300) }, files::services)
        val flapping = launch(Dispatchers.Default) { while (isActive) { backend.engine.reattach(); delay(37) } }
        try {
            repeat(6) { round ->
                val input = dir.resolve("Loop-$round.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(4096) { i -> ((i * (round + 3)) % 2000 - 1000).toShort() })) }
                val before = backend.studio.document.value.project.source
                assertTrue(backend.studio.dispatch(Action.Import(files.register(input))).accepted)
                waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != before }
                assertEquals("Loop-$round.wav", backend.studio.document.value.project.asset(backend.studio.document.value.project.source!!.assetHash).name)
            }
        } finally { flapping.cancelAndJoin(); backend.shutdown() }
    }

    @Test fun importsCompleteWhileTheFirstDeviceIsStillOpening() = runBlocking<Unit> {
        // Like the CI emulator: creating the first output takes several seconds, then the device plays normally.
        val opens = AtomicInteger()
        val slowFirst = {
            if (opens.getAndIncrement() == 0) Thread.sleep(3_000)
            object : AudioSink {
                override val encoding = SinkEncoding.FLOAT32
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                    length.also { java.util.concurrent.locks.LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000) }
                override fun close() = Unit
            }
        }
        val dir = directory()
        val files = CountingFiles()
        val backend = EditorBackend.create(dir.resolve("profile"), { StreamingEnginePort(it, slowFirst) }, files::services)
        val notices = java.util.concurrent.CopyOnWriteArrayList<Notice>()
        val listening = launch(Dispatchers.Default) { backend.studio.notices.collect { notices += it } }
        try {
            val input = dir.resolve("Loop.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(9_600) { i -> (i % 2000 - 1000).toShort() })) }
            val before = backend.studio.document.value.project.source
            val started = backend.studio.dispatch(Action.Import(files.register(input)))
            assertTrue(started.accepted, "$started")
            waitUntil { backend.studio.work.value.jobId == null }
            assertNotEquals(before, backend.studio.document.value.project.source, "Import was not kept: notices $notices, output ${backend.engine.status.value}")
        } finally { listening.cancelAndJoin(); backend.shutdown() }
    }

    @Test fun theOriginalPlaysAtTheDocumentSongKeyThroughEditsUndoAndARebuiltOutput() = runBlocking<Unit> {
        val lost = java.util.concurrent.atomic.AtomicBoolean(false)
        val paced = { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(!lost.get()) { "Audio output route changed" }
                java.util.concurrent.locks.LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                return length
            }
            override fun close() = Unit
        } }
        val dir = directory()
        val files = CountingFiles()
        val backend = EditorBackend.create(dir.resolve("profile"), { StreamingEnginePort(it, paced) }, files::services)
        /** Source frames the original moves per output frame over a short stretch of playback. */
        suspend fun rate(): Double {
            val source = backend.engine.originalPlayback().sourceFrame
            val output = backend.engine.snapshot().frame
            delay(200)
            return (backend.engine.originalPlayback().sourceFrame - source).toDouble() / (backend.engine.snapshot().frame - output)
        }
        suspend fun rateBecomes(expected: Double) = withTimeout(5_000) { while (kotlin.math.abs(rate() - expected) > expected * .05) Unit }
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val input = dir.resolve("Song.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(48_000 * 2 * 30) { i -> ((i / 2) % 480 * 60 - 14_000).toShort() })) }
            assertTrue(backend.studio.dispatch(Action.Import(files.register(input))).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            val project = backend.studio.document.value.project
            val asset = project.asset(project.source!!.assetHash)
            assertTrue(backend.audition.play(asset))
            rateBecomes(1.0)

            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetSourcePitch(12.0))).accepted)
            rateBecomes(2.0)
            // A lost and reopened output rebuilds the engine without the original; playing again brings the key back.
            lost.set(true)
            waitUntil { backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            lost.set(false)
            assertTrue(backend.engine.reattach())
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(backend.audition.play(asset))
            rateBecomes(2.0)
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            rateBecomes(1.0)
        } finally { backend.shutdown() }
    }

    @Test fun shutdownKeepsTheDocumentForTheNextLaunch() = runBlocking<Unit> {
        val dir = directory()
        val input = dir.resolve("Voice.wav").also { Files.write(it, Fixtures.wav(samples = ShortArray(2048) { (it % 400 - 200).toShort() })) }
        val profile = dir.resolve("profile")
        val files = CountingFiles()
        val first = EditorBackend.create(profile, ::silentEngine, files::services)
        val saved: DocumentState
        try {
            assertTrue(first.studio.dispatch(Action.Import(files.register(input))).accepted)
            waitUntil { first.studio.work.value.jobId == null && first.studio.document.value.project.source != null }
            assertTrue(first.studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
            saved = first.studio.document.value
        } finally { first.shutdown() }
        val second = EditorBackend.create(profile, ::silentEngine, CountingFiles()::services)
        try {
            assertEquals(saved.project, second.studio.document.value.project)
            assertEquals(saved.revision, second.studio.document.value.revision)
        } finally { second.shutdown() }
    }

    @Test fun failedCompositionClosesTheOutputItAlreadyStarted() {
        val started = mutableListOf<StreamingEnginePort>()
        val failure = assertFailsWith<IllegalStateException> {
            EditorBackend.create(directory().resolve("profile"), { compiler -> silentEngine(compiler).also { started += it } },
                { _, _ -> error("Host file services unavailable") })
        }
        assertEquals("Host file services unavailable", failure.message)
        assertEquals(1, started.size)
        assertEquals(DriverPhase.CLOSED, started.single().status.value.phase)
    }

    @Test fun unreadableAutosaveStopsBeforeAnythingStartsAndKeepsTheFiles() {
        val profile = directory().resolve("profile")
        val damaged = profile.resolve("autosave").also { Files.createDirectories(it) }.resolve("autosave.0.json")
        Files.writeString(damaged, "{ not a project")
        val engines = AtomicInteger()
        assertFailsWith<IllegalStateException> {
            EditorBackend.create(profile, { compiler -> engines.incrementAndGet(); silentEngine(compiler) }, CountingFiles()::services)
        }
        assertEquals(0, engines.get(), "No output may start before the saved document is recovered")
        assertEquals("{ not a project", Files.readString(damaged))
    }

    @Test fun failedFinalAutosaveAsksInsteadOfTrappingTheEditor() = runBlocking<Unit> {
        var asked = 0
        var finished = 0
        val diskFull: suspend () -> Unit = { throw java.io.IOException("No space left on device") }
        assertFalse(closeAfterAutosave(diskFull, { asked++; false }) { finished++ }, "Declining keeps the editor and its work")
        assertEquals(1, asked); assertEquals(0, finished)
        assertTrue(closeAfterAutosave(diskFull, { asked++; true }) { finished++ }, "The user can still close")
        assertEquals(2, asked); assertEquals(1, finished)
        assertTrue(closeAfterAutosave({}, { error("A saved document closes without a question") }) { finished++ })
        assertEquals(2, finished)
    }

    @Test fun cancellationDuringTheFinalAutosaveIsNotMistakenForAFailure() = runBlocking<Unit> {
        var asked = false
        val closing = async { closeAfterAutosave({ awaitCancellation() }, { asked = true; true }) { } }
        delay(20)
        closing.cancelAndJoin()
        assertFalse(asked)
        assertTrue(closing.isCancelled)
    }
}
