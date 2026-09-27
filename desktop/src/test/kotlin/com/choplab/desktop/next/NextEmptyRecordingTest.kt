package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.jvm.AudioSink
import com.choplab.jvm.MicInput
import com.choplab.jvm.SinkEncoding
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Actual presenter/desktop ports/EngineCore/files, with synthetic microphone and output; no native devices. */
class NextEmptyRecordingTest {
    @Test fun firstVoiceTakeRunsFromAnEmptyProjectAndSurvivesUndoExportArchiveAndRestart() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("empty-song-recording-")
        val profile = directory.resolve("profile")
        val microphone = ToneInput()
        val sink = SilentSink()
        val backend = NextBackend.create(profile, sinkFactory = { sink }, microphone = { microphone })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presenter = ContinuousEditorPresenter(backend.studio, scope, DesktopEditorPorts(backend) { null })
        val saved: Project
        val songFrames: Long
        try {
            withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.RECORD_VOICE) } }
            val before = backend.studio.document.value
            assertTrue(before.project.clips.isEmpty() && before.project.assets.isEmpty())
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertEquals(14_400_000L, assertIs<PlaybackTarget.Arrangement>(backend.studio.selection.value.playbackTarget).minimumFrames)
            withTimeout(10_000) { while (backend.voice.recordedMillis < 300 || backend.engine.snapshot().sequenceFrame < 4_800) delay(5) }
            assertEquals(before.project, backend.studio.document.value.project)
            assertEquals(before.revision, backend.studio.document.value.revision)
            assertEquals(0L, sink.nonzeroSamples, "The empty song advances without a silent PCM asset or an audible pattern")
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopVoice))
            assertTrue(microphone.closed)
            saved = backend.studio.document.value.project
            val clip = saved.clips.single()
            val asset = saved.assets.single()
            assertEquals(asset.hash, clip.assetHash)
            assertEquals(asset.hash, saved.pads[48].assetHash)
            assertTrue(clip.range.length > 4_800, "A meaningful portion of the captured tone is on the song")
            songFrames = requireNotNull(clip.timelineStartFrame) + clip.range.length
            assertTrue(songFrames < 48_000 * 5, "The export length comes from the recorded take, not the clock ceiling")
            assertEquals(PlaybackTarget.Arrangement(), backend.studio.selection.value.playbackTarget)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before.project, backend.studio.document.value.project, "The whole take is one Undo")
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(saved, backend.studio.document.value.project)

            val output = directory.resolve("first-song.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), songFrames.toInt(), bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            val rendered = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals(songFrames, rendered.info.frames)
            assertEquals(2, rendered.info.channels)
            assertTrue(rendered.samples.maxOf { abs(it) } > .1f)
            val archive = directory.resolve("first-song.choplab")
            assertTrue(backend.saveProject(archive).accepted)
            idle(backend)
            ZipFile(archive.toFile()).use { zip ->
                assertEquals(1, zip.entries().asSequence().count { it.name.startsWith("assets/") })
                assertContentEquals(backend.assets.read(asset), zip.getInputStream(assertNotNull(zip.getEntry("assets/${asset.hash}.wav"))).use { it.readBytes() })
                val json = zip.getInputStream(zip.getEntry("project.json")).bufferedReader().use { it.readText() }
                assertFalse(json.contains("minimumFrames"))
            }
            backend.flushAutosave()
            assertTrue(backend.openProject(archive).accepted)
            idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
            assertEquals(PlaybackTarget.Arrangement(), backend.studio.selection.value.playbackTarget)
        } finally { presenter.close(); backend.shutdown(); scope.cancel() }
        assertTrue(sink.closed)
        assertEquals(0L, Files.list(profile.resolve("voice-scratch")).use { it.count() })
        val restarted = NextBackend.create(profile, sinkFactory = { error("No output needed for file verification") }, microphone = { null })
        try {
            assertEquals(saved, restarted.studio.document.value.project)
            val output = directory.resolve("reopened.wav")
            assertTrue(restarted.studio.dispatch(Action.Export(ExportRequest(restarted.files.register(output), songFrames.toInt(), bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            idle(restarted)
            assertContentEquals(Files.readAllBytes(directory.resolve("first-song.wav")), Files.readAllBytes(output))
        } finally { restarted.shutdown() }
    }

    private suspend fun idle(backend: NextBackend) = withTimeout(10_000) {
        while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
    }

    private class ToneInput : MicInput {
        override val sampleRate = 48_000
        private var position = 0
        @Volatile private var stopped = false
        @Volatile var closed = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            val count = minOf(buffer.size, 480)
            LockSupport.parkNanos(count * 1_000_000_000L / sampleRate)
            for (i in 0 until count) buffer[i] = (.3 * sin((position + i) * 2 * Math.PI * 440 / sampleRate)).toFloat()
            position += count
            return count
        }
        override fun stop() { stopped = true }
        override fun close() { stopped = true; closed = true }
    }

    private class SilentSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var nonzeroSamples = 0L
        @Volatile var closed = false
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            var nonzero = nonzeroSamples
            for (i in offset until offset + length step 4) {
                val bits = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
                    ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (Float.fromBits(bits) != 0f) nonzero++
            }
            nonzeroSamples = nonzero
            LockSupport.parkNanos(length / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() { closed = true }
    }
}
