package com.choplab.desktop.next

import com.choplab.core.Action
import com.choplab.core.chop.LiveChopCorrection
import com.choplab.core.chop.LiveChopTimingMode
import com.choplab.engine.EngineCore
import com.choplab.engine.LiveReadout
import com.choplab.jvm.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlin.test.*

/** Real SOURCE, host and presenter. The endpoint and publication boundary are controlled, not physical audio. */
class LiveChopCompletionHostTest {
    @Test fun aContendedCompletionPollDropsAValidPressOnlyWithTheLegacyHostRead() = runBlocking<Unit> {
        for (legacy in listOf(true, false)) {
            val f = Fixture()
            val reader = Executors.newSingleThreadExecutor()
            val inject = AtomicBoolean()
            val observed = CompletableDeferred<Pair<OriginalPlaybackProbe, Boolean?>>()
            val ports = object : ContinuousEditorPorts by f.host {
                override fun originalPlaying(): Boolean? {
                    if (!inject.get()) return f.host.originalPlaying()
                    // The production poll may resume on a worker without a previous SOURCE snapshot.
                    // This reader has never read this engine; no snapshot is fabricated or cleared.
                    val result = reader.submit<Pair<OriginalPlaybackProbe, Boolean?>> {
                        val probe = f.backend.engine.originalPlaybackProbe()
                        probe to if (legacy) f.backend.engine.originalPlayback().playing else f.host.originalPlaying()
                    }.get(5, TimeUnit.SECONDS)
                    observed.complete(result)
                    return result.second
                }
            }
            val presenter = ContinuousEditorPresenter(f.backend.studio, f.scope, ports)
            try {
                f.begin(presenter)
                val before = f.backend.studio.document.value
                val press = assertNotNull(presenter.captureLiveChop())
                assertTrue(press.output.sourcePlaying)
                f.park()
                f.contend()
                inject.set(true)
                val read = withTimeout(5_000) { observed.await() }
                assertEquals(OriginalPlaybackProbe.Contended, read.first)
                assertEquals(if (legacy) false else null, read.second)
                until { if (legacy) !presenter.state.value.liveChopping else presenter.state.value.liveChopTiming.estimatedMillis == null }
                assertEquals(!legacy, presenter.state.value.liveChopping)
                assertNull(presenter.state.value.liveChopTiming.lastCut)
                assertEquals(before, f.backend.studio.document.value)
                inject.set(false)
                f.restorePublication()
                val actual = assertIs<OriginalPlaybackProbe.Ready>(f.backend.engine.originalPlaybackProbe()).playback
                assertTrue(actual.playing, "The poll ended the legacy pass while SOURCE was still playing")
                assertTrue(actual.sourceFrame < 48_000L * 12, "This is not natural completion")
                f.resume.countDown()
                // Same press-time gesture, delivered after the poll, as the PAD's release callback does.
                assertTrue(presenter.dispatch(ContinuousEditorAction.CapturePad(2, press)))
                if (legacy) {
                    assertNull(presenter.state.value.liveChopTiming.lastCut, "A lost pass silently discards its valid press")
                    assertEquals(before, f.backend.studio.document.value)
                } else {
                    until { presenter.state.value.liveChopTiming.lastCut != null }
                    val cut = assertNotNull(presenter.state.value.liveChopTiming.lastCut)
                    assertEquals(press.output.eventNanos, cut.eventNanos)
                    assertEquals(cut.observedSourceFrame - (.037 * 44_100 * 2).roundToLong(), cut.requestedSourceFrame)
                    assertEquals(LiveChopTimingMode.MANUAL, cut.mode)
                    val after = f.backend.studio.document.value
                    assertEquals(before.revision + 1, after.revision)
                    assertEquals(cut.appliedSourceFrame, after.project.pads[2].range!!.start)
                    assertEquals(before.project.source!!.range.end, after.project.pads[2].range!!.end)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
                    assertEquals(before.project, f.backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                    assertEquals(after.project, f.backend.studio.document.value.project)
                    assertContentEquals(f.originalBytes, f.backend.assets.read(after.project.assets.single()))
                }
            } finally {
                inject.set(false); f.restorePublication(); f.resume.countDown()
                presenter.close(); reader.shutdownNow(); f.close()
            }
        }
    }

    @Test fun contentionAtPressHasNoCutPositionButKeepsThePassAndItsManualCorrection() = runBlocking<Unit> {
        val f = Fixture()
        val presenter = ContinuousEditorPresenter(f.backend.studio, f.scope, f.host)
        try {
            f.begin(presenter)
            val before = f.backend.studio.document.value
            f.park(); f.contend()
            assertNull(presenter.captureLiveChop(), "A contended press must not borrow a cached position")
            until { presenter.state.value.liveChopTiming.estimatedMillis == null }
            assertTrue(presenter.state.value.liveChopping)
            assertEquals(LiveChopCorrection(LiveChopTimingMode.MANUAL, 37), presenter.state.value.liveChopTiming.correction)
            f.restorePublication()
            assertEquals(true, f.host.originalPlaying())
            assertEquals(before, f.backend.studio.document.value, "Recovery alone cannot fabricate a cut")
            val freshPress = assertNotNull(presenter.captureLiveChop())
            f.resume.countDown()
            assertTrue(presenter.dispatch(ContinuousEditorAction.CapturePad(2, freshPress)))
            until { presenter.state.value.liveChopTiming.lastCut != null }
            assertEquals(freshPress.output.eventNanos, presenter.state.value.liveChopTiming.lastCut!!.eventNanos)
            assertEquals(before.revision + 1, f.backend.studio.document.value.revision)
        } finally { f.restorePublication(); f.resume.countDown(); presenter.close(); f.close() }
    }

    @Test fun actualSourceCompletionBeforeReleaseStillDiscardsTheHeldPressWithTheCurrentHost() = runBlocking<Unit> {
        val f = Fixture()
        val presenter = ContinuousEditorPresenter(f.backend.studio, f.scope, f.host)
        try {
            f.begin(presenter)
            val before = f.backend.studio.document.value
            val press = assertNotNull(presenter.captureLiveChop())
            // Simulate elapsed output during a slow UI gesture, without sleeping for the whole asset.
            assertTrue(f.host.seekOriginal(before.project.source!!.range.end - 1))
            until { (f.backend.engine.originalPlaybackProbe() as? OriginalPlaybackProbe.Ready)?.playback?.playing == false }
            until { !presenter.state.value.liveChopping && !presenter.state.value.originalPlaying }
            assertTrue(presenter.dispatch(ContinuousEditorAction.CapturePad(2, press)))
            assertNull(presenter.state.value.liveChopTiming.lastCut)
            assertEquals(before, f.backend.studio.document.value)
        } finally { presenter.close(); f.close() }
    }

    private class Fixture {
        val directory = Files.createTempDirectory("live-chop-completion-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hold = AtomicBoolean()
        val parked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private val endpoint = CountingTestSink()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { object : AudioSink by endpoint {
            override fun bufferFrames() = 1024
            override fun pendingFrames() = 240L
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = endpoint.write(bytes, offset, length).also {
                if (hold.get()) {
                    parked.countDown()
                    check(resume.await(10, TimeUnit.SECONDS)) { "The test must release the audio owner" }
                }
            }
        } }, microphone = { null })
        val host = DesktopEditorPorts(backend) { null }
        lateinit var originalBytes: ByteArray
        private var publication: Pair<LiveReadout, Long>? = null
        private val version = LiveReadout::class.java.getDeclaredField("version").apply { isAccessible = true }

        suspend fun begin(presenter: ContinuousEditorPresenter) {
            val file = directory.resolve("source.wav")
            Files.newOutputStream(file).use { WavCodec.writeFloat(it, FloatArray(44_100 * 12 * 2) { if (it % 2 == 0) .1f else -.2f }, 44_100, 2) }
            originalBytes = Files.readAllBytes(file)
            assertTrue(backend.importAudio(file).accepted)
            until { backend.studio.work.value.jobId == null }
            backend.studio.dispatch(Action.RefreshTransport)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(12f)))
            until { backend.engine.liveChopOutput() != null && backend.studio.work.value.jobId == null }
            val route = assertNotNull(backend.engine.liveChopOutput()).route
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetLiveChopCorrection(route, LiveChopCorrection(LiveChopTimingMode.MANUAL, 37))))
            assertTrue(presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
            until { presenter.state.value.liveChopping &&
                (backend.engine.originalPlaybackProbe() as? OriginalPlaybackProbe.Ready)?.playback?.let { it.playing && it.sourceFrame >= 4_800 } == true }
        }

        suspend fun park() {
            hold.set(true)
            assertTrue(withContext(Dispatchers.IO) { parked.await(5, TimeUnit.SECONDS) })
        }
        fun contend() {
            check(parked.count == 0L)
            val view = StreamingEnginePort::class.java.getDeclaredField("engineView").run { isAccessible = true; get(backend.engine) }
            val engine = view.javaClass.getDeclaredField("engine").run { isAccessible = true; get(view) as EngineCore }
            val before = version.getLong(engine.readout)
            assertEquals(0L, before and 1L)
            publication = engine.readout to before
            version.setLong(engine.readout, before + 1)
        }
        fun restorePublication() { publication?.let { (readout, before) -> version.setLong(readout, before) }; publication = null }
        suspend fun close() { restorePublication(); resume.countDown(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }

    private companion object {
        suspend fun until(ready: () -> Boolean) = withTimeout(5_000) { while (!ready()) delay(5) }
    }
}
