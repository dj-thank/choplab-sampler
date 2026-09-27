package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.jvm.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Presenter and real Desktop ports drive EngineCore into a synthetic endpoint; no sound device is opened. */
class NextScratchEditorTest {
    @Test fun sourceAndHandControlsStayIndependentThroughTheActualHostAndOutputLoss() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("next-scratch-editor-")
        val input = directory.resolve("source.wav")
        Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(48_000 * 20) { if (it % 2 == 0) .08f else -.02f }) }
        val sink = LosingSink()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        var ends = 0
        val ports = object : ContinuousEditorPorts by real {
            override suspend fun scratchOriginalEnd(): Boolean { ends++; return real.scratchOriginalEnd() }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        suspend fun action(value: ContinuousEditorAction) { assertTrue(presenter.dispatch(value), value.toString()) }
        suspend fun outputFrames(count: Long) { val next = sink.counts.frames + count; waitUntil { sink.counts.frames >= next } }
        suspend fun drag() { repeat(12) { presenter.onAction(ContinuousEditorAction.ScratchDrag(8f)); delay(15) } }
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(backend.importAudio(input).accepted)
            waitUntil { backend.studio.work.value.jobId == null && backend.studio.document.value.project.source != null }
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetSourceRange(FrameRange(144_000, 384_000)))).accepted)
            val before = backend.studio.document.value
            action(ContinuousEditorAction.Navigate(ContinuousStage.BEAT))
            action(ContinuousEditorAction.PlayOriginal)
            action(ContinuousEditorAction.OpenScratch)
            action(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL))
            action(ContinuousEditorAction.SetHandMonitorGain(.25f))
            action(ContinuousEditorAction.ScratchHold)
            waitUntil { presenter.readout().handSourceFrame >= 144_000 }
            val sourceAtHold = presenter.readout().originalFrame
            drag()
            assertTrue(presenter.readout().originalFrame > sourceAtHold, "SOURCE continues while HAND moves")
            assertTrue(presenter.readout().originalFrame < 144_000, "HAND never seeks SOURCE into its range")
            assertTrue(presenter.readout().handSourceFrame > 144_000)
            assertEquals(.25f, backend.engine.handPlayback().gain)

            action(ContinuousEditorAction.SetOriginalMonitorGain(0f))
            action(ContinuousEditorAction.SetScratchCut(0f))
            outputFrames(2_048)
            val silent = sink.counts.leftEnergy
            drag()
            outputFrames(1_024)
            assertEquals(silent, sink.counts.leftEnergy, "SOURCE mute plus HAND CUT emits silence during a real drag")
            action(ContinuousEditorAction.SetScratchCut(1f))
            drag()
            assertTrue(sink.counts.leftEnergy > silent && sink.counts.rightEnergy > 0, "HAND sounds while SOURCE stays muted")
            action(ContinuousEditorAction.SetHandMonitorGain(0f))
            waitUntil { backend.engine.handPlayback().gain == 0f }
            outputFrames(1_024)
            val mutedHand = sink.counts.leftEnergy
            drag()
            assertEquals(mutedHand, sink.counts.leftEnergy, "HAND volume is independent of the open CUT")
            action(ContinuousEditorAction.SetHandMonitorGain(.5f))
            action(ContinuousEditorAction.SetOriginalMonitorGain(.4f))
            action(ContinuousEditorAction.ScratchLetGo)
            assertEquals(-1.0, presenter.readout().handSourceFrame)
            assertTrue(backend.engine.originalPlayback().playing)
            action(ContinuousEditorAction.CloseScratch)
            assertEquals(1, ends)

            action(ContinuousEditorAction.OpenScratch)
            action(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL))
            action(ContinuousEditorAction.ScratchHold)
            action(ContinuousEditorAction.Navigate(ContinuousStage.SAVE))
            assertEquals(2, ends)
            assertTrue(backend.engine.originalPlayback().playing)
            action(ContinuousEditorAction.OpenScratch)
            action(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL))
            action(ContinuousEditorAction.ScratchHold)
            waitUntil { presenter.state.value.scratch?.holding == true }
            sink.lost = true
            waitUntil { !backend.engine.snapshot().outputAttached && presenter.state.value.scratch?.holding == false }
            assertEquals(3, ends)
            action(ContinuousEditorAction.ScratchLetGo)
            action(ContinuousEditorAction.CloseScratch)
            assertEquals(3, ends, "Output loss and the following release relinquish HAND only once")
            assertEquals(before.project, backend.studio.document.value.project)
            assertEquals(before.revision, backend.studio.document.value.revision)
        } finally { presenter.close(); backend.shutdown(); scope.cancel() }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    private class LosingSink : AudioSink {
        val counts = CountingTestSink()
        @Volatile var lost = false
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            if (lost) throw java.io.IOException("Synthetic output loss")
            return counts.write(bytes, offset, length)
        }
        override fun close() = counts.close()
    }
}
