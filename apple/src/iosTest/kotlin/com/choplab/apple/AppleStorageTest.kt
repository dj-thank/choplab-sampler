package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.persistence.ProjectJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.choplab.engine.EngineCommand
import platform.posix.symlink
import kotlin.test.*

class AppleStorageTest {
    private fun directory() = IosPaths.temporary() + "/" + uniqueName("choplab-native-test-")
    private fun fixture(): Pair<Asset, ByteArray> {
        val out = ArrayOutput()
        IosWav.FloatWriter(out, 480).apply {
            write(FloatArray(960) { if (it % 2 == 0) .25f else -.5f }); finish()
        }
        val bytes = out.toByteArray()
        return Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 480, "Synthetic stereo") to bytes
    }
    private fun file(path: String, bytes: ByteArray) { FileWriter(path).use { it.write(bytes); it.syncAndClose() } }
    @Test fun sameSizeCorruptionIsRejectedEvenAfterVerification() = runBlocking {
        val store = IosAssetStore(directory())
        val (asset, bytes) = fixture()
        store.write(asset, bytes)
        assertTrue(store.containsVerified(asset))
        val corrupt = bytes.copyOf().also { it[44] = (it[44].toInt() xor 1).toByte() }
        FileWriter(store.verifiedPath(asset), exclusive = false).use { it.write(corrupt); it.syncAndClose() }
        assertFalse(store.containsVerified(asset))
        assertFails { store.read(asset) }
        assertFails { store.write(asset, bytes) }
    }
    @Test fun hiddenFilesAndOutstandingReservationsConsumeQuota() {
        val store = IosAssetStore(directory(), maxStoredBytes = 1000)
        file(store.directory + "/.unknown-pending", ByteArray(600))
        val pending = store.pendingPath(400)
        assertFails { store.pendingPath(1) }
        assertEquals(600L, store.storedBytes())
        store.discardPending(pending)
        assertNotNull(regularFileSize(store.directory + "/.unknown-pending"))
        assertFails { store.pendingPath(401) }
    }
    @Test fun symlinkCannotBeReadOrUsedToHideQuota() {
        val root = directory().also(::ensureDirectory)
        file("$root/real", byteArrayOf(1))
        assertEquals(0, symlink("$root/real", "$root/.linked"))
        assertNull(regularFileSize("$root/.linked"))
        assertFails { FileReader("$root/.linked") }
        assertFails { IosAssetStore(root).pendingPath(10) }
    }
    @Test fun autosaveRetainsThreeGenerationsAndFailedCommitDoesNotReplaceOne() = runBlocking {
        val store = IosAssetStore(directory())
        val autosaveRoot = directory()
        val autosave = IosAutosave(autosaveRoot, store)
        for (revision in 1L..4L) autosave.save(Project(title = "Project $revision"), revision)
        assertEquals(4L, autosave.recover()?.revision)
        assertEquals(3, listFiles(autosaveRoot).count { it.startsWith("autosave.") })
        val fault = IosAutosave(autosaveRoot, store) { error("Injected capacity/commit failure") }
        assertFails { fault.save(Project(title = "Rejected"), 5) }
        assertEquals(4L, autosave.recover()?.revision)
        FileWriter("$autosaveRoot/autosave.0.json", exclusive = false).use { it.write(byteArrayOf(1)); it.syncAndClose() }
        assertEquals(3L, autosave.recover()?.revision)
    }
    @Test fun unavailableOldAssetDoesNotResetAutosaveGeneration() = runBlocking {
        val store = IosAssetStore(directory())
        val (asset, bytes) = fixture()
        store.write(asset, bytes)
        val autosave = IosAutosave(directory(), store)
        autosave.save(Project(assets = frozenListOf(asset)), 1)
        autosave.save(Project(assets = frozenListOf(asset), title = "Previous"), 2)
        // Memory pressure can prevent asset validation even when the envelope itself remains trustworthy.
        store.memory.reserve(store.memory.limit).use { autosave.save(Project(title = "New empty project"), 3) }
        assertEquals(3L, autosave.recover()?.revision)
        assertEquals("New empty project", autosave.recover()?.project?.title)
    }
    @Test fun unknownSchemaAndUnknownZipEntryAreRejected() {
        val store = IosAssetStore(directory())
        val root = directory().also(::ensureDirectory)
        val document = ProjectJson.encode(Project()).decodeToString().replace("\"schemaVersion\":${ProjectLimits.SCHEMA}", "\"schemaVersion\":999")
        val path = "$root/unknown.choplab"
        FileWriter(path).use { output ->
            val zip = IosZip.Writer(output)
            zip.add("project.json", { false }) { it(document.encodeToByteArray(), document.encodeToByteArray().size) }
            zip.finish(); output.syncAndClose()
        }
        assertFails { IosArchive.read(path, store) { false } }
        val extra = "$root/extra.choplab"
        FileWriter(extra).use { output ->
            val zip = IosZip.Writer(output)
            val valid = ProjectJson.encode(Project())
            zip.add("project.json", { false }) { it(valid, valid.size) }
            zip.add("unknown.txt", { false }) { it(byteArrayOf(1), 1) }
            zip.finish(); output.syncAndClose()
        }
        assertFails { IosArchive.read(extra, store) { false } }
        assertFails { IosArchive.validateName("assets/../escaped.wav") }
        assertFails { IosArchive.validateName("assets\\escaped.wav") }
    }
    @Test fun decodeAdmissionFailsBeforeLoadingAndRecoversAfterRelease() = runBlocking {
        val store = IosAssetStore(directory())
        val (asset, bytes) = fixture()
        store.write(asset, bytes)
        val pcm = IosPcmPort(store, store.memory)
        store.memory.reserve(store.memory.limit).use {
            assertFailsWith<PcmBudgetExceeded> { pcm.acquire(asset) }
            assertEquals(0L, pcm.residentBytes())
        }
        pcm.acquire(asset).use { assertEquals(480, it.pcm.frameCount) }
        pcm.close()
        assertEquals(0L, store.memory.usedBytes)
        assertContentEquals(bytes, store.read(asset))
    }
    @Test fun unavailableOutputRefusesPlaybackButAcknowledgesEditsAndStop() = runBlocking {
        val store = IosAssetStore(directory())
        val pcm = IosPcmPort(store, store.memory)
        val compiler = ProgramCompiler(pcm)
        val driver = IosEnginePort(compiler, { null }, memory = store.memory)
        try {
            withTimeout(10_000) { driver.status.first { it.phase == DriverPhase.EDITING_ONLY } }
            assertFalse(driver.snapshot().outputAttached)
            assertFalse(driver.apply(EngineCommand.StartSequence(0, 1)))
            val program = compiler.compile(Project(), PlaybackTarget.Arrangement(), 2)
            try { assertTrue(driver.apply(EngineCommand.SwapProgram(0, 2, program))) }
            finally { program.releasePreparation() }
            assertTrue(driver.apply(EngineCommand.StopAll(driver.snapshot().frame, 3)))
            assertFalse(driver.snapshot().playing)
        } finally { driver.close(); pcm.close() }
        assertEquals(0L, store.memory.usedBytes)
    }
    @Test fun archiveLocalHeaderMismatchAndTrailingBytesAreRejected() {
        val root = directory().also(::ensureDirectory)
        val path = "$root/good.zip"
        FileWriter(path).use { output ->
            val zip = IosZip.Writer(output)
            zip.add("project.json", { false }) { it(byteArrayOf(1), 1) }
            zip.finish(); output.syncAndClose()
        }
        val bytes = readFile(path, 1024)
        file("$root/trailing.zip", bytes + byteArrayOf(0))
        assertFails { IosZip.entries("$root/trailing.zip") }
        val changed = bytes.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        file("$root/changed.zip", changed)
        assertFails { IosZip.entries("$root/changed.zip") }
    }
    @Test fun localImportChopPadArrangementWavSaveResumeAndNewUndo() = runBlocking {
        val root = directory().also(::ensureDirectory)
        val store = IosAssetStore("$root/assets")
        val (original, bytes) = fixture()
        file("$root/source.wav", bytes)
        val files = IosLocations()
        val asset = IosImportPort(store, files).import(files.register(HostFile("$root/source.wav", "source.wav")))
        assertEquals(original.hash, asset.hash)
        val editor = EditSession()
        fun apply(intent: Intent) { val plan = editor.plan(intent); plan.effects.indices.forEach { editor.acknowledge(plan, it) }; editor.commit(plan) }
        apply(Intent.ImportAsset(asset)); apply(Intent.EqualChop(2)); apply(Intent.AssignSlice(0, 0))
        val track = Track("track-1", "Beat", TrackKind.BANK)
        val clip = Clip("clip-1", track.id, asset.hash, FrameRange(0, 240))
        val project = editor.project.copy(tracks = frozenListOf(track), clips = frozenListOf(clip))
        val pcm = IosPcmPort(store, store.memory)
        val exporter = IosExportPort(ProgramCompiler(pcm), files, pcm)
        val output = files.register(HostFile("$root/song.wav", "song.wav"))
        exporter.export(project, PlaybackTarget.Arrangement(), ExportRequest(output, 240, tailMode = ExportTailMode.EXACT))
        val wav = FileReader("$root/song.wav").use { IosWav.read(it.asInput()) }
        assertEquals(24, wav.info.bits); assertEquals(240L, wav.info.frames)
        assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == 0 }.any { it > .01f })
        assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == 1 }.any { it < -.01f })
        val destination = files.register(HostFile("$root/song.choplab", "song.choplab"))
        val projects = IosProjectPort(store, files)
        projects.save(project, 3, destination)
        val opened = projects.open(destination)
        assertEquals(project, opened); assertContentEquals(bytes, store.read(asset))
        val next = EditSession(opened)
        assertFalse(next.canUndo)
        val rename = next.plan(Intent.Rename("Resumed")); rename.effects.indices.forEach { next.acknowledge(rename, it) }; next.commit(rename)
        val undo = assertNotNull(next.planUndo()); undo.effects.indices.forEach { next.acknowledge(undo, it) }; next.commit(undo)
        assertEquals(project, next.project)
        val redo = assertNotNull(next.planRedo()); redo.effects.indices.forEach { next.acknowledge(redo, it) }; next.commit(redo)
        assertEquals("Resumed", next.project.title)
        pcm.close(); assertEquals(0L, store.memory.usedBytes)
    }
}
