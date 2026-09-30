package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.core.model.Project
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.OfflineRender
import com.choplab.engine.PcmAsset
import com.choplab.jvm.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Real presenter, desktop ports, streaming engine and files, with a paced synthetic sink. No devices. */
class NextNoteRepeatTest {
    @Test fun recordedTripletPhraseSurvivesOneUndoWavArchiveAndRestartWithoutChangingTheOriginal() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("note-repeat-production-")
        val profile = directory.resolve("profile")
        val input = directory.resolve("source.wav")
        val samples = FloatArray(4_800 * 2) { (sin(it / 2 * .05) * if (it % 2 == 0) .2 else -.07).toFloat() }
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
        val original = Files.readAllBytes(input)
        val sink = CountingSink()
        val backend = NextBackend.create(profile, sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val saved: Project
        val songFrames: Int
        try {
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val source = backend.studio.document.value.project.asset(backend.studio.document.value.project.source!!.assetHash)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, 4_800), 0))).accepted)
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.PAD_AUDITION) && it.permits(ContinuousCapability.NOTE_REPEAT) } }
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetNoteRepeat(ContinuousNoteRepeat.SIXTEENTH_TRIPLET)))
            val before = backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordHits))
            await { backend.engine.snapshot().sequenceFrame >= 4_800 }
            val press = ContinuousHitGesture(0, backend.engine.snapshot().sequenceFrame)
            assertTrue(presenter.dispatch(ContinuousEditorAction.BeginHit(press)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.HoldPad(0)))
            await { backend.engine.snapshot().sequenceFrame >= press.songFrame + 32_000 }
            val release = backend.engine.snapshot().sequenceFrame
            assertTrue(presenter.dispatch(ContinuousEditorAction.EndHit(press, false, release)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.ReleasePad(0)))
            assertTrue(sink.nonzero > 10_000, "The actual streaming engine played several retriggers")
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopHits))
            saved = backend.studio.document.value.project
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            val clip = saved.clips.single()
            val performed = saved.asset(clip.assetHash)
            assertEquals(source.hash, performed.derivedFrom)
            assertEquals(press.songFrame, clip.timelineStartFrame)
            assertEquals(release - press.songFrame + saved.pads[0].releaseFrames, performed.frames)
            assertTrue(performed.frames > source.frames * 6, "Record the full held phrase, beyond the one-shot source")
            assertEquals(1f, clip.gain); assertEquals(0f, clip.pan)
            val captured = backend.assets.openVerified(performed).use { WavCodec.read(it) }
            val enginePad = ProgramCompiler.enginePad(saved.pads[0], source, PcmAsset.fromInterleaved(samples))
            val expected = OfflineRender.render(EngineProgram(listOf(enginePad), tempo = saved.tempo), listOf(
                EngineCommand.StartNoteRepeat(0, 0, 0, 160), EngineCommand.Release(release - press.songFrame, 1, 0)), performed.frames.toInt())
            captured.samples.indices.forEach { assertEquals(expected[it], captured.samples[it], 1e-6f, "Recorded sample $it") }
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(saved, backend.studio.document.value.project)
            assertContentEquals(original, backend.assets.read(source)); assertContentEquals(original, Files.readAllBytes(input))
            songFrames = (requireNotNull(clip.timelineStartFrame) + performed.frames).toInt()
            val output = directory.resolve("phrase.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), songFrames, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            val wav = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals(songFrames.toLong(), wav.info.frames)
            assertEquals(24, wav.info.bits); assertEquals(2, wav.info.channels)
            assertTrue(wav.samples.maxOf { abs(it) } > .1f)
            val archive = directory.resolve("phrase.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            ZipFile(archive.toFile()).use { zip ->
                assertEquals(2, zip.entries().asSequence().count { it.name.startsWith("assets/") })
                assertContentEquals(original, zip.getInputStream(zip.getEntry("assets/${source.hash}.wav")).use { it.readBytes() })
                assertContentEquals(backend.assets.read(performed), zip.getInputStream(zip.getEntry("assets/${performed.hash}.wav")).use { it.readBytes() })
            }
            backend.flushAutosave()
            assertTrue(backend.openProject(archive).accepted); idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
        } finally { presenter.close(); backend.shutdown(); ports.close(); scope.cancel() }
        assertTrue(sink.closed)
        val restarted = NextBackend.create(profile, sinkFactory = { error("No device for file readback") }, microphone = { null })
        try {
            assertEquals(saved, restarted.studio.document.value.project)
            val output = directory.resolve("reopened.wav")
            assertTrue(restarted.studio.dispatch(Action.Export(ExportRequest(restarted.files.register(output), songFrames, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(restarted)
            assertContentEquals(Files.readAllBytes(directory.resolve("phrase.wav")), Files.readAllBytes(output))
        } finally { restarted.shutdown() }
    }

    private suspend fun idle(backend: NextBackend) = await { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    private class CountingSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var nonzero = 0L
        @Volatile var closed = false
        private var started = 0L
        private var frames = 0L
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            for (i in offset until offset + length step 4) {
                val bits = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
                    ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (Float.fromBits(bits) != 0f) nonzero++
            }
            if (frames == 0L) started = System.nanoTime()
            frames += length / 8
            val deadline = started + frames * 1_000_000_000L / 48_000
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                LockSupport.parkNanos(((left + 15_624_999) / 15_625_000) * 15_625_000)
            }
            return length
        }
        override fun close() { closed = true }
    }
}
