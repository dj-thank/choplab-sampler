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
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    val chop by presenter.autoChop.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, autoChop = chop)
                }
                try {
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
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(chopped.project, backend.studio.document.value.project)
                    scene.pointer("ce-chop-slice-next")
                    scene.pointer("ce-chop-assign-slice")
                    until { backend.studio.document.value.project.pads[0].range == FrameRange(24_000, 72_000) }
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
    private suspend fun ImageComposeScene.pointer(value: String, afterRelease: suspend () -> Unit = {}) {
        ready(value)
        reach(value)
        val node = tag(value)
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), "$value disabled")
        assertTrue(node.boundsInWindow.width >= 48 && node.boundsInWindow.height >= 48, "$value is below 48dp")
        val center = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
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
