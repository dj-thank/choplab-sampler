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
