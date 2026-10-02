@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.ai.VocalPreviewPort
import com.choplab.core.ai.TtsResult
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.source.*
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.test.*

/** Actual host microphone/files/renderer/preview/Studio, with synthetic endpoints and real modal pointer input. */
class VocalTakeHostTest {
    @Test fun applyingCompKeepsItsWindowUntilTheOwnedEditFinishes() = runBlocking<Unit> {
        val applying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stopBlocked = false
        val f = Fixture { real -> object : ContinuousEditorPorts by real {
            override val vocalGuide = null
            override val vocalPractice = null
            override val vocalPitch = null
            override val vocalCoach = null
            override val sourcePreview get() = vocalTakes.preview
            override val onlineSource = idleOnlineHost {}
            override val vocalTakes = object : VocalTakePort by real.vocalTakes {
                override val preview = object : VocalPreviewPort by real.vocalTakes.preview {
                    override suspend fun stop(): TtsResult<Unit> {
                        if (stopBlocked) { applying.complete(Unit); release.await() }
                        return real.vocalTakes.preview.stop()
                    }
                }
                override suspend fun render(project: Project, draft: VocalCompDraft, name: String): Asset =
                    real.vocalTakes.render(project, draft, name).also { stopBlocked = true }
            }
        } }
        try {
            f.ready()
            assertEquals(VoiceTakes.Start.STARTED, f.backend.voice.start(1))
            until { f.backend.voice.recordedMillis >= 100 }
            val recording = assertNotNull(f.backend.voice.stop("Candidate"))
            val track = Track("voice", "VOICE", TrackKind.VOCAL)
            val take = Take("take", track.id, recording.asset.hash, FrameRange(0, recording.asset.frames), 0)
            assertTrue(f.backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(f.backend.studio.document.value.project, recording.asset, take, track))).accepted)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalTakes))
            val controller = assertNotNull(f.presenter.vocalTakes.value)
            assertTrue(controller.dispatch(VocalAction.WholeTake))
            val before = f.backend.studio.document.value
            val committing = async { controller.dispatch(VocalAction.Apply("Comp")) }
            withTimeout(5_000) { applying.await() }
            assertEquals(VocalPhase.APPLYING, controller.state.value.phase)
            for (action in listOf(ContinuousEditorAction.ImportOnline, ContinuousEditorAction.OpenStepPatterns,
                ContinuousEditorAction.Navigate(ContinuousStage.BEAT))) {
                assertFalse(withTimeout(1_000) { f.presenter.dispatch(action) })
                assertSame(controller, f.presenter.vocalTakes.value)
                assertNull(f.presenter.onlineSource.value)
                assertNull(f.presenter.stepPatterns.value)
                assertEquals(before, f.backend.studio.document.value)
            }
            release.complete(Unit)
            assertTrue(committing.await())
            assertEquals(before.revision + 1, f.backend.studio.document.value.revision)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before.project, f.backend.studio.document.value.project)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun onlineAndVocalWindowsTransferOwnershipInBothDirectionsWithoutEditing() = runBlocking<Unit> {
        var closes = 0
        val f = Fixture { real -> object : ContinuousEditorPorts by real {
            override val onlineSource = idleOnlineHost { closes++ }
        } }
        try {
            f.ready()
            val before = f.backend.studio.document.value
            for (action in listOf(ContinuousEditorAction.OpenVocalTakes, ContinuousEditorAction.OpenVocalPunch)) {
                assertTrue(f.presenter.dispatch(action))
                val takes = f.presenter.vocalTakes.value
                val punch = f.presenter.vocalPunch.value
                assertTrue(takes != null || punch != null)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.ImportOnline))
                assertNull(f.presenter.vocalTakes.value)
                assertNull(f.presenter.vocalPunch.value)
                takes?.let { assertEquals(VocalPhase.CLOSED, it.state.value.phase) }
                punch?.let { assertTrue(it.state.value.closed) }
                val online = assertNotNull(f.presenter.onlineSource.value)
                assertTrue(f.presenter.dispatch(action))
                assertNull(f.presenter.onlineSource.value)
                assertTrue(online.state.value.closed)
                assertEquals(before, f.backend.studio.document.value)
            }
            assertEquals(2, closes)
        } finally { f.close() }
    }

    @Test fun recordingsEnterTheLibraryThenLineChoicesPreviewApplyUndoExportAndReopenInBothLanguagesAndSizes() = runBlocking<Unit> {
        val localeBefore = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(Triple(1440,838,1f), Triple(390,844,2f))) {
            Locale.setDefault(locale)
            val f = Fixture()
            val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext) {
                val state by f.presenter.state.collectAsState()
                val takes by f.presenter.vocalTakes.collectAsState()
                ContinuousEditor(state, f.presenter::onAction, f.presenter::readout, vocalTakes = takes)
            }
            try {
                f.ready()
                scene.settle(); scene.pointer("ce-nav-BEAT")
                for (pass in 0..1) {
                    assertTrue(f.presenter.dispatch(ContinuousEditorAction.SeekSong(0)))
                    val before = f.backend.studio.document.value
                    scene.pointer("ce-record-voice")
                    until { f.backend.voice.recordedMillis >= 400 || f.backend.studio.document.value.revision != before.revision }
                    assertTrue(f.presenter.dispatch(ContinuousEditorAction.StopVoice))
                    val recorded = f.backend.studio.document.value
                    assertEquals(before.revision + 1, recorded.revision)
                    assertEquals(pass + 1, recorded.project.takes.size)
                    assertEquals(pass + 1, recorded.project.clips.size)
                    assertTrue(f.presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, f.backend.studio.document.value.project)
                    assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(recorded.project, f.backend.studio.document.value.project)
                }
                val studio = f.backend.studio
                val originalBytes = studio.document.value.project.assets.associateWith { f.backend.assets.read(it) }
                assertTrue(studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(
                    LyricLine("one", "First", 96, 288), LyricLine("two", "Second", 288, 480))))).accepted)
                val before = studio.document.value
                scene.pointer("ce-lyrics-open"); scene.pointer("ce-vocal-takes-open"); scene.awaitOwners(2)
                val editor = assertNotNull(f.presenter.vocalTakes.value)
                assertFalse(f.presenter.state.value.lyrics.open)
                assertEquals(2, editor.state.value.project.takes.size)
                scene.pointer("vocal-from-lyrics")
                val b = before.project.takes[1]
                scene.pointer("vocal-line-two"); scene.pointer("vocal-line-two-${b.id}"); scene.awaitOwners(2)
                before.project.clips.forEach { scene.pointer("vocal-replace-${it.id}") }
                assertEquals(before.project.takes.map { it.id }, editor.state.value.draft!!.segments.map { it.takeId })
                for (tag in listOf("vocal-preview-comp", "vocal-apply", "vocal-close")) scene.fullHit(tag, width, height)
                val energy = f.sink.leftEnergy
                scene.pointer("vocal-preview-comp")
                until { editor.state.value.previewing }
                until { f.sink.leftEnergy > energy }
                assertEquals(before, studio.document.value)
                scene.pointer("vocal-apply"); until { editor.state.value.applied }
                val applied = studio.document.value
                assertEquals(before.revision + 1, applied.revision)
                assertEquals(before.project.takes, applied.project.takes)
                assertEquals(1, applied.project.clips.size)
                assertEquals(listOf("one", "two"), applied.project.vocalComps.single().segments.map { it.lyricLineId })
                scene.pointer("vocal-close"); scene.awaitOwners(1)
                scene.pointer("ce-undo"); until { studio.document.value.canRedo }
                assertEquals(before.project, studio.document.value.project)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(applied.project, studio.document.value.project)
                scene.pointer("ce-nav-SAVE"); scene.pointer("ce-export"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val audio = Files.newInputStream(f.export).use(WavCodec::read)
                assertEquals(24, audio.info.bits); assertEquals(2, audio.info.channels)
                // Explicit line selections, without either unchecked original playing twice.
                assertEquals(.2f, audio.samples[4800 * 2], .00001f)
                assertEquals(.4f, audio.samples[9600 * 2], .00001f)
                assertTrue(audio.samples.indices.step(2).all { abs(audio.samples[it] - audio.samples[it+1]) < .000001f })
                scene.pointer("ce-save"); until { f.presenter.state.value.status == ContinuousStatus.SAVED }
                val restored = NextBackend.create(f.directory.resolve("restored"), sinkFactory = { error("No native audio") }, microphone = { null })
                try {
                    assertTrue(restored.openProject(f.archive).accepted); until { restored.studio.work.value.jobId == null }
                    assertEquals(applied.project, restored.studio.document.value.project)
                    originalBytes.forEach { (asset, bytes) -> assertContentEquals(bytes, restored.assets.read(asset)) }
                } finally { restored.shutdown() }
            } finally { scene.close(); f.close() }
        } } finally { Locale.setDefault(localeBefore) }
    }

    @Test fun closeAndRevisionChangeFenceALateRenderAndRecordingPermissionRefusesCompApply() = runBlocking<Unit> {
        for (ending in listOf(ContinuousEditorAction.CloseVocalTakes, ContinuousEditorAction.StopOriginal, ContinuousEditorAction.ImportOnline)) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val permission = CompletableDeferred<Unit>()
        val permissionEntered = CompletableDeferred<Unit>()
        val f = Fixture { real -> object : ContinuousEditorPorts by real {
            override val onlineSource = idleOnlineHost {}
            override val vocalTakes = object : VocalTakePort by real.vocalTakes {
                override suspend fun render(project: Project, draft: VocalCompDraft, name: String): Asset {
                    entered.complete(Unit); withContext(NonCancellable) { release.await() }
                    return real.vocalTakes.render(project, draft, name)
                }
            }
            override val recordingCue: RecordingCuePort? = null
            override suspend fun startVoice(maxSeconds: Int): VoiceStart { permissionEntered.complete(Unit); permission.await(); return VoiceStart.DENIED }
        } }
        try {
            f.ready()
            // A fixture recording uses the same real recorder/store, then a normal one-Undo retain edit.
            assertEquals(VoiceTakes.Start.STARTED, f.backend.voice.start(1))
            until { f.backend.voice.recordedMillis >= 350 }
            val recorded = assertNotNull(f.backend.voice.stop("Candidate"))
            val track = Track("voice", "VOICE", TrackKind.VOCAL)
            val take = Take("take", track.id, recorded.asset.hash, FrameRange(0, recorded.asset.frames), 0)
            assertTrue(f.backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(f.backend.studio.document.value.project, recorded.asset, take, track))).accepted)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalTakes))
            val editor = assertNotNull(f.presenter.vocalTakes.value)
            assertTrue(editor.dispatch(VocalAction.WholeTake))
            val preparing = async { editor.dispatch(VocalAction.PreviewTake) }
            entered.await()
            assertTrue(f.presenter.dispatch(ending))
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.CloseVocalTakes))
            val before = f.backend.studio.document.value
            release.complete(Unit); assertFalse(preparing.await())
            assertFalse(f.real.vocalTakes.preview.state.value.ownsSource)
            assertFalse(f.backend.engine.originalPlayback().playing)
            assertEquals(before, f.backend.studio.document.value)
            assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalTakes))
            val next = assertNotNull(f.presenter.vocalTakes.value)
            assertTrue(next.dispatch(VocalAction.WholeTake))
            assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.Rename("Changed"))).accepted)
            assertFalse(next.dispatch(VocalAction.Apply("Stale")))
            assertTrue(next.dispatch(VocalAction.Reload), "Reload must accept the current document")
            // The actor edit is committed before the host's work/availability collectors reach EDITABLE.
            // A real button stays disabled at that boundary; wait for the same published UI condition.
            until { next.state.value.editable && f.presenter.state.value.projectTitle == "Changed" &&
                f.backend.studio.work.value.let { it.jobId == null && it.preparationId == null } }
            assertTrue(next.dispatch(VocalAction.WholeTake), "Choose the take after the committed edit is available")
            val recording = async { f.presenter.dispatch(ContinuousEditorAction.RecordVoice) }
            permissionEntered.await()
            until { next.state.value.availability == VocalAvailability.RECORDING }
            assertFalse(withTimeout(1000) { next.dispatch(VocalAction.Apply("During permission")) })
            assertTrue(f.backend.studio.document.value.project.vocalComps.isEmpty())
            permission.complete(Unit); assertFalse(recording.await())
        } finally { release.complete(Unit); permission.complete(Unit); f.close() }
        }
    }

    private fun idleOnlineHost(closed: () -> Unit) = OnlineSourceHost { _, stop ->
        val port = object : OnlineSourcePort {
            override val state = MutableStateFlow(OnlineWorkerState())
            override fun search(query: String, catalog: OnlineCatalog) = false
            override fun inspect(id: String) = false
            override fun selectFormat(id: String) = false
            override fun save(id: String) = false
            override fun cancel() = Unit
            override fun stopAll() = stop()
            override fun close() = closed()
        }
        OnlineImportSession(port) { null }
    }

    private class Fixture(wrap: (DesktopEditorPorts)->ContinuousEditorPorts = { it }) {
        val directory = Files.createTempDirectory("vocal-host-")
        val sink = CountingTestSink()
        var number = 0
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink }, microphone = { ConstantMic(++number * .2f) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        val archive = directory.resolve("song.choplab")
        val export = directory.resolve("song.wav")
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by wrap(real) {
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(export), frames.toInt(), bits = 24)
        })
        suspend fun ready() = withTimeout(10000) { while (!presenter.state.value.permits(ContinuousCapability.RECORD_VOICE)) delay(5) }
        suspend fun close() { presenter.close(); real.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }
    private class ConstantMic(private val value: Float) : MicInput {
        override val sampleRate = 48_000
        private var started = 0L
        private var frames = 0L
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            if (started == 0L) started = System.nanoTime()
            frames += 480
            val deadline = started + frames * 1_000_000_000L / sampleRate
            while (System.nanoTime() < deadline && !stopped) LockSupport.parkNanos((deadline - System.nanoTime()).coerceAtLeast(1))
            if (stopped) return -1
            buffer.fill(value, 0, 480); return 480
        }
        override fun stop() { stopped = true }
        override fun close() { stopped = true }
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
        repeat(40) {
            if (nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == value }) {
                val fields = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "vocal-take-fields" }
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
