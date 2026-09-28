@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.desktop.next

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import com.choplab.ui.pattern.PatternPhase
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Normal BEAT pointers -> presenter-owned factory -> real host render -> atomic edit -> WAV/archive. No devices/providers. */
class StepPatternHostIntegrationTest {
    @Test fun normalBeatEntryEditsRepeatsPlacesAndRestoresAtDesktopAndPhoneInBothLanguages() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 838, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("step-pattern-host-")
                val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { error("No native device") }, microphone = { null })
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = DesktopEditorPorts(backend, parent = { null })
                val archive = directory.resolve("song.choplab")
                val exported = directory.resolve("song.wav")
                val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by host {
                    override suspend fun chooseSave() = backend.files.register(archive)
                    override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(exported), frames.toInt(), bits = 24)
                })
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    val patterns by presenter.stepPatterns.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, stepPatterns = patterns)
                }
                try {
                    val source = directory.resolve("source.wav")
                    val bytes = FloatArray(9_600) { i -> (sin(i / 2 * .05) * if (i % 2 == 0) .3 else -.15).toFloat() }
                    Files.newOutputStream(source).use { WavCodec.writeFloat(it, bytes, 48_000, 2) }
                    assertTrue(backend.importAudio(source).accepted)
                    until { backend.studio.work.value.jobId == null }
                    val hash = requireNotNull(backend.studio.document.value.project.source).assetHash
                    for (id in 0..1) assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(hash, FrameRange(0, 4_800), id))).accepted)
                    assertTrue(backend.studio.dispatch(Action.SelectPad(0)).accepted)
                    until { presenter.state.value.permits(ContinuousCapability.STEP_PATTERNS) }
                    scene.settle()
                    scene.pointer("ce-nav-BEAT")
                    scene.pointer("ce-pad-details")
                    scene.pointer("ce-step-patterns")
                    val original = backend.studio.document.value
                    until { presenter.stepPatterns.value != null }
                    scene.setText("pattern-name", "Discard me")
                    scene.pointer("ce-step-patterns-close")
                    scene.awaitOwners(1)
                    assertEquals(original, backend.studio.document.value)

                    scene.pointer("ce-step-patterns")
                    val editor = requireNotNull(presenter.stepPatterns.value)
                    scene.setText("pattern-name", "A")
                    scene.pointer("pattern-step-0")
                    scene.pointer("pattern-step-4")
                    scene.pointer("pattern-save")
                    until { editor.state.value.phase == PatternPhase.EDITING && !editor.state.value.dirty }
                    assertEquals(original.revision + 1, backend.studio.document.value.revision)
                    scene.setText("pattern-repeat", "2")
                    scene.pointer("pattern-queue")
                    scene.pointer("pattern-new")
                    scene.setText("pattern-name", "B")
                    scene.pointer("pattern-bars-2")
                    scene.pointer("pattern-pad")
                    scene.pointer("pattern-pad-1")
                    scene.awaitOwners(2)
                    until { editor.state.value.selectedPadId == 1 }
                    scene.pointer("pattern-step-1")
                    scene.pointer("pattern-step-9")
                    scene.pointer("pattern-save")
                    until { editor.state.value.phase == PatternPhase.EDITING && !editor.state.value.dirty }
                    scene.pointer("pattern-queue")
                    scene.setText("pattern-start", "2")
                    assertEquals(listOf(2, 2), editor.state.value.sequence.map { it.repeats })
                    val beforePlacement = backend.studio.document.value
                    scene.pointer("pattern-place")
                    until { editor.state.value.applied && editor.state.value.phase == PatternPhase.EDITING }
                    scene.settle()
                    val placed = backend.studio.document.value
                    assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "pattern-queue-range" },
                        "An emptied queue must not mislabel the placed six-bar range as zero bars")
                    assertEquals(beforePlacement.revision + 1, placed.revision)
                    assertEquals(8, placed.project.clips.size)
                    assertEquals(listOf(1, 2), placed.project.patterns.map { it.bars })
                    assertEquals(original.project.source, placed.project.source)
                    assertEquals(original.project.banks, placed.project.banks)
                    assertEquals(1, backend.studio.selection.value.padId)
                    for (tag in listOf("ce-step-patterns-stop", "ce-step-patterns-close")) scene.fullHit(tag, width, height)
                    val evidence = java.io.File("build/reports/ui-evidence/step-pattern-host").apply { mkdirs() }
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use {
                        evidence.resolve("${locale.language}-${width}-font${(font * 100).toInt()}.png").writeBytes(it.bytes)
                    } }
                    scene.pointer("ce-step-patterns-stop")
                    assertEquals(placed, backend.studio.document.value)
                    scene.pointer("ce-step-patterns-close")
                    scene.awaitOwners(1)
                    assertNull(presenter.stepPatterns.value)
                    scene.pointer("ce-undo")
                    until { backend.studio.document.value.canRedo }
                    assertEquals(beforePlacement.project, backend.studio.document.value.project, "The whole queue is one Undo")
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                    assertEquals(placed.project, backend.studio.document.value.project)
                    scene.pointer("ce-nav-SAVE")
                    scene.pointer("ce-export")
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val wav = Files.newInputStream(exported).use(WavCodec::read)
                    assertEquals(2, wav.info.channels)
                    assertEquals(48_000, wav.info.sampleRate)
                    assertEquals(24, wav.info.bits)
                    assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == 0 }.any { abs(it) > .01f })
                    assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == 1 }.any { abs(it) > .01f })
                    scene.pointer("ce-save")
                    until { presenter.state.value.status == ContinuousStatus.SAVED }
                    ZipFile(archive.toFile()).use { zip -> placed.project.assets.forEach { assertNotNull(zip.getEntry(it.entryName)) } }
                    val restored = NextBackend.create(directory.resolve("restored"), sinkFactory = { error("No native device") }, microphone = { null })
                    try {
                        assertTrue(restored.openProject(archive).accepted)
                        until { restored.studio.work.value.jobId == null }
                        assertEquals(placed.project, restored.studio.document.value.project)
                        assertContentEquals(Files.readAllBytes(source), restored.assets.read(placed.project.asset(hash)))
                    } finally { restored.shutdown() }
                } finally {
                    scene.close(); presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively()
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    private suspend fun until(ready: () -> Boolean) = withTimeout(10_000) { while (!ready()) delay(5) }
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
    private suspend fun ImageComposeScene.pointer(value: String) {
        ready(value)
        reach(value)
        val node = tag(value)
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), "$value disabled")
        val center = node.boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private suspend fun ImageComposeScene.setText(value: String, text: String) {
        ready(value)
        reach(value)
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
