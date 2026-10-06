@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.desktop.next

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.engine.Tempo
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import com.choplab.ui.analysis.SourceAnalysisPhase
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Locale
import kotlin.math.*
import kotlin.test.*

/** Real shared BEAT input -> real bounded PCM analysis -> explicit revision edit -> archive/export. No devices/providers. */
class SourceAnalysisHostIntegrationTest {
    @Test fun normalBeatEntryOffersCandidatesAppliesOnceAndRestoresAtDesktopAndPhoneInBothLanguages() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 838, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("source-analysis-host-")
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
                    val analysis by presenter.sourceAnalysis.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, sourceAnalysis = analysis)
                }
                try {
                    val source = directory.resolve("source.wav")
                    val samples = FloatArray(12 * 48_000 * 2) { index ->
                        val frame = index / 2
                        val phase = frame % (48_000 * 60 / 98.0)
                        val chord = listOf(60, 64, 67).sumOf { note ->
                            sin(frame * 2 * PI * 440 * 2.0.pow((note - 69) / 12.0) / 48_000) * if (note == 60) .18 else .12
                        } * (.25 + .75 * exp(-phase / 7_000))
                        val kick = if (phase < 4_800) .7 * sin(phase * 2 * PI * 78 / 48_000) * exp(-phase / 650) else 0.0
                        ((chord + kick) * if (index % 2 == 0) 1.0 else -.65).toFloat()
                    }
                    Files.newOutputStream(source).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
                    assertTrue(backend.importAudio(source).accepted)
                    until { backend.studio.work.value.jobId == null }
                    val hash = requireNotNull(backend.studio.document.value.project.source).assetHash
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(hash, FrameRange(0, 4_800), 0))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(120_000, 620)))).accepted)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
                    until { presenter.state.value.permits(ContinuousCapability.SOURCE_ANALYSIS) }
                    scene.settle()
                    scene.pointer("ce-nav-BEAT")
                    scene.pointer("ce-tempo")
                    scene.pointer("ce-source-analysis")
                    until { presenter.sourceAnalysis.value != null }
                    scene.awaitOwners(2)
                    val analysis = requireNotNull(presenter.sourceAnalysis.value)
                    val original = backend.studio.document.value
                    scene.pointer("source-analysis-start")
                    until { analysis.state.value.phase == SourceAnalysisPhase.RESULT }
                    scene.settle()
                    assertEquals(original, backend.studio.document.value, "Analysing must not edit the song")
                    assertNull(analysis.state.value.selectedMilliBpm)
                    assertFalse(analysis.state.value.canApply)
                    val result = requireNotNull(analysis.state.value.result)
                    val tempo = result.tempos.minBy { abs(it.milliBpm - 98_000) }
                    assertTrue(abs(tempo.milliBpm - 98_000) <= 1_000, result.tempos.toString())
                    assertTrue(result.keys.any { it.tonic == 0 && it.mode == com.choplab.core.analysis.KeyMode.MAJOR }, result.keys.toString())
                    scene.pointer("source-analysis-tempo-${tempo.milliBpm}")
                    assertEquals(original, backend.studio.document.value, "Selecting a candidate must not edit the song")
                    scene.pointer("source-analysis-apply")
                    until { analysis.state.value.applied }
                    val applied = backend.studio.document.value
                    assertEquals(original.revision + 1, applied.revision)
                    assertEquals(tempo.milliBpm, applied.project.tempo.milliBpm)
                    assertEquals(original.project.tempo.swingPermille, applied.project.tempo.swingPermille)
                    assertEquals(original.project, applied.project.copy(tempo = original.project.tempo))
                    scene.reach("source-analysis-applied")
                    for (tag in listOf("source-analysis-stop", "source-analysis-close")) scene.fullHit(tag, width, height)
                    val evidence = java.io.File("build/reports/ui-evidence/source-analysis-host").apply { mkdirs() }
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use {
                        evidence.resolve("${locale.language}-${width}-font${(font * 100).toInt()}.png").writeBytes(it.bytes)
                    } }
                    scene.pointer("source-analysis-stop")
                    assertEquals(applied, backend.studio.document.value)
                    scene.pointer("source-analysis-close")
                    scene.awaitOwners(1)
                    assertNull(presenter.sourceAnalysis.value)
                    scene.pointer("ce-undo")
                    until { backend.studio.document.value.project == original.project }
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                    assertEquals(applied.project, backend.studio.document.value.project)
                    scene.pointer("ce-nav-SAVE")
                    scene.pointer("ce-export")
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val wav = Files.newInputStream(exported).use(WavCodec::read)
                    assertEquals(2, wav.info.channels)
                    assertEquals(48_000, wav.info.sampleRate)
                    assertEquals(24, wav.info.bits)
                    for (channel in 0..1) assertTrue(wav.samples.filterIndexed { i, _ -> i % 2 == channel }.any { abs(it) > .01f })
                    scene.pointer("ce-save")
                    until { presenter.state.value.status == ContinuousStatus.SAVED }
                    val restored = NextBackend.create(directory.resolve("restored"), sinkFactory = { error("No native device") }, microphone = { null })
                    try {
                        assertTrue(restored.openProject(archive).accepted)
                        until { restored.studio.work.value.jobId == null }
                        assertEquals(applied.project, restored.studio.document.value.project)
                        assertContentEquals(Files.readAllBytes(source), restored.assets.read(applied.project.asset(hash)))
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
