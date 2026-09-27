package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.engine.PcmReadStatus
import com.choplab.jvm.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.test.*

/** Real SOURCE/BEAT/SAVE presenter and desktop ports; a paced synthetic sink never opens a native device. */
class LongSourceHostIntegrationTest {
    @Test fun selectedLongSourceSupportsLateHandPadsReloadUndoExportAndArchiveThroughNormalPorts() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("long-source-host-")
        val before = PcmMemoryBudget.shared.statistics().usedBytes
        try {
            exercise(directory)
            withTimeout(5000) { while (PcmMemoryBudget.shared.statistics().usedBytes != before) delay(5) }
            val memory = PcmMemoryBudget.shared.statistics()
            assertTrue(memory.peakBytes <= memory.limitBytes)
            println("400s production ports PCM peak=${memory.peakBytes} limit=${memory.limitBytes}; shutdown retained=${memory.usedBytes - before}")
        } finally { directory.toFile().deleteRecursively() }
    }

    private suspend fun exercise(directory: Path) {
        val input = directory.resolve("source.wav")
        val frames = 400 * 48_000
        writeFixture(input, frames)
        val archive = directory.resolve("song.choplab")
        val exported = directory.resolve("song.wav")
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { PacedSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        var voiceLimit = -1
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun chooseAudio() = backend.files.register(input)
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(exported), frames.toInt(), bits = 24)
            override val recordingCue: RecordingCuePort? = null
            override suspend fun startVoice(maxSeconds: Int): VoiceStart { voiceLimit = maxSeconds; return VoiceStart.UNAVAILABLE }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        suspend fun action(value: ContinuousEditorAction) { assertTrue(presenter.dispatch(value), value.toString()) }
        lateinit var saved: Project
        try {
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            action(ContinuousEditorAction.ImportAudio)
            idle(backend)
            await { presenter.state.value.original?.peaks?.isNotEmpty() == true }
            val asset = backend.studio.document.value.project.assets.single()
            assertEquals(frames.toLong(), asset.frames)
            assertEquals(Files.size(input), asset.byteCount)
            val late = 375L * 48_000
            action(ContinuousEditorAction.SetSourceRange(late, late + 12_000))
            action(ContinuousEditorAction.AssignSourceRange(0))
            assertEquals(PcmResidency.bytes(asset), ProgramCompiler.residentBudgetBytes(backend.studio.document.value.project),
                "SOURCE, HAND and PAD share one charged PCM cache")
            assertTrue(ProgramCompiler.residentBudgetBytes(backend.studio.document.value.project) < 20L * 1024 * 1024)
            action(ContinuousEditorAction.Navigate(ContinuousStage.BEAT))
            action(ContinuousEditorAction.SeekOriginal(390L * 48_000))
            action(ContinuousEditorAction.PlayOriginal)
            await { presenter.readout().originalFrame > 390L * 48_000 }
            action(ContinuousEditorAction.OpenScratch)
            action(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL))
            action(ContinuousEditorAction.ScratchHold)
            val hand = presenter.readout().handSourceFrame
            val source = presenter.readout().originalFrame
            assertTrue(hand >= late && hand < late + 12_000)
            repeat(8) { presenter.onAction(ContinuousEditorAction.ScratchDrag(5f)); delay(15) }
            await { presenter.readout().handSourceFrame > hand }
            val forward = presenter.readout().handSourceFrame
            repeat(8) { presenter.onAction(ContinuousEditorAction.ScratchDrag(-5f)); delay(15) }
            await { presenter.readout().handSourceFrame < forward }
            assertTrue(presenter.readout().originalFrame > source, "HAND's negative motion does not seek SOURCE")
            action(ContinuousEditorAction.CloseScratch)
            assertEquals(-1.0, presenter.readout().handSourceFrame)
            action(ContinuousEditorAction.StopAll)
            action(ContinuousEditorAction.TapPad(0))
            action(ContinuousEditorAction.PlacePad(0, null, 0)) // Uses the long paged asset directly in export.
            action(ContinuousEditorAction.SetPadReverse(0, true))
            action(ContinuousEditorAction.SetPadPitch(0, 3f))
            action(ContinuousEditorAction.PlacePad(0, null, 144_000)) // The bounded baked PAD also fits.
            assertEquals(2, backend.studio.document.value.project.clips.size)

            // Remaining recording time must charge the cache, not all 400 seconds of original PCM.
            assertFalse(presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertTrue(voiceLimit in 250..300, "Long SOURCE still leaves room to record: $voiceLimit")
            action(ContinuousEditorAction.RecordHits)
            await { presenter.state.value.recordingHits }
            action(ContinuousEditorAction.CaptureHit(0, 24_000))
            val beforeHits = backend.studio.document.value
            assertFalse(presenter.dispatch(ContinuousEditorAction.ReloadAudio), "Reload cannot interrupt a recording")
            action(ContinuousEditorAction.StopHits)
            assertEquals(beforeHits.revision + 1, backend.studio.document.value.revision)
            assertEquals(3, backend.studio.document.value.project.clips.size)
            action(ContinuousEditorAction.Undo)
            assertEquals(beforeHits.project, backend.studio.document.value.project)
            action(ContinuousEditorAction.Redo)

            // Fail an unread late page in an isolated asset store, then restore the exact same original bytes.
            // The old engine program and SOURCE both retain the failed PCM, so retry must rebuild both leases.
            action(ContinuousEditorAction.StopAll)
            val document = backend.studio.document.value
            val stored = backend.assets.verifiedPath(asset)
            val missing = directory.resolve("temporarily-unreadable.wav")
            Files.move(stored, missing)
            try {
                assertFalse(presenter.dispatch(ContinuousEditorAction.SeekOriginal(320L * 48_000)))
                await { presenter.readout().pcm.status == PcmReadStatus.FAILED }
                assertEquals(PcmReadStatus.FAILED, requireNotNull(presenter.diagnostics()).pcm.status)
                assertTrue(presenter.dispatch(ContinuousEditorAction.ExportWav))
                idle(backend)
                assertFalse(Files.exists(exported), "A failed PCM page cannot publish a silent successful WAV")
            } finally { Files.move(missing, stored) }
            action(ContinuousEditorAction.ReloadAudio)
            await { presenter.readout().pcm.status != PcmReadStatus.FAILED }
            val reloaded = backend.studio.document.value
            assertEquals(document.project, reloaded.project)
            assertEquals(document.revision, reloaded.revision)
            assertEquals(document.canUndo, reloaded.canUndo)
            assertEquals(document.canRedo, reloaded.canRedo)
            assertFalse(backend.engine.originalPlayback().playing)
            assertFalse(backend.engine.snapshot().playing)
            assertEquals(-1.0, presenter.readout().handSourceFrame)

            action(ContinuousEditorAction.SeekOriginal(frames.toLong()))
            assertEquals(frames.toLong(), presenter.readout().originalFrame)
            assertFalse(backend.engine.originalPlayback().playing)
            action(ContinuousEditorAction.PlayOriginal)
            await { presenter.readout().originalFrame in 1 until 48_000 }
            action(ContinuousEditorAction.StopAll)
            action(ContinuousEditorAction.Navigate(ContinuousStage.SAVE))
            action(ContinuousEditorAction.ExportWav)
            idle(backend)
            val wav = Files.newInputStream(exported).use(WavCodec::read)
            assertEquals(24, wav.info.bits)
            assertTrue(wav.samples.filterIndexed { index, _ -> index % 2 == 0 }.any { it > .002f })
            assertTrue(wav.samples.filterIndexed { index, _ -> index % 2 == 1 }.any { it < -.01f })
            action(ContinuousEditorAction.SaveProject)
            idle(backend)
            saved = backend.studio.document.value.project
            ZipFile(archive.toFile()).use { zip ->
                val entry = assertNotNull(zip.getEntry(asset.entryName))
                assertEquals(asset.byteCount, entry.size)
                assertContentEquals(digest(Files.newInputStream(input)), digest(zip.getInputStream(entry)))
            }
        } finally { presenter.close(); host.close(); scope.cancel(); backend.shutdown() }

        val restored = NextBackend.create(directory.resolve("restored"), sinkFactory = { error("Offline export has no device") }, microphone = { null })
        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restoredHost = DesktopEditorPorts(restored) { null }
        val second = directory.resolve("reopened.wav")
        val restoredPresenter = ContinuousEditorPresenter(restored.studio, restoredScope, object : ContinuousEditorPorts by restoredHost {
            override suspend fun chooseOpen() = restored.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(restored.files.register(second), frames.toInt(), bits = 24)
        })
        try {
            assertTrue(restoredPresenter.dispatch(ContinuousEditorAction.OpenProject))
            idle(restored)
            assertEquals(saved, restored.studio.document.value.project)
            assertTrue(restoredPresenter.dispatch(ContinuousEditorAction.ExportWav))
            idle(restored)
            assertContentEquals(Files.readAllBytes(exported), Files.readAllBytes(second), "Fresh paged reads use the same export graph")
        } finally {
            restoredPresenter.close(); restoredHost.close(); restoredScope.cancel(); restored.shutdown()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(5) }
    private suspend fun idle(backend: NextBackend) = await { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private fun digest(input: java.io.InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest()
    }
    private fun writeFixture(path: Path, frames: Int) = Files.newOutputStream(path).use { out ->
        val writer = WavCodec.FloatWriter(out, frames.toLong())
        val buffer = FloatArray(4096 * 2)
        var first = 0
        while (first < frames) {
            val count = minOf(4096, frames - first)
            for (i in 0 until count * 2) buffer[i] = if (i % 2 == 0) (32_701 + (first + i / 2) % 197) / 8_388_608f
                else -(133_003 + (first + i / 2) % 199) / 8_388_608f
            writer.write(buffer, frameCount = count); first += count
        }
        writer.finish()
    }
    private class PacedSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            val data = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
            while (data.hasRemaining()) check(data.float.isFinite())
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000 / 48_000)
            return length
        }
        override fun close() = Unit
    }
}
