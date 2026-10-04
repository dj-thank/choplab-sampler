@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.desktop.next

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.chop.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.chop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.test.*

/** Actual editor, host worker, SOURCE engine region, PAD and persistence; the endpoint is synthetic. */
class AutoChopHostTest {
    @Test fun normalSourcePlaybackStopsInThePresenterWhenItsOutputIsReleased() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("source-output-release-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingTestSink() }, microphone = { null })
        val host = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
        try {
            val file = directory.resolve("source.wav")
            Files.newOutputStream(file).use { WavCodec.writeFloat(it, FloatArray(48_000 * 8 * 2) { if (it % 2 == 0) .1f else -.2f }) }
            assertTrue(backend.importAudio(file).accepted); idle(backend)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
            until { presenter.state.value.permits(ContinuousCapability.ORIGINAL_PLAYBACK) }
            assertTrue(presenter.dispatch(ContinuousEditorAction.PlayOriginal))
            until { presenter.state.value.originalPlaying && (backend.engine.originalPlaybackProbe() as? OriginalPlaybackProbe.Ready)?.playback?.playing == true }
            assertFalse(presenter.state.value.liveChopping, "Normal SOURCE has no live-chop route-end path")
            val before = backend.studio.document.value
            assertTrue(backend.engine.releaseOutput())
            until { backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(OriginalPlaybackProbe.Unavailable, backend.engine.originalPlaybackProbe())
            val stopped = withTimeoutOrNull(5_000) {
                while (presenter.state.value.originalPlaying) delay(5)
                true
            }
            assertEquals(true, stopped, "Released SOURCE still shown playing: driver=${backend.engine.status.value}, " +
                "probe=${backend.engine.originalPlaybackProbe()}, host=${host.originalPlaying()}, state=${presenter.state.value.originalPlaying}")
            assertEquals(false, host.originalPlaying())
            assertEquals(before, backend.studio.document.value)
        } finally { presenter.close(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }

    @Test fun sourcePublicationContentionIsUnknownToAFreshHostReaderRatherThanStopped() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("live-chop-source-readout-")
        val endpoint = CountingTestSink()
        val hold = java.util.concurrent.atomic.AtomicBoolean()
        val parked = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { object : AudioSink by endpoint {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = endpoint.write(bytes, offset, length).also {
                if (hold.get()) {
                    parked.countDown()
                    check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "The test must release the output worker" }
                }
            }
        } }, microphone = { null })
        val host = DesktopEditorPorts(backend) { null }
        try {
            val file = directory.resolve("source.wav")
            Files.newOutputStream(file).use { WavCodec.writeFloat(it, FloatArray(48_000 * 8 * 2) { if (it % 2 == 0) .1f else -.2f }) }
            assertTrue(backend.importAudio(file).accepted); idle(backend)
            val asset = backend.studio.document.value.project.assets.single()
            assertTrue(host.playOriginal(asset))
            hold.set(true)
            assertTrue(withContext(Dispatchers.IO) { parked.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            val playing = assertIs<OriginalPlaybackProbe.Ready>(backend.engine.originalPlaybackProbe()).playback
            assertTrue(playing.playing)
            assertTrue(playing.sourceFrame < asset.frames)
            // The audio owner is parked: hold its existing seqlock mid-publication without racing its writer.
            val view = StreamingEnginePort::class.java.getDeclaredField("engineView").run { isAccessible = true; get(backend.engine) }
            val engine = view.javaClass.getDeclaredField("engine").run { isAccessible = true; get(view) as com.choplab.engine.EngineCore }
            val version = com.choplab.engine.LiveReadout::class.java.getDeclaredField("version").apply { isAccessible = true }
            val before = version.getLong(engine.readout)
            assertEquals(0L, before and 1L)
            version.setLong(engine.readout, before + 1)
            try {
                withContext(reader) {
                    // A new control worker has an empty snapshot until a coherent copy succeeds.
                    assertEquals(OriginalPlaybackProbe.Contended, backend.engine.originalPlaybackProbe())
                    assertNull(host.originalPlaying(), "An unobserved SOURCE state must not end a live chop pass")
                }
            } finally { version.setLong(engine.readout, before) }
            assertEquals(true, withContext(reader) { host.originalPlaying() })
        } finally {
            resume.countDown(); host.close(); backend.shutdown(); reader.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test fun temporaryReadoutContentionKeepsTheManualCorrectionForTheSameOutputRoute() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("live-chop-readout-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val endpoint = CountingTestSink()
        val contend = java.util.concurrent.atomic.AtomicBoolean(false)
        val collisions = java.util.concurrent.atomic.AtomicInteger()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { object : AudioSink by endpoint {
            override fun bufferFrames() = 1024
            override fun pendingFrames(): Long {
                if (contend.get()) {
                    // Force the written-frame fence to change during every sampling attempt.
                    // Two writes ensure the first has returned to the actual streaming driver.
                    val first = endpoint.frames
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (endpoint.frames <= first + 256) {
                        check(System.nanoTime() < deadline) { "The output stopped during the contention control" }
                        java.util.concurrent.locks.LockSupport.parkNanos(100_000)
                    }
                    collisions.incrementAndGet()
                }
                return 240L
            }
        } }, microphone = { null })
        val host = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
        try {
            until { backend.engine.liveChopOutput() != null && backend.studio.work.value.jobId == null }
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenLiveChopTiming))
            val route = assertNotNull(backend.engine.liveChopOutput()).route
            val manual = LiveChopCorrection(LiveChopTimingMode.MANUAL, 37)
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetLiveChopCorrection(route, manual)))
            until { presenter.state.value.liveChopTiming.correction == manual }
            val before = backend.studio.document.value
            contend.set(true)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenLiveChopTiming))
            until { presenter.state.value.liveChopTiming.open }
            contend.set(false)
            until { backend.engine.liveChopOutput() != null }
            val recovered = assertNotNull(backend.engine.liveChopOutput())
            assertEquals(route, recovered.route, "The session, engine, clock, format and buffer did not change")
            assertTrue(collisions.get() >= 3, "All three coherent sampling attempts must have collided")
            assertEquals(before, backend.studio.document.value)
            assertEquals(manual, presenter.state.value.liveChopTiming.correction,
                "Same route after ${collisions.get()} controlled readout collisions: ${presenter.state.value.liveChopTiming}")
        } finally {
            contend.set(false); presenter.close(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively()
        }
    }

    @Test fun editingWithoutAnOutputRefusesPreviewBeforeClaimingSourceAndRemainsCancellable() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("auto-chop-offline-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { error("No output in this fixture") }, microphone = { null })
        val host = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
        try {
            val file = directory.resolve("source.wav")
            Files.newOutputStream(file).use { WavCodec.writeFloat(it, FloatArray(48_000) { .1f }, 48_000, 2) }
            assertTrue(backend.importAudio(file).accepted); idle(backend)
            val before = backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.AutoChop))
            val controller = requireNotNull(presenter.autoChop.value)
            assertTrue(controller.dispatch(AutoChopAction.Prepare)); until { controller.state.value.canApply }
            assertFalse(controller.dispatch(AutoChopAction.Preview))
            assertEquals(AutoChopProblem.UNAVAILABLE, controller.state.value.problem)
            assertFalse(host.sourcePreview!!.state.value.ownsSource)
            assertTrue(presenter.dispatch(ContinuousEditorAction.CloseAutoChop))
            assertNull(presenter.autoChop.value)
            assertEquals(before, backend.studio.document.value)
        } finally { presenter.close(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }
    @Test fun normalChopEntryPreviewsAppliesOneUndoAssignsPadsAndReopensOriginalBytes() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for (size in listOf(Triple(390, 844, 2f), Triple(1440, 1024, 1f))) {
                Locale.setDefault(locale)
                val (width, height, font) = size
                val directory = Files.createTempDirectory("auto-chop-host-")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingTestSink() }, microphone = { null })
                val host = DesktopEditorPorts(backend) { null }
                val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
                val assignments = mutableListOf<ContinuousEditorAction.AssignSourceSlice>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    val chop by presenter.autoChop.collectAsState()
                    ContinuousEditor(state, { action ->
                        if (action is ContinuousEditorAction.AssignSourceSlice) assignments += action
                        presenter.onAction(action)
                    }, presenter::readout, autoChop = chop)
                }
                try {
                    fun sliceText() = scene.nodes().firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "ce-chop-slice"
                    }?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    fun expectedSlice(number: Int, start: String, end: String) =
                        if (locale == Locale.JAPANESE) "区間 $number / 4：$start — $end" else "Slice $number / 4: $start — $end"
                    suspend fun awaitChopUi(step: String, ready: () -> Boolean) {
                        val reached = withTimeoutOrNull(15_000) {
                            do { scene.render(System.nanoTime()).close(); delay(5) } while (!ready())
                            true
                        }
                        val shown = presenter.state.value
                        val document = backend.studio.document.value
                        val controls = scene.nodes().filter {
                            it.config.getOrNull(SemanticsProperties.TestTag) in listOf("ce-chop-slice-next", "ce-chop-assign-slice")
                        }.joinToString { "${it.config.getOrNull(SemanticsProperties.TestTag)}=${it.boundsInWindow}, disabled=${it.config.getOrNull(SemanticsProperties.Disabled) != null}" }
                        assertEquals(true, reached, "$locale ${width}x$height font=$font $step: owners=${scene.semanticsOwners.size}, " +
                            "slice=${sliceText()}, shownMarkers=${shown.original?.markers}, shownPad=${shown.selectedPadId}, " +
                            "canRedo=${shown.canRedo}, assignAllowed=${shown.permits(ContinuousCapability.ASSIGN_SOURCE_RANGE)}, " +
                            "revision=${document.revision}, savedMarkers=${document.project.source?.markers}, pad0=${document.project.pads[0].range}, " +
                            "status=${shown.status}, assignments=$assignments, $controls")
                    }
                    val input = directory.resolve("original.wav")
                    val samples = FloatArray(144_000 * 2)
                    listOf(24_000, 72_000, 120_000).forEach { first -> repeat(1_200) { n ->
                        samples[(first + n) * 2] = .7f * (1 - n / 1200f)
                        samples[(first + n) * 2 + 1] = -.2f * (1 - n / 1200f)
                    } }
                    Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
                    val originalBytes = Files.readAllBytes(input)
                    assertTrue(backend.importAudio(input).accepted); idle(backend)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.SetSourceRange(12_000, 132_000)))
                    until { presenter.state.value.permits(ContinuousCapability.ORIGINAL_PLAYBACK) }
                    val before = backend.studio.document.value
                    scene.pointer("ce-auto-chop")
                    until { presenter.autoChop.value != null }
                    val controller = requireNotNull(presenter.autoChop.value)
                    scene.pointer("ce-auto-count-less"); scene.pointer("ce-auto-count-less")
                    scene.pointer("ce-auto-prepare")
                    until { controller.state.value.canApply }
                    assertEquals(listOf(42_000L, 72_000L, 102_000L), controller.state.value.markers)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-auto-mode-ATTACK")
                    scene.pointer("ce-auto-prepare")
                    until { controller.state.value.canApply }
                    val cuts = requireNotNull(controller.state.value.markers)
                    assertEquals(listOf(24_000L, 72_000L, 120_000L), cuts)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-auto-next")
                    val preview = host.sourcePreview!!
                    val issuedAt = System.nanoTime()
                    val playbackStarted = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        withTimeoutOrNull(15_000) {
                            val observed = preview.state.first { it.owner == VocalPreviewOwner.CHOP && it.phase == VocalPreviewPhase.PLAYING }
                            assertFalse(presenter.dispatch(ContinuousEditorAction.SeekOriginal(0)), "CHOP owns SOURCE while playing")
                            observed
                        }
                    }
                    scene.pointer("ce-auto-preview") {
                        if (locale == Locale.JAPANESE && width == 390) {
                            // The audio can finish while a loaded UI has not resumed after its pointer event.
                            if (playbackStarted.await() != null) until { !preview.state.value.ownsSource }
                        }
                    }
                    val observed = assertNotNull(playbackStarted.await(), "$locale ${width}x$height elapsedMs=${(System.nanoTime() - issuedAt) / 1_000_000}: chop=${controller.state.value}, source=${preview.state.value}, output=${backend.engine.snapshot()}")
                    assertEquals(VocalPreviewOwner.CHOP, observed.owner)
                    assertEquals(VocalPreviewPhase.PLAYING, observed.phase)
                    scene.pointer("ce-auto-preview-stop")
                    until { !host.sourcePreview!!.state.value.ownsSource }
                    assertFalse(backend.engine.originalPlayback().playing)
                    assertEquals(before, backend.studio.document.value)
                    scene.reach("ce-auto-apply")
                    val screenshot = Path.of("build/reports/ui-evidence/auto-chop/auto-${locale.language}-${width}x$height.png")
                    Files.createDirectories(screenshot.parent)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(screenshot, it.bytes) } }
                    scene.pointer("ce-auto-apply")
                    until { presenter.autoChop.value == null }
                    val chopped = backend.studio.document.value
                    assertEquals(before.revision + 1, chopped.revision)
                    assertEquals(cuts, chopped.project.source!!.markers)
                    assertEquals(before.project.pads, chopped.project.pads)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
                    // Document edits finish before the presenter's projection and Compose frame. Observe
                    // Undo in the actual scene before Redo, so a late marker update cannot reset a clicked slice.
                    awaitChopUi("Undo removes the saved slices") {
                        presenter.state.value.original?.markers == before.project.source!!.markers && presenter.state.value.canRedo &&
                            scene.semanticsOwners.size == 1 && sliceText() == null
                    }
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(chopped.project, backend.studio.document.value.project)
                    awaitChopUi("Redo displays the first saved slice") {
                        val shown = presenter.state.value
                        shown.original?.markers == cuts && !shown.canRedo && shown.selectedPadId == 0 &&
                            shown.permits(ContinuousCapability.ASSIGN_SOURCE_RANGE) &&
                            sliceText() == expectedSlice(1, "0:00.25", "0:00.50")
                    }
                    scene.pointer("ce-chop-slice-next")
                    awaitChopUi("Next displays the second saved slice") { sliceText() == expectedSlice(2, "0:00.50", "0:01.50") }
                    scene.pointer("ce-chop-assign-slice")
                    assertEquals(listOf(ContinuousEditorAction.AssignSourceSlice(1, 0, chopped.project.source!!.assetHash, 24_000, 72_000)),
                        assignments, "$locale ${width}x$height: the single Assign click must submit the displayed native range")
                    awaitChopUi("Assign stores the displayed second slice on PAD A1") {
                        backend.studio.document.value.project.pads[0].range == FrameRange(24_000, 72_000)
                    }
                    assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(0)))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                    val project = backend.studio.document.value.project
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.FillPadPattern("pattern-1", 0, 960))).accepted)
                    val request = ExportRequest(backend.files.register(directory.resolve("beat.wav")), 48_000, bits = 24)
                    assertTrue(backend.studio.dispatch(Action.Export(request, PlaybackTarget.Pattern("pattern-1"))).accepted); idle(backend)
                    val audio = Files.newInputStream(directory.resolve("beat.wav")).use(WavCodec::read)
                    assertEquals(24, audio.info.bits); assertTrue(audio.samples.any { abs(it) > .01f })
                    val final = backend.studio.document.value.project
                    val bytes = ByteArrayOutputStream().also { ArchiveCodec().write(final, backend.assets, it) }.toByteArray()
                    val fresh = FileAssetStore(directory.resolve("fresh"))
                    assertEquals(final, ArchiveCodec().read(ByteArrayInputStream(bytes), fresh))
                    assertContentEquals(originalBytes, fresh.read(final.asset(project.source!!.assetHash)))
                    backend.flushAutosave()
                    assertEquals(final, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
                    assertEquals(cuts, final.source!!.markers)
                } finally { scene.close(); presenter.close(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun liveChopUsesPressTimeManualAndEstimatedOutputThenReopensExactNativeBytes() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(Triple(390, 844, 2f), Triple(1440, 1024, 1f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("live-chop-host-")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val endpoint = CountingTestSink()
                val buffer = java.util.concurrent.atomic.AtomicInteger(1024)
                val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { object : AudioSink by endpoint {
                    override fun bufferFrames() = buffer.get()
                    override fun pendingFrames() = 240L
                } }, microphone = { null })
                val host = DesktopEditorPorts(backend) { null }
                val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
                val timingActions = mutableListOf<ContinuousEditorAction>()
                val timingObservations = mutableListOf<String>()
                // Distinguish a missing press reading, an unsent action and a pass that ended before its edit.
                // Keep a bounded trace for a failure; observe the real readout without retrying the gesture.
                val liveObservations = ArrayDeque<String>()
                val liveReadouts = mutableMapOf<String, Int>()
                var liveStarted = 0L
                var livePhase = "before Begin"
                fun observeLive(event: String, clock: ContinuousEditorReadout? = null) {
                    if (liveStarted == 0L) return
                    val shown = presenter.state.value
                    val document = backend.studio.document.value
                    if (liveObservations.size == 48) liveObservations.removeFirst()
                    liveObservations.addLast("${(System.nanoTime() - liveStarted) / 1_000_000}ms $livePhase $event: " +
                        "live=${shown.liveChopping}, revision=${document.revision}, range=${document.project.source?.range}, " +
                        "gesture=${if (clock == null) "not sampled" else clock.liveChopGesture}, timing=${shown.liveChopTiming}, status=${shown.status}, " +
                        "original=${backend.engine.originalPlayback()}, originalProbe=${backend.engine.originalPlaybackProbe()}, " +
                        "probe=${backend.engine.liveChopProbe()}, output=${backend.engine.snapshot()}, driver=${backend.engine.status.value}")
                }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    ContinuousEditor(state, { action ->
                        if (action == ContinuousEditorAction.OpenLiveChopTiming || action == ContinuousEditorAction.CloseLiveChopTiming ||
                            action is ContinuousEditorAction.SetLiveChopCorrection) {
                            timingActions += action
                            timingObservations += "${System.nanoTime()}: $action ui=${state.liveChopTiming} " +
                                "probe=${backend.engine.liveChopProbe()} work=${backend.studio.work.value} driver=${backend.engine.status.value}"
                        }
                        if (action == ContinuousEditorAction.BeginLiveChop || action == ContinuousEditorAction.EndLiveChop ||
                            action == ContinuousEditorAction.StopAll || action is ContinuousEditorAction.CapturePad ||
                            action is ContinuousEditorAction.TapPad || action is ContinuousEditorAction.ReleasePad) observeLive("action=$action")
                        presenter.onAction(action)
                    }, {
                        presenter.readout().also { clock ->
                            if (liveStarted != 0L && liveReadouts.getOrDefault(livePhase, 0) < 4) {
                                liveReadouts[livePhase] = liveReadouts.getOrDefault(livePhase, 0) + 1
                                observeLive("readout", clock)
                            }
                        }
                    })
                }
                try {
                    fun timingDiagnostics(): String = "$locale ${width}x$height font=$font " +
                        "state=${presenter.state.value.liveChopTiming}, owners=${scene.semanticsOwners.size}, " +
                        "selected=${scene.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-live-timing-MANUAL" }?.config?.getOrNull(SemanticsProperties.Selected)}, " +
                        "slider=${scene.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-live-timing-slider" }?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo)}, " +
                        "status=${presenter.state.value.status}, work=${backend.studio.work.value}, driver=${backend.engine.status.value}, " +
                        "probe=${backend.engine.liveChopProbe()}, source=${host.sourcePreview?.state?.value}, actions=$timingActions, observations=$timingObservations"
                    suspend fun awaitTimingFrame(condition: () -> Boolean) {
                        // As in VocalGuidePanelTest, wait for the frame-clock work to finish; a
                        // guessed number of rendered frames can leave a scroll/radio animation active.
                        do { scene.render(System.nanoTime()).close(); delay(1) } while (!condition() || scene.hasInvalidations())
                    }
                    suspend fun setManualDraft() {
                        scene.pointer("ce-live-timing-MANUAL")
                        awaitTimingFrame { scene.tag("ce-live-timing-MANUAL").config.getOrNull(SemanticsProperties.Selected) == true }
                        scene.reach("ce-live-timing-slider")
                        assertTrue(scene.tag("ce-live-timing-slider").config[SemanticsActions.SetProgress].action!!(37f), timingDiagnostics())
                        awaitTimingFrame {
                            scene.tag("ce-live-timing-MANUAL").config.getOrNull(SemanticsProperties.Selected) == true &&
                                scene.tag("ce-live-timing-slider").config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 37f
                        }
                    }
                    suspend fun awaitTimingDialog(open: Boolean) {
                        // Actor completion and popup input ownership are separate from an enabled background tag.
                        val reached = withTimeoutOrNull(10_000) {
                            do { scene.render(System.nanoTime()).close(); delay(1) } while (
                                presenter.state.value.liveChopTiming.open != open ||
                                    scene.semanticsOwners.size != (if (open) 2 else 1) ||
                                    scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-live-timing-panel" } != open)
                            true
                        }
                        val tags = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.filter { it.startsWith("ce-live-timing") }
                        assertEquals(true, reached, "$locale ${width}x$height font=$font timing open=$open: " +
                            "state=${presenter.state.value.liveChopTiming}, owners=${scene.semanticsOwners.size}, " +
                            "status=${presenter.state.value.status}, driver=${backend.engine.status.value}, actions=$timingActions, tags=$tags")
                    }
                    val rate = if (locale == Locale.JAPANESE) 44_100 else 96_000
                    val input = directory.resolve("native.wav")
                    val samples = FloatArray(rate * 12 * 2) { n -> (kotlin.math.sin((n / 2) * .037) * if (n % 2 == 0) .4 else -.2).toFloat() }
                    Files.newOutputStream(input).use { WavCodec.writeFloat(it, samples, rate, 2) }
                    val originalBytes = Files.readAllBytes(input)
                    assertTrue(backend.importAudio(input).accepted); idle(backend)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(12f)))
                    val before = backend.studio.document.value
                    scene.pointer("ce-live-timing") {
                        assertEquals(1, timingActions.count { it == ContinuousEditorAction.OpenLiveChopTiming }, "First open must be emitted by its one pointer input")
                    }
                    awaitTimingDialog(true)
                    assertEquals(true, withTimeoutOrNull(15_000) { setManualDraft(); true }, "Cancel draft: ${timingDiagnostics()}")
                    // Delay the real queued cancel at its edit lock. An enabled background control is not
                    // evidence that the modal has closed, even when a fixed number of frames has elapsed.
                    val editLock = ContinuousEditorPresenter::class.java.getDeclaredField("serialized").run {
                        isAccessible = true; get(presenter) as kotlinx.coroutines.sync.Mutex
                    }
                    withTimeout(10_000) { editLock.lock() }
                    var locked = true
                    var closing: Deferred<Unit>? = null
                    try {
                        scene.pointer("ce-live-timing-cancel") {
                            assertEquals(1, timingActions.count { it == ContinuousEditorAction.CloseLiveChopTiming }, "Cancel must be emitted by its one pointer input")
                        }
                        assertTrue(presenter.state.value.liveChopTiming.open)
                        closing = async(start = CoroutineStart.UNDISPATCHED) { awaitTimingDialog(false) }
                        scene.settle()
                        assertFalse(closing.isCompleted, "A queued cancel must keep the next main-page input waiting while its popup still owns the pointer")
                        editLock.unlock(); locked = false
                        closing.await()
                    } finally {
                        if (locked) editLock.unlock()
                        closing?.cancelAndJoin()
                    }
                    assertEquals(before, backend.studio.document.value)
                    assertEquals(LiveChopTimingMode.ESTIMATED, presenter.state.value.liveChopTiming.correction.mode)
                    scene.pointer("ce-live-timing") {
                        assertEquals(2, timingActions.count { it == ContinuousEditorAction.OpenLiveChopTiming }, "Reopen must be emitted by its one pointer input")
                    }
                    awaitTimingDialog(true)
                    val manual = LiveChopCorrection(LiveChopTimingMode.MANUAL, 37)
                    var acknowledgement = "MANUAL selection and visible 37 ms draft"
                    val committed = try {
                        // One deadline covers the displayed draft, its one real pointer input and the actor commit.
                        withTimeoutOrNull(15_000) {
                            setManualDraft()
                            val route = assertNotNull(presenter.state.value.liveChopTiming.route, timingDiagnostics())
                            val priorApplies = timingActions.count { it is ContinuousEditorAction.SetLiveChopCorrection }
                            val png = Path.of("build/reports/ui-evidence/live-chop/timing-${locale.language}-${width}x$height.png")
                            Files.createDirectories(png.parent)
                            scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(png, it.bytes) } }
                            acknowledgement = "Apply pointer and payload"
                            scene.pointer("ce-live-timing-apply", stableHit = true) {
                                val applies = timingActions.filterIsInstance<ContinuousEditorAction.SetLiveChopCorrection>()
                                assertEquals(priorApplies + 1, applies.size, "Apply must be emitted by its one pointer input: ${timingDiagnostics()}")
                                assertEquals(ContinuousEditorAction.SetLiveChopCorrection(route, manual), applies.last(), timingDiagnostics())
                            }
                            acknowledgement = "actor commit and popup release"
                            awaitTimingFrame {
                                val timing = presenter.state.value.liveChopTiming
                                timing.correction == manual && !timing.open && scene.semanticsOwners.size == 1 &&
                                    scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-live-timing-panel" }
                            }
                            true
                        }
                    } catch (failure: AssertionError) {
                        fail("At $acknowledgement: ${timingDiagnostics()}", failure)
                    }
                    assertEquals(true, committed, "Timed out at $acknowledgement: ${timingDiagnostics()}")
                    assertEquals(before, backend.studio.document.value)
                    liveStarted = System.nanoTime()
                    observeLive("before Begin pointer")
                    scene.pointer("ce-live-chop"); until { presenter.state.value.liveChopping }; scene.settle()
                    livePhase = "reach PAD"; observeLive("Begin observed")
                    scene.reach("ce-pad-2"); scene.fullHit("ce-pad-2", width, height)
                    observeLive("PAD reached")
                    val pad = scene.tag("ce-pad-2").boundsInWindow.center
                    livePhase = "press"; observeLive("before Press")
                    val start = System.nanoTime()
                    scene.sendPointerEvent(PointerEventType.Press, pad, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    livePhase = "held"; observeLive("after Press")
                    scene.settle(); delay(75)
                    livePhase = "release"; observeLive("before Release")
                    val release = System.nanoTime()
                    scene.sendPointerEvent(PointerEventType.Release, pad, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
                    livePhase = "after release"; observeLive("after Release")
                    scene.settle(); until { presenter.state.value.liveChopTiming.lastCut != null || !presenter.state.value.liveChopping }
                    observeLive("receipt wait ended")
                    val receipt = assertNotNull(presenter.state.value.liveChopTiming.lastCut,
                        "$locale ${width}x$height font=$font: ${liveObservations.joinToString("\n")}")
                    assertTrue(receipt.eventNanos in start until release, "It uses the press, not the later release")
                    assertEquals(receipt.observedSourceFrame - kotlin.math.round(.037 * rate * 2).toLong(), receipt.requestedSourceFrame,
                        "$locale ${width}x$height: receipt=$receipt, timing=${presenter.state.value.liveChopTiming}")
                    assertEquals(before.revision + 1, backend.studio.document.value.revision)
                    val chopped = backend.studio.document.value.project
                    assertEquals(receipt.appliedSourceFrame, chopped.pads[2].range!!.start)
                    assertEquals(before.project.source!!.range.end, chopped.pads[2].range!!.end)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(chopped, backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
                    val pending = assertNotNull(presenter.captureLiveChop())
                    buffer.set(2048)
                    presenter.dispatch(ContinuousEditorAction.CapturePad(3, pending))
                    until { !presenter.state.value.liveChopping && presenter.state.value.liveChopTiming.correction.mode == LiveChopTimingMode.ESTIMATED }
                    assertEquals(chopped, backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.BeginLiveChop))
                    delay(150)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.CapturePad(4, assertNotNull(presenter.captureLiveChop()))))
                    until { presenter.state.value.liveChopTiming.lastCut != null }
                    assertEquals(LiveChopTimingMode.ESTIMATED, presenter.state.value.liveChopTiming.lastCut!!.mode)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.TapPad(2)))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.FillPadPattern("pattern-1", 2, 960))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(directory.resolve("beat.wav")), 24_000, bits = 24), PlaybackTarget.Pattern("pattern-1"))).accepted)
                    idle(backend)
                    assertTrue(Files.newInputStream(directory.resolve("beat.wav")).use(WavCodec::read).samples.any { abs(it) > .01f })
                    val final = backend.studio.document.value.project
                    val archive = ByteArrayOutputStream().also { ArchiveCodec().write(final, backend.assets, it) }.toByteArray()
                    val fresh = FileAssetStore(directory.resolve("fresh"))
                    assertEquals(final, ArchiveCodec().read(ByteArrayInputStream(archive), fresh))
                    assertContentEquals(originalBytes, fresh.read(final.asset(final.source!!.assetHash)))
                    backend.flushAutosave()
                    assertEquals(final, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
                } finally { scene.close(); presenter.close(); host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private suspend fun idle(backend: NextBackend) { until { backend.studio.work.value.jobId == null }; backend.studio.dispatch(Action.RefreshTransport) }
    private suspend fun until(ready: () -> Boolean) = withTimeout(15_000) { while (!ready()) delay(5) }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = requireNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }) { value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.awaitOwners(count: Int) = withTimeout(10_000) {
        do { render(System.nanoTime()).close(); delay(1) } while (semanticsOwners.size != count)
    }
    private suspend fun ImageComposeScene.reach(value: String) {
        fun SemanticsNode.contains(): Boolean = config.getOrNull(SemanticsProperties.TestTag) == value || children.any { it.contains() }
        // A desktop popup may still be positioning itself after its owner appears. Keep the
        // actual hit-area assertion, but let its scroll viewport settle before deciding it is clipped.
        repeat(16) {
            val current = tag(value)
            val visible = current.boundsInWindow
            if (visible.width >= current.size.width - 1 && visible.height >= current.size.height - 1) return
            for (ancestor in nodes().filter { it.contains() && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }) {
                val target = tag(value); val rect = ancestor.boundsInRoot
                if (rect.width <= 0 || rect.height <= 0) continue
                val x = target.positionInRoot.x; val y = target.positionInRoot.y
                val horizontal = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                val vertical = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val dx = if (horizontal != null && (x < rect.left || x + target.size.width > rect.right)) x - rect.left else 0f
                val dy = if (vertical != null && (y < rect.top || y + target.size.height > rect.bottom)) y - rect.top else 0f
                if (dx != 0f || dy != 0f) {
                    assertTrue(ancestor.config[SemanticsActions.ScrollBy].action!!(dx, dy))
                    var previous: Pair<Float?, Float?>? = null
                    for (frame in 0..12) { settle(); val position = horizontal?.value?.invoke() to vertical?.value?.invoke(); if (position == previous) break; previous = position }
                }
            }
            settle()
        }
        val node = tag(value)
        assertTrue(node.boundsInWindow.width >= node.size.width - 1 && node.boundsInWindow.height >= node.size.height - 1,
            "$value is clipped: visible=${node.boundsInWindow}, size=${node.size}")
    }
    private fun ImageComposeScene.fullHit(value: String, width: Int, height: Int) {
        val node = tag(value); val bounds = node.boundsInWindow
        assertTrue(bounds.width >= 48 && bounds.height >= 48, "$value below 48dp: $bounds")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
        assertTrue(bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1)
    }
    private suspend fun ImageComposeScene.pointer(value: String, stableHit: Boolean = false, afterRelease: suspend () -> Unit = {}) {
        ready(value)
        reach(value)
        val node = tag(value)
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), "$value disabled")
        assertTrue(node.boundsInWindow.width >= 48 && node.boundsInWindow.height >= 48, "$value is below 48dp")
        val center = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        if (stableHit) assertEquals(center, tag(value).boundsInWindow.center, "$value moved during its pointer press")
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        afterRelease()
        settle()
    }
    private suspend fun ImageComposeScene.ready(value: String) {
        // A completed Studio job can precede the Compose frame which re-enables SAVE or the next edit.
        val ready = withTimeoutOrNull(10_000) {
            while (true) {
                render(System.nanoTime()).close(); delay(1)
                val node = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
                if (node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null) break
            }
            true
        }
        assertEquals(true, ready, "$value did not become ready for its single input")
    }
}
