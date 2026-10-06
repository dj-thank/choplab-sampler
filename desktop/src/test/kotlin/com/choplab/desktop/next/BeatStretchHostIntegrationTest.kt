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
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.stretch.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipFile
import kotlin.math.*
import kotlin.test.*

/** Actual BEAT controls, real worker/actor/SOURCE, PCM export and fresh archive/autosave; synthetic output only. */
class BeatStretchHostIntegrationTest {
    @Test fun padAndClipStretchThroughActualPointersUndoWavArchiveAndFreshResumeInBothLayouts() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("stretch-host-")
                val profile = directory.resolve("profile")
                val backend = NextBackend.create(profile, sinkFactory = ::pacedSink, microphone = { null })
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = DesktopEditorPorts(backend, parent = { null })
                val archive = directory.resolve("song.choplab"); val exported = directory.resolve("song.wav")
                val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by host {
                    override suspend fun chooseSave() = backend.files.register(archive)
                    override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(exported), frames.toInt(), bits = 24)
                })
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    val stretch by presenter.beatStretch.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, beatStretch = stretch)
                }
                var expected: Project? = null
                try {
                    until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
                    val rate = if (width == 1440) 44_100 else 96_000
                    val sourcePath = directory.resolve("source.wav")
                    val samples = FloatArray(rate * 4) { i -> (.15 * sin(2 * PI * 220 * (i / 2) / rate) * if (i % 2 == 0) 1.0 else -.5).toFloat() }
                    Files.newOutputStream(sourcePath).use { WavCodec.writeFloat(it, samples, sampleRate = rate) }
                    assertTrue(backend.importAudio(sourcePath).accepted)
                    until { backend.studio.work.value.jobId == null }
                    val source = backend.studio.document.value.project.assets.single()
                    val range = FrameRange(13, source.frames - 19)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, range, 0))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(Track("beat", "Beat", TrackKind.BANK)),
                        frozenListOf(Clip("clip", "beat", source.hash, range)), frozenListOf()))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(150_000)))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetSourcePitch(6.0))).accepted)
                    assertTrue(backend.audition.pitch(6f)); assertTrue(backend.audition.originalGain(.25f))
                    assertTrue(backend.audition.seek(source, 5000)); until { backend.audition.nativeFrame() == 5000L }
                    scene.settle(); scene.pointer("ce-nav-BEAT")
                    if (width == 1440) for (pad in 0..15) scene.fullHit("ce-pad-$pad", width, height)
                    val before = backend.studio.document.value
                    scene.pointer("ce-pad-details"); scene.pointer("ce-stretch-pad"); scene.awaitOwners(2)
                    val padEditor = requireNotNull(presenter.beatStretch.value)
                    scene.setText("ce-stretch-bpm", "NaN")
                    assertNotNull(scene.tag("ce-stretch-prepare").config.getOrNull(SemanticsProperties.Disabled))
                    scene.setText("ce-stretch-bpm", "120")
                    scene.pointer("ce-stretch-prepare")
                    until { padEditor.state.value.phase == StretchPhase.EDITING && padEditor.state.value.prepared }
                    scene.pointer("ce-stretch-original"); until { padEditor.state.value.audition == StretchAudition.ORIGINAL }
                    assertEquals(VocalPreviewOwner.STRETCH, host.beatStretch.preview.state.value.owner)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-stretch-processed"); until { padEditor.state.value.audition == StretchAudition.STRETCHED }
                    val preparedHash = host.beatStretch.preview.state.value.assetHash
                    assertEquals(before, backend.studio.document.value)
                    scene.evidence(locale, width, font, "audition")
                    scene.pointer("ce-stretch-stop")
                    until { !host.beatStretch.preview.state.value.ownsSource && !backend.engine.originalPlayback().playing }
                    assertEquals(.25f, backend.engine.originalPlayback().gain); assertEquals(5000L, backend.audition.nativeFrame())
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("ce-stretch-prepare"); until { padEditor.state.value.prepared && padEditor.state.value.phase == StretchPhase.EDITING }
                    scene.pointer("ce-stretch-apply"); until { padEditor.state.value.applied }
                    val afterPad = backend.studio.document.value.project
                    assertEquals(before.revision + 1, backend.studio.document.value.revision)
                    assertEquals(preparedHash, afterPad.pads[0].assetHash)
                    assertEquals(before.project.clips, afterPad.clips); assertEquals(before.project.source, afterPad.source)
                    scene.pointer("ce-stretch-close"); scene.awaitOwners(1)
                    scene.pointer("ce-undo"); until { backend.studio.document.value.project == before.project }
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(afterPad, backend.studio.document.value.project)
                    if (width < 900) scene.pointer("ce-pane-timeline")
                    scene.pointer("ce-clip-clip"); until { presenter.state.value.selectedClipId == "clip" }
                    scene.pointer("ce-stretch-clip"); scene.awaitOwners(2)
                    val clipEditor = requireNotNull(presenter.beatStretch.value)
                    assertEquals(StretchKind.CLIP, clipEditor.state.value.target.kind)
                    scene.setText("ce-stretch-bpm", "120"); scene.pointer("ce-stretch-prepare")
                    until { clipEditor.state.value.prepared && clipEditor.state.value.phase == StretchPhase.EDITING }
                    scene.pointer("ce-stretch-apply"); until { clipEditor.state.value.applied }
                    val applied = backend.studio.document.value.project
                    assertEquals(2, applied.beatStretches.size)
                    scene.reach("ce-stretch-applied"); scene.evidence(locale, width, font, "applied")
                    for (tag in listOf("ce-stretch-stop", "ce-stretch-close", "ce-stretch-cancel")) scene.fullHit(tag, width, height)
                    scene.pointer("ce-stretch-close"); scene.awaitOwners(1)
                    scene.pointer("ce-undo"); until { backend.studio.document.value.project == afterPad }
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(applied, backend.studio.document.value.project)
                    assertContentEquals(Files.readAllBytes(sourcePath), backend.assets.read(source))
                    scene.pointer("ce-nav-SAVE"); scene.pointer("ce-export")
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val wav = Files.newInputStream(exported).use(WavCodec::read)
                    val expectedFrames = stretchFrames(source, range, 120_000, 150_000)
                    assertEquals(expectedFrames, wav.info.frames); assertEquals(24, wav.info.bits)
                    assertEquals(48_000, wav.info.sampleRate); assertEquals(2, wav.info.channels)
                    assertTrue(abs(frequency(wav.samples, 6000, 36_000) - 220) < 2)
                    for (frame in 6000 until 12_000) assertTrue(abs(wav.samples[frame * 2 + 1] + wav.samples[frame * 2] * .5f) < 1e-6)
                    scene.pointer("ce-save"); until { presenter.state.value.status == ContinuousStatus.SAVED }
                    ZipFile(archive.toFile()).use { zip -> applied.assets.forEach { assertNotNull(zip.getEntry(it.entryName)) } }
                    val restored = NextBackend.create(directory.resolve("restored"), sinkFactory = { error("No native device") }, microphone = { null })
                    try {
                        assertTrue(restored.openProject(archive).accepted); until { restored.studio.work.value.jobId == null }
                        assertEquals(applied, restored.studio.document.value.project)
                        applied.assets.forEach { assertContentEquals(backend.assets.read(it), restored.assets.read(it)) }
                    } finally { restored.shutdown() }
                    backend.flushAutosave(); expected = applied
                } finally {
                    scene.close(); presenter.close(); host.close(); scope.cancel(); backend.shutdown()
                    if (expected == null) directory.toFile().deleteRecursively()
                }
                try {
                    val resumed = NextBackend.create(profile, sinkFactory = { error("No native device") }, microphone = { null })
                    try { until { resumed.studio.document.value.project == expected }; assertEquals(2, resumed.studio.document.value.project.beatStretches.size) }
                    finally { resumed.shutdown() }
                } finally { directory.toFile().deleteRecursively() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun realPresenterFencesLateAssetsOnCancelCloseSelectionSaveRecordingRevisionAndShutdown() = runBlocking<Unit> {
        for (case in listOf("cancel", "stop", "close", "selection", "save", "recording", "revision", "shutdown")) {
            val directory = Files.createTempDirectory("stretch-host-fence-")
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
                override suspend fun chooseSave(): Location? { saving.complete(Unit); finishSave.await(); return null }
                override val beatStretch = object : BeatStretchHost by host.beatStretch {
                    override suspend fun render(project: Project, draft: StretchDraft, progress: (Int, Int) -> Unit): Asset {
                        val result = host.beatStretch.render(project, draft, progress)
                        rendered.complete(Unit)
                        withContext(NonCancellable) { returnAsset.await() }
                        return result
                    }
                }
            })
            try {
                until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
                val input = directory.resolve("source.wav")
                Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(96_000) { i ->
                    (.13 * sin(2 * PI * 220 * (i / 2) / 48_000) * if (i % 2 == 0) 1.0 else -.5).toFloat()
                }) }
                assertTrue(backend.importAudio(input).accepted); until { backend.studio.work.value.jobId == null }
                val source = backend.studio.document.value.project.assets.single()
                assertTrue(backend.studio.dispatch(Action.Edit(Intent.AssignRange(source.hash, FrameRange(0, source.frames), 0))).accepted)
                assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(150_000)))).accepted)
                assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
                val target = StretchTarget(StretchKind.PAD, "0")
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenBeatStretch(target)))
                val editor = requireNotNull(presenter.beatStretch.value)
                // The real BPM field stays disabled until the combined host's
                // import/work availability reaches the controller. A direct
                // fixture dispatch must observe that same editable boundary.
                until { editor.state.value.editable }
                assertTrue(editor.dispatch(StretchAction.Bpm("120")), "$case: ${editor.state.value}")
                val before = backend.studio.document.value
                val pending = async { editor.dispatch(StretchAction.Prepare) }
                withTimeout(10_000) { rendered.await() }
                var saveJob: Deferred<Boolean>? = null
                when (case) {
                    "cancel" -> assertTrue(editor.dispatch(StretchAction.Cancel))
                    "stop" -> assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                    "close" -> assertTrue(presenter.dispatch(ContinuousEditorAction.CloseBeatStretch))
                    "selection" -> assertTrue(presenter.dispatch(ContinuousEditorAction.SelectPad(1)))
                    "save" -> { saveJob = async { presenter.dispatch(ContinuousEditorAction.SaveProject) }; withTimeout(5000) { saving.await() } }
                    "recording" -> {
                        assertTrue(presenter.dispatch(ContinuousEditorAction.RecordVoice)); until { presenter.state.value.recordingVoice }
                        assertFalse(presenter.state.value.permits(ContinuousCapability.BEAT_STRETCH))
                        assertFalse(presenter.dispatch(ContinuousEditorAction.OpenBeatStretch(target)))
                    }
                    "revision" -> assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetTempo(Tempo(103_000)))).accepted)
                    "shutdown" -> presenter.close()
                }
                returnAsset.complete(Unit); assertFalse(withTimeout(5000) { pending.await() }, case)
                finishSave.complete(Unit); saveJob?.await()
                if (case == "recording") assertTrue(presenter.dispatch(ContinuousEditorAction.StopAll))
                assertFalse(editor.state.value.prepared, case); assertFalse(editor.dispatch(StretchAction.Apply), case)
                assertEquals(if (case == "revision") before.revision + 1 else before.revision, backend.studio.document.value.revision, case)
                assertTrue(backend.studio.document.value.project.beatStretches.isEmpty(), case)
                assertEquals(source.hash, backend.studio.document.value.project.pads[0].assetHash, case)
                assertFalse(host.beatStretch.preview.state.value.ownsSource, case)
                assertContentEquals(Files.readAllBytes(input), backend.assets.read(source), case)
                if (case in listOf("close", "selection", "save", "recording", "shutdown")) assertNull(presenter.beatStretch.value)
                if (case == "revision") {
                    assertTrue(editor.dispatch(StretchAction.Reload)); assertEquals(backend.studio.document.value.revision, editor.state.value.revision)
                }
            } finally {
                returnAsset.complete(Unit); finishSave.complete(Unit)
                presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively()
            }
        }
    }

    @Test fun changedSongTempoLeavesBytesAloneUntilExplicitReprocessingFromOriginalAndUnityReturnsOriginalRange() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("stretch-reprocess-")
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = ::pacedSink, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend, parent = { null })
        val presenter = ContinuousEditorPresenter(backend.studio, scope, host)
        try {
            until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val input = directory.resolve("source.wav")
            Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(192_000) { i ->
                (.15 * sin(2 * PI * 220 * (i / 2) / 48_000) * if (i % 2 == 0) 1.0 else -.5).toFloat()
            }) }
            assertTrue(backend.importAudio(input).accepted); until { backend.studio.work.value.jobId == null }
            val source = backend.studio.document.value.project.assets.single()
            val range = FrameRange(13, source.frames - 19)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(Track("beat", "Beat", TrackKind.BANK)),
                frozenListOf(Clip("clip", "beat", source.hash, range)), frozenListOf()))).accepted)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetTempo(150)))
            val target = StretchTarget(StretchKind.CLIP, "clip")
            suspend fun prepare(): BeatStretchController {
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenBeatStretch(target)))
                val controller = requireNotNull(presenter.beatStretch.value)
                if (controller.state.value.sourceBpm.isEmpty()) assertTrue(controller.dispatch(StretchAction.Bpm("120")))
                else assertEquals(120_000, controller.state.value.milliBpm())
                assertTrue(controller.dispatch(StretchAction.Prepare))
                return controller
            }
            assertTrue(prepare().dispatch(StretchAction.Apply))
            val first = backend.studio.document.value.project
            val firstAsset = first.asset(first.clips.single().assetHash)
            val firstBytes = backend.assets.read(firstAsset)
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetTempo(180)))
            val changed = backend.studio.document.value
            assertEquals(first.clips, changed.project.clips); assertEquals(first.beatStretches, changed.project.beatStretches)
            assertContentEquals(firstBytes, backend.assets.read(firstAsset))
            val controller = prepare()
            assertEquals(changed, backend.studio.document.value)
            assertTrue(controller.dispatch(StretchAction.Apply))
            val second = backend.studio.document.value.project
            val recipe = second.beatStretches.single()
            assertEquals(source.hash, recipe.sourceAssetHash); assertEquals(range, recipe.sourceRange)
            assertEquals(180_000, recipe.targetMilliBpm)
            assertEquals(stretchFrames(source, range, 120_000, 180_000), second.clips.single().range.length)
            val direct = backend.stretchRenderer().render(changed.project, BeatStretchEdits.draft(changed, target, 120_000), "Same original")
            assertEquals(direct.hash, recipe.renderedAssetHash)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(changed.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(second, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetTempo(120)))
            val assetsBeforeUnity = backend.studio.document.value.project.assets
            assertTrue(prepare().dispatch(StretchAction.Apply))
            val unity = backend.studio.document.value.project
            assertEquals(source.hash, unity.clips.single().assetHash); assertEquals(range, unity.clips.single().range)
            assertTrue(unity.assets.all { it in assetsBeforeUnity }); assertEquals(frozenListOf(source), unity.assets)
            assertContentEquals(Files.readAllBytes(input), backend.assets.read(source))
            assertContentEquals(firstBytes, backend.assets.read(firstAsset))
        } finally { presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively() }
    }

    private fun ImageComposeScene.evidence(locale: Locale, width: Int, font: Float, phase: String) {
        val directory = java.io.File("build/reports/ui-evidence/stretch-host").apply { mkdirs() }
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
