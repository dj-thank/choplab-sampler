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
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.SetTempo(240)))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(2))))
            val before = f.backend.studio.document.value
            f.recordVoice()
            val armed = f.armedCue()
            val cue = armed.recordingStartFrame
            assertTrue(cue > armed.frame, "Cue must be in the future: $armed")
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
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.SetTempo(240)))
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(2))))
                val before = f.backend.studio.document.value.project
                f.recordVoice()
                await { f.mic.frames > 1_000 }
                if (loss) f.backend.engine.releaseOutput() else assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopVoice))
                f.voiceFinished()
                assertEquals(before, f.backend.studio.document.value.project)
                assertEquals(0, f.backend.engine.snapshot().countInBeatsRemaining)
                assertFalse(f.backend.engine.snapshot().playing)
                assertEquals(PlaybackTarget.Arrangement(), f.backend.studio.selection.value.playbackTarget)
                assertEquals(0, Files.list(f.directory.resolve("profile/voice-scratch")).use { it.count() })
            } finally { f.close() }
            assertEquals(1, f.mic.closes)
        }
    }

    @Test fun closingTheInputDoesNotMeanTheAsynchronousRecordingClockCleanupHasFinished() = runBlocking<Unit> {
        val discarded = CompletableDeferred<Unit>()
        val finishDiscard = CompletableDeferred<Unit>()
        val f = Fixture { actual -> object : ContinuousEditorPorts by actual {
            override suspend fun discardVoice() {
                actual.discardVoice()
                discarded.complete(Unit)
                finishDiscard.await()
            }
        } }
        try {
            f.ready()
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.SetTempo(240)))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(2))))
            val before = f.backend.studio.document.value.project
            f.recordVoice()
            await { f.mic.frames > 1_000 }
            f.backend.engine.releaseOutput()
            withTimeout(10_000) { discarded.await() }
            await { !f.presenter.state.value.recordingVoice && f.mic.closes == 1 }
            assertTrue((f.backend.studio.selection.value.playbackTarget as PlaybackTarget.Arrangement).minimumFrames > 0,
                "Closing the microphone precedes removal of the temporary recording clock")
            assertEquals(before, f.backend.studio.document.value.project)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { f.voiceFinished() }
            assertFalse(completed.isCompleted)
            finishDiscard.complete(Unit)
            completed.await()
            assertEquals(PlaybackTarget.Arrangement(), f.backend.studio.selection.value.playbackTarget)
            assertEquals(before, f.backend.studio.document.value.project)
            assertEquals(0, Files.list(f.directory.resolve("profile/voice-scratch")).use { it.count() })
        } finally { finishDiscard.complete(Unit); f.close() }
        assertEquals(1, f.mic.closes)
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
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(1))))
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
            f.recordVoice() // Count-in off.
            val cue = f.armedCue().recordingStartFrame
            await { f.backend.voice.recordedMillis >= 40 }
            f.backend.engine.releaseOutput()
            await { f.presenter.state.value.status == ContinuousStatus.VOICE_SAVED && f.mic.closes == 1 }
            f.voiceFinished()
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
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.SetTempo(240)))
            f.presenter.dispatch(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(1))))
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
        @Volatile private var voiceStart: VoiceStart? = null
        @Volatile private var cueObservation = "not called"
        private val wrapped = wrap(ports)
        private val observed = object : ContinuousEditorPorts by wrapped {
            override val recordingCue = wrapped.recordingCue?.let { actual -> object : RecordingCuePort by actual {
                override suspend fun startArmedVoice(maxSeconds: Int): VoiceStart =
                    actual.startArmedVoice(maxSeconds).also { voiceStart = it }
                override fun cueVoiceAt(engineFrame: Long): Boolean {
                    val before = backend.engine.snapshot()
                    return actual.cueVoiceAt(engineFrame).also {
                        cueObservation = "frame=$engineFrame accepted=$it before=$before after=${backend.engine.snapshot()}"
                    }
                }
            } }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, observed)
        suspend fun recordVoice() {
            // One production dispatch, no retry or substituted cue. A CI refusal must retain its stage evidence.
            val accepted = presenter.dispatch(ContinuousEditorAction.RecordVoice)
            assertTrue(accepted, "RecordVoice refused: status=${presenter.state.value.status} start=$voiceStart " +
                "cue=$cueObservation driver=${backend.engine.status.value} receipt=${backend.engine.lastReceipt} " +
                "transport=${backend.studio.transport.value} micFrames=${mic.frames} micCloses=${mic.closes}")
        }
        suspend fun ready() { withTimeout(10_000) { presenter.state.first { it.permits(ContinuousCapability.RECORD_VOICE) } } }
        suspend fun idle() = await { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
        suspend fun armedCue(): TransportState {
            // Render publishes an asynchronous readout; the first copy may still be the old snapshot.
            var snapshot = backend.engine.snapshot()
            await { snapshot = backend.engine.snapshot(); snapshot.recordingStartFrame >= 0 }
            return snapshot
        }
        suspend fun voiceFinished() = await {
            !presenter.state.value.recordingVoice && mic.closes == 1 &&
                backend.studio.selection.value.playbackTarget == PlaybackTarget.Arrangement() &&
                backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null
        }
        suspend fun close() { presenter.close(); backend.shutdown(); ports.close(); scope.cancel() }
    }
    private class ConstantMic : MicInput {
        override val sampleRate = 48_000
        @Volatile var frames = 0L
        @Volatile var closes = 0
        @Volatile private var stopped = false
        private val clock = FrameClock()
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            clock.awaitFrames(480)
            buffer.fill(.2f, 0, 480); frames += 480
            return 480
        }
        override fun stop() { stopped = true }
        override fun close() { stopped = true; closes++ }
    }
    private class CountingSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var nonzero = 0L
        private val clock = FrameClock()
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            for (i in offset until offset + length step 4) {
                val bits = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
                    ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (Float.fromBits(bits) != 0f) nonzero++
            }
            clock.awaitFrames(length / 8)
            return length
        }
        override fun close() {}
    }
    /** Both devices keep 48 kHz even when their different buffer sizes wake on coarse OS timers. */
    private class FrameClock {
        private var startedAt = 0L
        private var frames = 0L
        fun awaitFrames(count: Int) {
            if (frames == 0L) startedAt = System.nanoTime()
            frames += count
            val deadline = startedAt + frames * 1_000_000_000L / 48_000
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                // Reproduce coarse timer wakes on every platform. Accumulating relative sleeps here
                // would give the 480-frame input and 256-frame output different, drifting rates.
                val quantum = 15_625_000L
                LockSupport.parkNanos(((remaining + quantum - 1) / quantum) * quantum)
            }
        }
    }
    private companion object {
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    }
}
