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
import com.choplab.engine.MixerSnapshot
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.mixer.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.test.*

/** Real presenter, Desktop ports, shared DSP, WAV/ZIP/archive; synthetic PCM and output only. */
class MixerHostTest {
    @Test fun ordinaryEditorAppliesOneMixUndoAndExportsChosenFormatTailAndStemsThenReopens() = runBlocking<Unit> {
        val oldLocale = Locale.getDefault()
        try { for ((locale, width, height, scale) in listOf(
            Layout(Locale.JAPANESE, 390, 844, 2f), Layout(Locale.ENGLISH, 1440, 1024, 1f))) {
            Locale.setDefault(locale)
            val f = Fixture()
            var scene: ImageComposeScene? = null
            try {
                f.ready()
                val before = f.backend.studio.document.value
                scene = ImageComposeScene(width, height, Density(1f, scale), coroutineContext = coroutineContext) {
                    val state by f.presenter.state.collectAsState()
                    ContinuousEditor(state, f.presenter::onAction, f.presenter::readout, mixerReadout = f.presenter::readMixer)
                }
                scene.pointer("ce-nav-BEAT")
                scene.pointer("ce-pad-details")
                scene.pointer("ce-mixer-open")
                scene.setText("ce-mixer-gain", "65")
                scene.setText("ce-mixer-pan", "-25")
                scene.setText("ce-mixer-low_db", "3")
                scene.setText("ce-mixer-delay_send", "40")
                assertEquals(before.project, f.backend.studio.document.value.project)
                scene.fullHit("ce-mixer-apply", width, height); scene.fullHit("ce-mixer-stop", width, height)
                scene.pointer("ce-mixer-apply")
                until { f.backend.studio.document.value.revision == before.revision + 1 }
                val mixed = f.backend.studio.document.value.project
                assertEquals(.65f, mixed.tracks[0].gain); assertEquals(-.25f, mixed.tracks[0].pan)
                assertEquals(3f, mixed.tracks[0].fx.insert.eq.lowDb)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, f.backend.studio.document.value.project)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(mixed, f.backend.studio.document.value.project)
                scene.pointer("ce-nav-SAVE")
                scene.pointer("ce-mixer-open")
                scene.pointer("ce-mixer-switch-delay")
                scene.setText("ce-mixer-delay_time", "10")
                scene.setText("ce-mixer-feedback", "0")
                scene.setText("ce-mixer-gain", "75")
                scene.pointer("ce-mixer-apply")
                until { f.backend.studio.document.value.project.mix.delay.enabled }
                val production = f.backend.studio.document.value.project
                // The real driver reports actual shared-graph levels with the coherent bus identities.
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.PlaySong))
                val meter = MixerSnapshot()
                until { f.presenter.readMixer(meter) && meter.masterPeak.any { it > 0f } }
                assertNotNull((0 until com.choplab.engine.MixerProgram.MAX_BUSES).firstOrNull { meter.program.busId(it) == "bank" })
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopAll))
                scene.pointer("ce-export-bits-16"); scene.pointer("ce-export-tail")
                scene.reach("ce-export-bits-16")
                val evidence = java.io.File(System.getProperty("choplab.ui.evidenceDir", "build/reports/ui-evidence"), "mixer-save").apply { mkdirs() }
                scene.render(System.nanoTime()).use { image -> requireNotNull(image.encodeToData()).use {
                    java.io.File(evidence, "save-${locale.language}-${width}x$height.png").writeBytes(it.bytes)
                } }
                scene.pointer("ce-export"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val exact = Files.newInputStream(f.wav).use(WavCodec::read)
                assertEquals(16, exact.info.bits); assertEquals(4096, exact.info.frames)
                scene.pointer("ce-export-bits-24"); scene.pointer("ce-export-tail")
                scene.pointer("ce-export"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val tail = Files.newInputStream(f.wav).use(WavCodec::read)
                assertEquals(24, tail.info.bits); assertTrue(tail.info.frames > 4096)
                scene.pointer("ce-export-stems"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val entries = linkedMapOf<String, ByteArray>()
                ZipInputStream(Files.newInputStream(f.zip)).use { zip ->
                    while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
                }
                assertEquals(4, entries.size)
                assertTrue(entries.getValue("manifest.json").decodeToString().contains("POST_FADER_POST_INSERT_PRE_MASTER"))
                for ((name, bytes) in entries) if (name.endsWith(".wav")) {
                    val info = bytes.inputStream().use(WavCodec::inspect)
                    assertEquals(32, info.bits); assertEquals(2, info.channels); assertEquals(tail.info.frames, info.frames)
                }
                scene.pointer("ce-save"); until { f.presenter.state.value.status == ContinuousStatus.SAVED }
                val reopened = NextBackend.create(f.root.resolve("reopened"), sinkFactory = { error("No device") }, microphone = { null })
                try {
                    assertTrue(reopened.openProject(f.archive).accepted); until { reopened.studio.work.value.jobId == null }
                    assertEquals(production, reopened.studio.document.value.project)
                    assertContentEquals(f.bytes, reopened.assets.read(f.asset))
                } finally { reopened.shutdown() }
                f.backend.flushAutosave()
                scene.close(); scene = null; f.shutdown()
                val restarted = NextBackend.create(f.root.resolve("profile"), sinkFactory = { error("No device") }, microphone = { null })
                try { assertEquals(production, restarted.studio.document.value.project) } finally { restarted.shutdown() }
            } finally { scene?.close(); f.close() }
        } } finally { Locale.setDefault(oldLocale) }
    }

    @Test fun cancelStaleAndRecordingRefuseMixWithoutChangingTheSavedGraph() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.ready()
            val before = f.backend.studio.document.value
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Open())))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Change(MixerField.GAIN, "25"))))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Cancel)))
            assertEquals(before, f.backend.studio.document.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Open())))
            assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.Rename("New title"))).accepted)
            assertFalse(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Apply)))
            assertEquals(before.project.mix, f.backend.studio.document.value.project.mix)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Cancel)))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.RecordHits))
            assertFalse(f.presenter.dispatch(ContinuousEditorAction.Mixer(MixerAction.Open())))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopAll))
        } finally { f.close() }
    }

    private data class Layout(val locale: Locale, val width: Int, val height: Int, val scale: Float)
    private class Fixture {
        val root = Files.createTempDirectory("mixer-host-")
        val backend = NextBackend.create(root.resolve("profile"), sinkFactory = { CountingTestSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        val wav = root.resolve("song.wav"); val zip = root.resolve("stems.zip"); val archive = root.resolve("song.choplab")
        val bytes = ByteArrayOutputStream().also { out ->
            val writer = WavCodec.FloatWriter(out, 4096)
            writer.write(FloatArray(8192) { if (it % 2 == 0) .12f else -.03f }); writer.finish()
        }.toByteArray()
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 4096, "Synthetic stereo")
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by real {
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(wav), frames.toInt())
            override suspend fun chooseStems(frames: Long) = StemExportRequest(backend.files.register(zip), frames.toInt())
        })
        suspend fun ready() {
            backend.assets.write(asset, bytes)
            val p = Project(assets = frozenListOf(asset), tracks = frozenListOf(Track("bank", "BANK A", TrackKind.BANK), Track("voice", "Voice", TrackKind.VOCAL)),
                banks = (0..7).map { Bank(it, trackId = if (it == 0) "bank" else null) }.frozen(),
                pads = (0..127).map { if (it == 0) Pad(it, asset.hash, FrameRange(0, asset.frames)) else Pad(it) }.frozen(),
                clips = frozenListOf(Clip("a", "bank", asset.hash, FrameRange(0, asset.frames)), Clip("b", "voice", asset.hash, FrameRange(0, asset.frames))))
            assertTrue(backend.studio.dispatch(Action.New(p)).accepted)
            withTimeout(10_000) { while (!presenter.state.value.permits(ContinuousCapability.MIXER) || !presenter.state.value.permits(ContinuousCapability.RECORD_HITS)) delay(5) }
        }
        private var closed = false
        suspend fun shutdown() { if (!closed) { presenter.close(); real.close(); backend.shutdown(); scope.cancel(); closed = true } }
        suspend fun close() { shutdown(); root.toFile().deleteRecursively() }
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
                val fields = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-mixer-fields" }
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
