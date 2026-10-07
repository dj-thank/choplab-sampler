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
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.vocal.*
import com.choplab.core.vocal.VocalPitchDraft
import com.choplab.engine.PitchCorrectionPhase
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.*
import kotlin.test.*

/** Actual VOCAL pointers, desktop ports, worker, SOURCE preview, actor, audio graph and archive; no native devices. */
class VocalPitchHostIntegrationTest {
    @Test fun normalVocalEntryAuditionsWithoutEditingThenAppliesOneUndoExportsAndRestoresInBothLanguages() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("pitch-host-")
                val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = ::pacedSink, microphone = { null })
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
                    val pitch by presenter.vocalPitch.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, vocalPitch = pitch)
                }
                try {
                    until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
                    val sourcePath = directory.resolve("source.wav")
                    val frames = 6 * 48_000
                    val input = FloatArray(frames * 2) { i ->
                        val left = (.13 * sin(2 * PI * (220 * 2.0.pow(32.0 / 1200)) * (i / 2) / 48_000)).toFloat()
                        if (i % 2 == 0) left else -left * .5f
                    }
                    Files.newOutputStream(sourcePath).use { WavCodec.writeFloat(it, input) }
                    assertTrue(backend.importAudio(sourcePath).accepted)
                    until { backend.studio.work.value.jobId == null }
                    val source = backend.studio.document.value.project.assets.single()
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(
                        frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
                        frozenListOf(Clip("voice-clip", "voice", source.hash, FrameRange(0, source.frames))),
                        frozenListOf(Take("raw", "voice", source.hash, FrameRange(0, source.frames), 0))))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetSourcePitch(6.0))).accepted)
                    assertTrue(backend.audition.pitch(6f)); assertTrue(backend.audition.originalGain(.25f))
                    assertTrue(backend.audition.seek(source, 5000))
                    until { backend.audition.nativeFrame() == 5000L }
                    until { presenter.state.value.permits(ContinuousCapability.VOCAL_PITCH) }
                    scene.settle()
                    scene.pointer("ce-nav-BEAT")
                    if (width == 1440) for (pad in 0..15) scene.fullHit("ce-pad-$pad", width, height)
                    val before = backend.studio.document.value
                    scene.openPitch(presenter)
                    val editor = requireNotNull(presenter.vocalPitch.value)
                    scene.setText("ce-pitch-retune", "NaN")
                    assertNotNull(scene.tag("ce-pitch-apply").config.getOrNull(SemanticsProperties.Disabled))
                    scene.setText("ce-pitch-retune", "0")
                    scene.setText("ce-pitch-vibrato", "0")
                    scene.pointer("ce-pitch-key-9")
                    scene.pointer("ce-pitch-scale-MAJOR")
                    scene.pointer("ce-pitch-prepare")
                    until { editor.state.value.phase == PitchEditorPhase.EDITING && editor.state.value.prepared }
                    assertNull(editor.state.value.problem)
                    scene.ready("ce-pitch-report")
                    val reportText = scene.tag("ce-pitch-report").config[SemanticsProperties.Text].joinToString { it.text }
                    assertTrue("98%" in reportText); assertFalse("%%" in reportText)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-pitch-preview-original")
                    until { editor.state.value.audition == PitchAudition.ORIGINAL }
                    assertEquals(source.hash, host.vocalPitch.preview.state.value.assetHash)
                    assertEquals(VocalPreviewOwner.PITCH, host.vocalPitch.preview.state.value.owner)
                    assertEquals(1f, backend.engine.originalPlayback().gain)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-pitch-preview-corrected")
                    val corrected = withTimeoutOrNull(10_000) {
                        while (editor.state.value.audition != PitchAudition.CORRECTED) delay(5)
                        true
                    } ?: false
                    assertTrue(corrected, "B audition ${locale.language}/${width}px/font$font: " +
                        "phase=${editor.state.value.phase}, audition=${editor.state.value.audition}, " +
                        "prepared=${editor.state.value.prepared}, problem=${editor.state.value.problem}, " +
                        "SOURCE=${host.vocalPitch.preview.state.value.phase}/${host.vocalPitch.preview.state.value.owner}, " +
                        "playing=${backend.engine.originalPlayback().playing}")
                    val correctionHash = requireNotNull(host.vocalPitch.preview.state.value.assetHash)
                    assertNotEquals(source.hash, correctionHash)
                    assertEquals(before, backend.studio.document.value)
                    for (tag in listOf("ce-pitch-stop", "ce-pitch-close")) scene.fullHit(tag, width, height)
                    scene.evidence(locale, width, font, "audition")
                    scene.pointer("ce-pitch-stop")
                    until { !host.vocalPitch.preview.state.value.ownsSource && !backend.engine.originalPlayback().playing }
                    assertEquals(.25f, backend.engine.originalPlayback().gain)
                    assertEquals(5000L, backend.audition.nativeFrame(), "Stop restores the paused SOURCE position")
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-pitch-close")
                    scene.awaitOwners(1)
                    assertNull(presenter.vocalPitch.value)
                    assertEquals(before, backend.studio.document.value)

                    scene.openPitch(presenter)
                    val appliedEditor = requireNotNull(presenter.vocalPitch.value)
                    scene.setText("ce-pitch-retune", "0")
                    scene.setText("ce-pitch-vibrato", "0")
                    scene.pointer("ce-pitch-prepare")
                    until { appliedEditor.state.value.phase == PitchEditorPhase.EDITING && appliedEditor.state.value.prepared }
                    scene.pointer("ce-pitch-apply")
                    until { appliedEditor.state.value.applied && appliedEditor.state.value.phase == PitchEditorPhase.EDITING }
                    val applied = backend.studio.document.value
                    assertEquals(before.revision + 1, applied.revision)
                    assertEquals(source.hash, applied.project.pitchCorrections.single().sourceAssetHash)
                    assertNotEquals(source.hash, applied.project.clips.single().assetHash)
                    assertEquals(before.project.source, applied.project.source)
                    assertEquals(before.project.banks, applied.project.banks)
                    assertEquals(before.project.takes, applied.project.takes)
                    assertContentEquals(Files.readAllBytes(sourcePath), backend.assets.read(source))
                    scene.reach("ce-pitch-applied")
                    scene.evidence(locale, width, font, "applied")
                    for (tag in listOf("ce-pitch-stop", "ce-pitch-close")) scene.fullHit(tag, width, height)
                    scene.pointer("ce-pitch-close")
                    scene.awaitOwners(1)
                    scene.pointer("ce-undo")
                    until { backend.studio.document.value.canRedo }
                    assertEquals(before.project, backend.studio.document.value.project, "Exactly one Undo restores the whole original song")
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                    assertEquals(applied.project, backend.studio.document.value.project)

                    scene.pointer("ce-nav-SAVE")
                    scene.pointer("ce-export")
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val wav = Files.newInputStream(exported).use(WavCodec::read)
                    assertEquals(2, wav.info.channels); assertEquals(48_000, wav.info.sampleRate); assertEquals(24, wav.info.bits)
                    assertEquals(frames.toLong(), wav.info.frames)
                    assertTrue(abs(frequency(wav.samples, 6000, 36_000) - 220.0) < .1)
                    for (frame in 6000 until 12_000) assertTrue(abs(wav.samples[frame * 2 + 1] + wav.samples[frame * 2] * .5f) < 1e-6)
                    scene.pointer("ce-save")
                    until { presenter.state.value.status == ContinuousStatus.SAVED }
                    ZipFile(archive.toFile()).use { zip -> applied.project.assets.forEach { assertNotNull(zip.getEntry(it.entryName)) } }
                    val restored = NextBackend.create(directory.resolve("restored"), sinkFactory = { error("No native device") }, microphone = { null })
                    try {
                        assertTrue(restored.openProject(archive).accepted)
                        until { restored.studio.work.value.jobId == null }
                        assertEquals(applied.project, restored.studio.document.value.project)
                        assertContentEquals(Files.readAllBytes(sourcePath), restored.assets.read(source))
                        assertContentEquals(backend.assets.read(applied.project.asset(applied.project.clips.single().assetHash)),
                            restored.assets.read(applied.project.asset(applied.project.clips.single().assetHash)))
                    } finally { restored.shutdown() }
                } finally {
                    scene.close(); presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively()
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun realPresenterFencesLateAssetsOnCancelCloseSaveRecordingRevisionAndHostShutdown() = runBlocking<Unit> {
        for (case in listOf("cancel", "close", "analysis", "stopAll", "stopSource", "save", "recording", "revision", "shutdown")) {
            val directory = Files.createTempDirectory("pitch-host-fence-")
            val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = ::pacedSink, microphone = { null })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = DesktopEditorPorts(backend, parent = { null })
            val rendered = CompletableDeferred<Unit>(); val returnAsset = CompletableDeferred<Unit>()
            val saving = CompletableDeferred<Unit>(); val finishSave = CompletableDeferred<Unit>()
            val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by host {
                override val recordingCue: RecordingCuePort? = null
                override val voiceAvailable = true
                override suspend fun startVoice(maxSeconds: Int) = VoiceStart.STARTED
                override fun cueVoice() = Unit
                override suspend fun stopVoice(name: String): VoiceTake? = null
                override suspend fun discardVoice() = Unit
                override suspend fun chooseSave(): Location? {
                    saving.complete(Unit); finishSave.await(); return null
                }
                override val vocalPitch = object : VocalPitchHost by host.vocalPitch {
                    override suspend fun render(project: Project, draft: VocalPitchDraft,
                        progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch {
                        val result = host.vocalPitch.render(project, draft, progress)
                        rendered.complete(Unit)
                        // A completed platform/worker result may be delivered after cancellation.
                        withContext(NonCancellable) { returnAsset.await() }
                        return result
                    }
                }
            })
            try {
                until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
                val input = directory.resolve("source.wav")
                Files.newOutputStream(input).use { out -> WavCodec.writeFloat(out, FloatArray(96_000) { i ->
                    (.13 * sin(2 * PI * 224.1 * (i / 2) / 48_000) * if (i % 2 == 0) 1.0 else -.5).toFloat()
                }) }
                assertTrue(backend.importAudio(input).accepted)
                until { backend.studio.work.value.jobId == null }
                val source = backend.studio.document.value.project.assets.single()
                assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(
                    frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
                    frozenListOf(Clip("voice-clip", "voice", source.hash, FrameRange(0, source.frames))), frozenListOf()))).accepted)
                assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
                val editor = requireNotNull(presenter.vocalPitch.value)
                // Import completion precedes the combined host availability reaching the editor.
                // Match the real pitch controls' editable boundary before sending the fixture's Apply.
                until { editor.state.value.editable }
                val before = backend.studio.document.value
                val beforeApply = editor.state.value
                val pending = async { editor.dispatch(PitchAction.Apply) }
                withTimeout(10_000) {
                    select<Unit> {
                        rendered.onAwait { }
                        pending.onAwait { result -> fail("$case: Apply completed ($result) before render: " +
                            "before=${beforeApply.phase}/${beforeApply.availability}/${beforeApply.problem}, " +
                            "after=${editor.state.value.phase}/${editor.state.value.availability}/${editor.state.value.problem}") }
                    }
                }
                var saveJob: Deferred<Boolean>? = null
                when (case) {
                    "cancel" -> assertTrue(editor.dispatch(PitchAction.Cancel))
                    "close" -> assertTrue(presenter.dispatch(ContinuousEditorAction.CloseVocalPitch))
                    "analysis" -> {
                        assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
                        assertNull(presenter.vocalPitch.value)
                        assertNotNull(presenter.sourceAnalysis.value)
                    }
                    "stopAll" -> assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                    "stopSource" -> assertTrue(presenter.dispatch(ContinuousEditorAction.StopOriginal))
                    "save" -> {
                        saveJob = async { presenter.dispatch(ContinuousEditorAction.SaveProject) }
                        withTimeout(5000) { saving.await() }
                        // The modal chooser has not started Studio I/O yet; it still closes/fences the old editor.
                        assertNull(presenter.vocalPitch.value)
                    }
                    "recording" -> {
                        assertTrue(presenter.dispatch(ContinuousEditorAction.RecordVoice))
                        until { presenter.state.value.recordingVoice }
                        assertFalse(presenter.state.value.permits(ContinuousCapability.VOCAL_PITCH))
                        assertFalse(presenter.dispatch(ContinuousEditorAction.OpenVocalPitch))
                        assertNull(presenter.vocalPitch.value)
                    }
                    "revision" -> assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(103_000)))).accepted)
                    "shutdown" -> presenter.close()
                }
                returnAsset.complete(Unit)
                assertFalse(withTimeout(5000) { pending.await() }, case)
                finishSave.complete(Unit); saveJob?.await()
                if (case == "recording") assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                assertEquals(if (case == "revision") before.revision + 1 else before.revision, backend.studio.document.value.revision, case)
                assertTrue(backend.studio.document.value.project.pitchCorrections.isEmpty(), case)
                assertEquals(source.hash, backend.studio.document.value.project.clips.single().assetHash, case)
                assertFalse(host.vocalPitch.preview.state.value.ownsSource, case)
                assertContentEquals(Files.readAllBytes(input), backend.assets.read(source), case)
                if (case == "revision") {
                    assertFalse(editor.dispatch(PitchAction.Apply))
                    assertTrue(editor.dispatch(PitchAction.Reload))
                    assertEquals(backend.studio.document.value.revision, editor.state.value.revision)
                }
            } finally {
                returnAsset.complete(Unit); finishSave.complete(Unit)
                presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively()
            }
        }
    }

    private suspend fun ImageComposeScene.openPitch(presenter: ContinuousEditorPresenter) {
        pointer("ce-lyrics-open"); awaitOwners(2)
        pointer("ce-pitch-open")
        until { presenter.vocalPitch.value != null }
        // Wait for the real replacement dialog, never send a pointer to a closing lyrics window.
        ready("ce-pitch-close"); awaitOwners(2)
    }
    private fun ImageComposeScene.evidence(locale: Locale, width: Int, font: Float, phase: String) {
        val directory = java.io.File("build/reports/ui-evidence/pitch-host").apply { mkdirs() }
        render(System.nanoTime()).use { image -> image.encodeToData()!!.use {
            directory.resolve("${locale.language}-${width}-font${(font * 100).toInt()}-$phase.png").writeBytes(it.bytes)
        } }
    }
    private fun pacedSink() = object : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
            return length
        }
        override fun close() = Unit
    }
    private fun frequency(samples: FloatArray, start: Int, count: Int): Double {
        val crossing = (start + 1 until start + count).filter { samples[(it - 1) * 2] <= 0 && samples[it * 2] > 0 }.map {
            val a = samples[(it - 1) * 2]; val b = samples[it * 2]
            it - 1.0 - a / (b - a)
        }
        return (crossing.size - 1) * 48_000.0 / (crossing.last() - crossing.first())
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
        repeat(3) {
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
