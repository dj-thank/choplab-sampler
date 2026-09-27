package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.core.model.Project
import com.choplab.engine.PlayMode
import com.choplab.jvm.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.test.*

/** Actual shared presenter, desktop host, Studio, driver, input worker and files; synthetic devices only. */
class NextCountInTest {
    @Test fun twoBarsPrecedeTheFirstVoiceTakeWithoutEnteringUndoFilesOrExport() = runBlocking<Unit> {
        val f = Fixture()
        val saved: Project
        try {
            f.ready()
            f.presenter.dispatch(ContinuousEditorAction.SetTempo(240))
            f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(2)))
            val before = f.backend.studio.document.value
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            val cue = f.backend.engine.snapshot().recordingStartFrame
            assertTrue(cue > f.backend.engine.snapshot().frame)
            await { f.sink.nonzero > 100 }
            assertEquals(0, f.backend.engine.snapshot().sequenceFrame)
            assertEquals(0, f.backend.voice.recordedMillis)
            assertEquals(before, f.backend.studio.document.value, "Preparing/clicking never edits the song")
            await { f.backend.voice.recordedMillis >= 250 }
            assertEquals(cue, f.backend.engine.snapshot().recordingStartedFrame)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopVoice))
            saved = f.backend.studio.document.value.project
            val asset = saved.assets.single()
            val clip = saved.clips.single()
            assertTrue(asset.frames in 10_000L..48_000L, "Only the post-cue take is stored: ${asset.frames}")
            assertTrue(f.mic.frames - asset.frames > 90_000, "Two bars were drained before file writing began")
            assertEquals(0, clip.range.start)
            assertTrue(f.backend.assets.openVerified(asset).use { WavCodec.read(it) }.samples.all { it == .2f })
            assertEquals(PlaybackTarget.Arrangement(), f.backend.studio.selection.value.playbackTarget)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before.project, f.backend.studio.document.value.project)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(saved, f.backend.studio.document.value.project)
            val frames = requireNotNull(clip.timelineStartFrame) + clip.range.length
            val wav = f.directory.resolve("voice.wav")
            assertTrue(f.backend.studio.dispatch(Action.Export(ExportRequest(f.backend.files.register(wav), frames.toInt()), PlaybackTarget.Arrangement())).accepted)
            f.idle()
            val rendered = Files.newInputStream(wav).use { WavCodec.read(it) }
            assertEquals(frames, rendered.info.frames)
            assertTrue(rendered.samples.all { abs(it) <= .200001f }, "The monitoring click is absent from export")
            val archive = f.directory.resolve("song.choplab")
            assertTrue(f.backend.saveProject(archive).accepted)
            f.idle()
            ZipFile(archive.toFile()).use { zip ->
                val json = zip.getInputStream(zip.getEntry("project.json")).bufferedReader().use { it.readText() }
                assertFalse(json.contains("minimumFrames") || json.contains("countIn") || json.contains("metronome"))
            }
            assertTrue(f.backend.openProject(archive).accepted)
            f.idle()
            assertEquals(saved, f.backend.studio.document.value.project)
        } finally { f.close() }
        assertEquals(1, f.mic.closes)
        val reopened = NextBackend.create(f.directory.resolve("profile"), sinkFactory = { error("No output needed") }, microphone = { null })
        try { assertEquals(saved, reopened.studio.document.value.project) } finally { reopened.shutdown() }
    }

    @Test fun stopAndOutputLossDuringCountInDiscardTheInputAndNeverCreateATake() = runBlocking<Unit> {
        for (loss in listOf(false, true)) {
            val f = Fixture()
            try {
                f.ready()
                f.presenter.dispatch(ContinuousEditorAction.SetTempo(240))
                f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(2)))
                val before = f.backend.studio.document.value.project
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordVoice))
                await { f.mic.frames > 1_000 }
                if (loss) f.backend.engine.releaseOutput() else assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopVoice))
                await { !f.presenter.state.value.recordingVoice && f.mic.closes == 1 }
                assertEquals(before, f.backend.studio.document.value.project)
                assertEquals(0, f.backend.engine.snapshot().countInBeatsRemaining)
                assertFalse(f.backend.engine.snapshot().playing)
                assertEquals(PlaybackTarget.Arrangement(), f.backend.studio.selection.value.playbackTarget)
                assertEquals(0, Files.list(f.directory.resolve("profile/voice-scratch")).use { it.count() })
            } finally { f.close() }
            assertEquals(1, f.mic.closes)
        }
    }

    @Test fun permissionWaitCannotStartTheClockAndStopOwnsALateSuccessfulInputExactlyOnce() = runBlocking<Unit> {
        val permission = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val f = Fixture { actual -> object : ContinuousEditorPorts by actual {
            override val recordingCue = object : RecordingCuePort {
                override suspend fun startArmedVoice(maxSeconds: Int): VoiceStart {
                    entered.complete(Unit); permission.await()
                    return actual.recordingCue.startArmedVoice(maxSeconds)
                }
                override fun cueVoiceAt(engineFrame: Long) = actual.recordingCue.cueVoiceAt(engineFrame)
                override fun armingTimedOut() = actual.recordingCue.armingTimedOut()
            }
        } }
        try {
            f.ready()
            f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(1)))
            val before = f.backend.studio.document.value.project
            val starting = async { f.presenter.dispatch(ContinuousEditorAction.RecordVoice) }
            entered.await()
            assertEquals(0, f.backend.engine.snapshot().countInBeatsRemaining)
            assertEquals(0, f.mic.frames)
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { f.presenter.dispatch(ContinuousEditorAction.StopVoice) }
            permission.complete(Unit)
            assertFalse(starting.await())
            assertTrue(stopping.await())
            assertEquals(1, f.mic.closes)
            assertEquals(before, f.backend.studio.document.value.project)
            assertFalse(f.backend.engine.snapshot().playing)
            assertEquals(0, f.backend.engine.snapshot().countInBeatsRemaining)
        } finally { f.close() }
    }

    @Test fun outputLossJustAfterTheCueKeepsAShortTakeUsingTheReceiptFromTheReplacedEngine() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.ready()
            val before = f.backend.studio.document.value.project
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordVoice)) // Count-in off.
            val cue = f.backend.engine.snapshot().recordingStartFrame
            await { f.backend.voice.recordedMillis >= 40 }
            f.backend.engine.releaseOutput()
            await { f.presenter.state.value.status == ContinuousStatus.VOICE_SAVED && f.mic.closes == 1 }
            assertEquals(cue, f.backend.engine.snapshot().recordingStartedFrame)
            assertEquals(1, f.backend.studio.document.value.project.clips.size)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, f.backend.studio.document.value.project)
        } finally { f.close() }
        assertEquals(1, f.mic.closes)
    }

    @Test fun padCountInRejectsEarlyPressesAndKeepsOneUndoForTheHeldPerformance() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.ready()
            val wav = f.directory.resolve("pad.wav")
            Files.newOutputStream(wav).use { WavCodec.writeFloat(it, FloatArray(48_000) { .1f }, channels = 1) }
            assertTrue(f.backend.importAudio(wav).accepted); f.idle()
            val source = f.backend.studio.document.value.project.assets.single()
            assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, source.frames), 0))).accepted)
            val pad = f.backend.studio.document.value.project.pads[0]
            assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.SetPad(pad.copy(mode = PlayMode.GATE)))).accepted)
            f.presenter.dispatch(ContinuousEditorAction.SetTempo(240))
            f.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE))
            f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(1)))
            val before = f.backend.studio.document.value.project
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordHits))
            val early = ContinuousHitGesture(0, 0)
            f.presenter.dispatch(ContinuousEditorAction.BeginHit(early))
            f.presenter.dispatch(ContinuousEditorAction.EndHit(early, false, 0))
            await { f.backend.engine.snapshot().sequenceFrame > 1_000 }
            val press = ContinuousHitGesture(0, f.presenter.readout().songFrame)
            f.presenter.dispatch(ContinuousEditorAction.BeginHit(press))
            f.presenter.dispatch(ContinuousEditorAction.HoldPad(0))
            await { f.presenter.readout().songFrame > press.songFrame + 4_800 }
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopHits))
            f.presenter.dispatch(ContinuousEditorAction.ReleasePad(0))
            f.presenter.dispatch(ContinuousEditorAction.EndHit(press, false, f.presenter.readout().songFrame))
            val saved = f.backend.studio.document.value.project
            assertEquals(1, saved.clips.size, "The pre-count press was never part of the performance")
            assertEquals(press.songFrame, saved.clips.single().timelineStartFrame)
            assertTrue(saved.clips.single().range.length >= 4_800)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before, f.backend.studio.document.value.project)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(saved, f.backend.studio.document.value.project)
        } finally { f.close() }
    }

    private class Fixture(wrap: (DesktopEditorPorts) -> ContinuousEditorPorts = { it }) {
        val directory = Files.createTempDirectory("count-in-production-")
        val mic = ConstantMic()
        val sink = CountingSink()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink }, microphone = { mic })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, wrap(ports))
        suspend fun ready() { withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.RECORD_VOICE) } } }
        suspend fun idle() = await { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
        suspend fun close() { presenter.close(); backend.shutdown(); ports.close(); scope.cancel() }
    }
    private class ConstantMic : MicInput {
        override val sampleRate = 48_000
        @Volatile var frames = 0L
        @Volatile var closes = 0
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            LockSupport.parkNanos(10_000_000)
            buffer.fill(.2f, 0, 480); frames += 480
            return 480
        }
        override fun stop() { stopped = true }
        override fun close() { stopped = true; closes++ }
    }
    private class CountingSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var nonzero = 0L
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            for (i in offset until offset + length step 4) {
                val bits = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
                    ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (Float.fromBits(bits) != 0f) nonzero++
            }
            LockSupport.parkNanos(length / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() {}
    }
    private companion object {
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    }
}
