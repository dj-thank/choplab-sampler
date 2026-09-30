@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.separation.*
import com.choplab.jvm.*
import com.choplab.jvm.separation.*
import com.choplab.ui.*
import com.choplab.ui.separation.*
import com.choplab.ui.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.*

/** SOURCE entry, actual Desktop ports/worker/Studio/export/archive. Only inference and audio endpoints are synthetic. */
class FourStemHostTest {
    @Test fun switchingSourceToolsClosesThePreviousOwnerAndFencesLateSeparationWithoutChangingTheSong() = runBlocking<Unit> {
        val native = GainFactory().apply { release = CompletableDeferred() }
        val onlineCloses = AtomicInteger()
        val f = Fixture(native, wrap = { real -> object : ContinuousEditorPorts by real {
            override val onlineSource = OnlineSourceHost { _, stop ->
                OnlineImportSession(object : OnlineSourcePort {
                    override val state = MutableStateFlow(OnlineWorkerState())
                    override fun search(query: String, catalog: OnlineCatalog) = false
                    override fun inspect(id: String) = false
                    override fun selectFormat(id: String) = false
                    override fun save(id: String) = false
                    override fun cancel() {}
                    override fun stopAll() = stop()
                    override fun close() { onlineCloses.incrementAndGet() }
                }) { null }
            }
        } })
        try {
            f.ready()
            val before = f.backend.studio.document.value
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.ImportOnline))
            val online = assertNotNull(f.presenter.onlineSource.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenFourStems))
            assertTrue(online.state.value.closed)
            assertNull(f.presenter.onlineSource.value)
            assertEquals(1, onlineCloses.get())
            val editor = assertNotNull(f.presenter.fourStems.value)
            assertTrue(editor.start()); withTimeout(10_000) { native.entered.await() }
            assertTrue(withTimeout(1_000) { f.presenter.dispatch(ContinuousEditorAction.ImportOnline) })
            assertNull(f.presenter.fourStems.value)
            assertEquals(FourStemPhase.CLOSED, editor.state.value.phase)
            assertNotNull(f.presenter.onlineSource.value)
            assertEquals(0, native.closes.get(), "Switching tools must not release a still-running native session")
            assertTrue(PcmMemoryBudget.shared.statistics().usedBytes >= FourStemSpec.PIPELINE_PCM_BYTES + FourStemSpec.NATIVE_IO_PCM_BYTES)
            native.release!!.complete(Unit)
            until { native.closes.get() == 1 }
            until { PcmMemoryBudget.shared.statistics().usedBytes < FourStemSpec.PIPELINE_PCM_BYTES }
            assertEquals(before, f.backend.studio.document.value)
            assertEquals(f.original.byteCount, f.backend.assets.storedBytes())
        } finally { native.release!!.complete(Unit); f.close() }
        assertEquals(2, onlineCloses.get())
    }

    @Test fun sourceEntryPreparesThenExplicitlyPlacesFourHeadsAsOneUndoAndReopensAllBytesInBothLanguagesAndSizes() = runBlocking<Unit> {
        val localeBefore = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(Triple(1440,838,1f), Triple(390,844,2f))) {
            Locale.setDefault(locale)
            val f = Fixture()
            val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext) {
                val state by f.presenter.state.collectAsState()
                val stems by f.presenter.fourStems.collectAsState()
                ContinuousEditor(state, f.presenter::onAction, f.presenter::readout, fourStems = stems)
            }
            try {
                f.ready()
                val before = f.backend.studio.document.value
                val originalBytes = f.backend.assets.read(f.original)
                scene.pointer("ce-four-stems-open"); scene.awaitOwners(2)
                val editor = assertNotNull(f.presenter.fourStems.value)
                assertEquals(FourStemPhase.IDLE, editor.state.value.phase)
                assertEquals(0, f.native.opens.get(), "Opening does not fetch a model or start inference")
                assertEquals(before, f.backend.studio.document.value)
                scene.pointer("four-stem-start"); until { editor.state.value.phase == FourStemPhase.READY }
                assertEquals(1, f.native.opens.get()); assertFalse(f.native.download)
                assertEquals(before, f.backend.studio.document.value)
                assertEquals(StemPart.entries, editor.state.value.prepared!!.stems.map { it.part })
                scene.pointer("four-stem-mix-INSTRUMENTAL"); scene.setText("four-stem-first-bar", "2")
                scene.reach("four-stem-apply"); scene.fullHit("four-stem-apply", width, height)
                scene.fullHit("four-stem-stop", width, height); scene.fullHit("four-stem-close", width, height)
                scene.pointer("four-stem-apply"); until { editor.state.value.phase == FourStemPhase.APPLIED }
                val after = f.backend.studio.document.value
                assertEquals(before.revision + 1, after.revision)
                assertEquals(before.project.source, after.project.source)
                assertEquals(before.project.lyrics, after.project.lyrics)
                assertEquals(4, after.project.clips.size)
                assertEquals(listOf(false, false, false, true), after.project.tracks.map { it.mute })
                assertTrue(after.project.clips.all { it.startTick == 3840L && it.range == FrameRange(0, 8820) })
                scene.pointer("four-stem-close"); scene.awaitOwners(1)
                scene.pointer("ce-nav-BEAT"); scene.pointer("ce-undo"); until { f.backend.studio.document.value.canRedo }
                assertEquals(before.project, f.backend.studio.document.value.project)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); until { f.backend.studio.document.value.project == after.project }
                // Closing the worker left the backend's PCM cache and the original SOURCE usable.
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.PlayOriginal))
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopOriginal))
                scene.pointer("ce-nav-SAVE"); scene.pointer("ce-export"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val audio = Files.newInputStream(f.export).use(WavCodec::read)
                assertEquals(24, audio.info.bits); assertEquals(2, audio.info.channels)
                assertEquals(105600L, audio.info.frames)
                assertEquals(.12f, audio.samples[100800 * 2], .00001f)
                assertEquals(-.03f, audio.samples[100800 * 2 + 1], .00001f)
                assertTrue(audio.samples.take(96_000 * 2).all { abs(it) < .000001f })
                scene.pointer("ce-save"); until { f.presenter.state.value.status == ContinuousStatus.SAVED }
                val restored = NextBackend.create(f.directory.resolve("restored"), sinkFactory = { error("No native audio") }, microphone = { null })
                try {
                    assertTrue(restored.openProject(f.archive).accepted); until { restored.studio.work.value.jobId == null }
                    assertEquals(after.project, restored.studio.document.value.project)
                    after.project.assets.forEach { assertContentEquals(f.backend.assets.read(it), restored.assets.read(it)) }
                    assertContentEquals(originalBytes, restored.assets.read(f.original))
                } finally { restored.shutdown() }
                assertEquals(1, f.native.closes.get())
            } finally { scene.close(); f.close() }
        } } finally { Locale.setDefault(localeBefore) }
    }

    @Test fun stopCloseNavigationOutputLossAndHostShutdownFenceLateNativeWorkWithoutReleasingItsMemoryEarly() = runBlocking<Unit> {
        for (ending in listOf("stop", "source-stop", "close", "navigate", "output-loss", "shutdown", "revision")) {
            val native = GainFactory().apply { release = CompletableDeferred() }
            val f = Fixture(native)
            try {
                f.ready()
                val before = f.backend.studio.document.value
                assertTrue(withTimeout(1000) { f.presenter.dispatch(ContinuousEditorAction.OpenFourStems) })
                val editor = assertNotNull(f.presenter.fourStems.value)
                assertTrue(editor.start()); withTimeout(10000) { native.entered.await() }
                val retained = PcmMemoryBudget.shared.statistics().usedBytes
                assertTrue(retained >= FourStemSpec.PIPELINE_PCM_BYTES + FourStemSpec.NATIVE_IO_PCM_BYTES)
                withTimeout(1500) { when (ending) {
                    "stop" -> assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopAll))
                    "source-stop" -> assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopOriginal))
                    "close" -> assertTrue(f.presenter.dispatch(ContinuousEditorAction.CloseFourStems))
                    "navigate" -> assertTrue(f.presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)))
                    "output-loss" -> { f.backend.engine.releaseOutput(); until { editor.state.value.phase == FourStemPhase.CANCELLING } }
                    "shutdown" -> f.shutdown()
                    "revision" -> { assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.Rename("Changed"))).accepted); until { editor.state.value.phase == FourStemPhase.CANCELLING } }
                } }
                assertEquals(0, native.closes.get(), "A still-running native call keeps its session and memory: $ending")
                assertTrue(PcmMemoryBudget.shared.statistics().usedBytes >= FourStemSpec.PIPELINE_PCM_BYTES + FourStemSpec.NATIVE_IO_PCM_BYTES)
                assertFalse(editor.apply())
                native.release!!.complete(Unit)
                until { native.closes.get() == 1 }
                until { PcmMemoryBudget.shared.statistics().usedBytes < FourStemSpec.PIPELINE_PCM_BYTES }
                assertTrue(f.backend.studio.document.value.project.clips.isEmpty())
                assertEquals(before.project.assets, f.backend.studio.document.value.project.assets)
                assertEquals(before.project.source, f.backend.studio.document.value.project.source)
                assertEquals(f.original.byteCount, f.backend.assets.storedBytes())
                assertNull(editor.state.value.prepared)
                if (ending != "shutdown") assertTrue(f.presenter.dispatch(ContinuousEditorAction.CloseFourStems))
            } finally { native.release!!.complete(Unit); f.close() }
        }
    }

    @Test fun permissionArmingAndStaleReadyResultsRefuseApplyAndStartingAgainDoesNotCallTheWorker() = runBlocking<Unit> {
        val permission = CompletableDeferred<Unit>(); val permissionEntered = CompletableDeferred<Unit>()
        val f = Fixture(wrap = { real -> object : ContinuousEditorPorts by real {
            override val recordingCue: RecordingCuePort? = null
            override suspend fun startVoice(maxSeconds: Int): VoiceStart { permissionEntered.complete(Unit); permission.await(); return VoiceStart.DENIED }
        } })
        try {
            f.ready()
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenFourStems))
            val editor = assertNotNull(f.presenter.fourStems.value)
            assertTrue(editor.start()); until { editor.state.value.phase == FourStemPhase.READY }
            val before = f.backend.studio.document.value
            val recording = async { f.presenter.dispatch(ContinuousEditorAction.RecordVoice) }
            permissionEntered.await()
            until { editor.state.value.availability == FourStemAvailability.RECORDING }
            assertFalse(withTimeout(1000) { editor.apply() }); assertFalse(withTimeout(1000) { editor.start() })
            assertEquals(SeparationProblem.RECORDING, editor.state.value.problem)
            assertEquals(1, f.native.opens.get()); assertEquals(before, f.backend.studio.document.value)
            permission.complete(Unit); assertFalse(recording.await())
            // The recording action has returned, but the derived availability flow may publish
            // the cleared arming flag on its next turn. Reopen only after that real guard clears.
            until { editor.state.value.availability == FourStemAvailability.EDITABLE }
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.CloseFourStems))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenFourStems))
            val next = assertNotNull(f.presenter.fourStems.value)
            assertTrue(next.start()); until { next.state.value.phase == FourStemPhase.READY }
            assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.Rename("Changed"))).accepted)
            assertFalse(next.apply()); assertEquals(SeparationProblem.STALE_DOCUMENT, next.state.value.problem)
            assertTrue(f.backend.studio.document.value.project.clips.isEmpty())
        } finally { permission.complete(Unit); f.close() }
    }

    private class Fixture(val native: GainFactory = GainFactory(), wrap: (DesktopEditorPorts) -> ContinuousEditorPorts = { it }) {
        val directory = Files.createTempDirectory("four-stem-host-")
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingTestSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend, fourStemSessions = native, fourStemMemory = { SeparationMemory(8L shl 30, 4L shl 30, false) }) { null }
        val archive = directory.resolve("song.choplab")
        val export = directory.resolve("song.wav")
        private val bytes = ByteArrayOutputStream().also { stream ->
            val writer = WavCodec.FloatWriter(stream, 9600)
            writer.write(FloatArray(9600 * 2) { if (it % 2 == 0) .2f else -.05f }); writer.finish()
        }.toByteArray()
        val original = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 9600, "Original.wav")
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by wrap(real) {
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(export), frames.toInt(), tailFrames = 0, bits = 24)
        })
        suspend fun ready() {
            backend.assets.write(original, bytes)
            assertTrue(backend.studio.dispatch(Action.New(Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, original.frames)),
                lyrics = frozenListOf(LyricLine("line", "Keep me", 0, 3840))))).accepted)
            withTimeout(10000) { while (!presenter.state.value.permits(ContinuousCapability.FOUR_STEMS) || !presenter.state.value.permits(ContinuousCapability.RECORD_VOICE)) delay(5) }
        }
        private var closed = false
        suspend fun shutdown() { if (!closed) { presenter.close(); real.close(); backend.shutdown(); scope.cancel(); closed = true } }
        suspend fun close() { shutdown(); directory.toFile().deleteRecursively() }
    }
    private class GainFactory : FourStemSessionFactory {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        @Volatile var download = false
        override fun open(memory: PcmMemoryBudget, available: SeparationMemory, allowDownload: Boolean, check: () -> Unit): FourStemInference {
            opens.incrementAndGet(); download = allowDownload
            return object : FourStemInference {
                override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                    runBlocking { memory.reserve(FourStemSpec.NATIVE_IO_PCM_BYTES) }.use {
                        val output = FloatArray(FourStemSpec.FRAMES * 8) { index ->
                            channelMajor[index % (FourStemSpec.FRAMES * 2)] * (index / (FourStemSpec.FRAMES * 2) + 1) * .1f
                        }
                        entered.complete(Unit)
                        release?.let { runBlocking { it.await() } }
                        consume(FloatBuffer.wrap(output))
                    }
                }
                override fun cancel() {}
                override fun close() { closes.incrementAndGet() }
            }
        }
    }
    private suspend fun until(ready: suspend () -> Boolean) = withTimeout(10_000) { while (!ready()) delay(5) }
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
        repeat(40) {
            if (nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == value }) {
                val fields = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "four-stem-panel" }
                if (fields != null) { fields.config[SemanticsActions.ScrollBy].action!!(0f, 140f); settle() }
            }
            if (nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == value }) return@repeat
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
            val target = tag(value)
            if (target.boundsInWindow.width >= target.size.width - 1 && target.boundsInWindow.height >= target.size.height - 1) return
        }
        val node = tag(value)
        assertTrue(node.boundsInWindow.width >= node.size.width - 1 && node.boundsInWindow.height >= node.size.height - 1, "$value is clipped")
    }
    private fun ImageComposeScene.fullHit(value: String, width: Int, height: Int) {
        val node = tag(value); val bounds = node.boundsInWindow
        assertTrue(bounds.width >= 48 && bounds.height >= 48, "$value below 48dp: $bounds")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
        assertTrue(bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1)
    }
    private suspend fun ImageComposeScene.pointer(value: String) {
        settle()
        reach(value)
        ready(value)
        val node = tag(value)
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), "$value disabled")
        val center = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private suspend fun ImageComposeScene.setText(value: String, text: String) {
        settle()
        reach(value)
        ready(value)
        assertTrue(tag(value).config[SemanticsActions.SetText].action!!(AnnotatedString(text)))
        settle()
        assertEquals(text, tag(value).config[SemanticsProperties.EditableText].text)
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
