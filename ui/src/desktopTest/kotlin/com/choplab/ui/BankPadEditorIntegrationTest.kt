@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

/** Normal Compose screen -> Presenter -> Studio -> production archive/asset store, with an acknowledged fake output. */
class BankPadEditorIntegrationTest {
    private val output = File(System.getProperty("choplab.ui.evidenceDir")).resolve("bank-pad-integration").apply { mkdirs() }

    @Test fun ordinaryBeatEditsCancelApplyUndoAndReopenThroughTheProductionArchiveAtWideAndFontTwo() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPANESE)
        try {
            for ((width, height, font) in listOf(Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                val directory = Files.createTempDirectory("bank-pad-ui-test-")
                val archive = directory.resolve("music.choplab")
                val h = Harness(directory.resolve("first"), archive)
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by h.presenter.state.collectAsState()
                    ContinuousEditor(state, h.presenter::onAction, h.presenter::readout)
                }
                try {
                    scene.settle()
                    scene.click("ce-nav-BEAT")
                    h.until { it.stage == ContinuousStage.BEAT }
                    scene.settle()
                    if (width == 1440) {
                        val scroll = scene.tag("ce-pads-pane").config[SemanticsProperties.VerticalScrollAxisRange]
                        assertEquals(0f, scroll.value(), "Initial wide BEAT has not been scrolled")
                        for (id in 0..15) scene.fullHit("ce-pad-$id", width, height)
                    }
                    scene.fullHit("ce-stop-all", width, height)
                    scene.fullHit("ce-song-stop", width, height)
                    scene.capture("beat-${width}x$height-initial.png")
                    val before = h.studio.document.value.project
                    scene.click("ce-pad-details")
                    scene.click("ce-bank-edit")
                    scene.setText("name", "取り消す名前")
                    assertEquals(before, h.studio.document.value.project)
                    scene.fullHit("ce-bank-pad-cancel", width, height)
                    scene.fullHit("ce-bank-pad-stop", width, height)
                    val stops = h.engine.commands.count { it is EngineCommand.Stop }
                    scene.click("ce-bank-pad-stop")
                    assertEquals(stops + 1, h.engine.commands.count { it is EngineCommand.Stop })
                    scene.click("ce-bank-pad-cancel")
                    scene.awaitBankPadDialogClosed()
                    assertEquals(before, h.studio.document.value.project)
                    assertFalse(h.studio.document.value.canUndo)

                    scene.click("ce-bank-edit")
                    scene.setText("name", "低音")
                    scene.setText("color", "#FABA20")
                    scene.setText("role", "リズム")
                    scene.click("ce-bank-pad-apply")
                    h.until { it.bankPadEditor.draft == null && it.banks[0].name == "低音" }
                    scene.awaitBankPadDialogClosed()
                    assertEquals(Bank(0, "低音", 0xfaba20, "リズム"), h.studio.document.value.project.banks[0])
                    assertEquals(0xfaba20, h.presenter.state.value.banks[0].color)
                    assertTrue(scene.description("ce-bank-0").contains("低音"))
                    assertTrue(scene.description("ce-bank-0").contains("リズム"))
                    assertTrue(scene.description("ce-pad-0").contains("低音"))
                    assertTrue(scene.description("ce-pad-0").contains("リズム"))
                    scene.click("ce-undo")
                    h.until { it.banks[0].name == before.banks[0].name }
                    scene.settle()
                    assertEquals(before, h.studio.document.value.project, "All BANK metadata is one Undo")
                    assertFalse(h.studio.document.value.canUndo)
                    assertFalse(scene.description("ce-pad-0").contains("リズム"))
                    assertTrue(h.presenter.dispatch(ContinuousEditorAction.Redo))
                    h.until { it.banks[0].name == "低音" }

                    scene.click("ce-pad-sound-edit")
                    scene.setText("pan", "-50")
                    scene.click("ce-bank-pad-cancel")
                    scene.awaitBankPadDialogClosed()
                    assertEquals(before.pads, h.studio.document.value.project.pads)
                    scene.click("ce-pad-sound-edit")
                    scene.setText("pan", "-50")
                    scene.setText("attack", "20")
                    scene.setText("decay", "80")
                    scene.setText("sustain", "35")
                    scene.setText("release", "45")
                    scene.fullHit("ce-bank-pad-apply", width, height)
                    scene.fullHit("ce-bank-pad-stop", width, height)
                    scene.capture("pad-${width}x$height-ready.png")
                    scene.click("ce-bank-pad-apply")
                    h.until { it.bankPadEditor.draft == null }
                    scene.awaitBankPadDialogClosed()
                    val changed = h.studio.document.value.project
                    val expected = before.pads[0].copy(pan = -.5f, attackFrames = 960, decayFrames = 3840, sustainLevel = .35f, releaseFrames = 2160)
                    assertEquals(expected, changed.pads[0])
                    assertEquals(before.pads.drop(1), changed.pads.drop(1))
                    assertEquals(before.source, changed.source)
                    assertEquals(before.clips, changed.clips)
                    scene.click("ce-undo")
                    h.until { it.canRedo }
                    assertEquals(before.pads, h.studio.document.value.project.pads, "All five sound parameters are one Undo")
                    assertEquals(changed.banks, h.studio.document.value.project.banks)
                    assertTrue(h.presenter.dispatch(ContinuousEditorAction.Redo))
                    assertEquals(changed, h.studio.document.value.project)

                    scene.click("ce-nav-SAVE")
                    scene.click("ce-save")
                    h.until { it.status == ContinuousStatus.SAVED }
                    assertEquals(h.studio.document.value.revision, h.studio.document.value.savedRevision)
                    assertTrue(Files.size(archive) > 0)
                    val fresh = Harness(directory.resolve("fresh"), archive, restoring = true)
                    val reopenedScene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                        val state by fresh.presenter.state.collectAsState()
                        ContinuousEditor(state, fresh.presenter::onAction, fresh.presenter::readout)
                    }
                    try {
                        assertEquals(0, fresh.store.storedBytes())
                        reopenedScene.settle()
                        reopenedScene.click("ce-open")
                        fresh.until { it.banks[0].name == "低音" && it.bankPadBlocked == null }
                        assertEquals(changed, fresh.studio.document.value.project)
                        assertContentEquals(h.bytes, fresh.store.read(changed.assets.single()))
                        assertFalse(fresh.studio.document.value.canUndo)
                        reopenedScene.click("ce-nav-BEAT")
                        reopenedScene.click("ce-pad-details")
                        reopenedScene.click("ce-pad-sound-edit")
                        fresh.until { it.bankPadEditor.draft != null }
                        val draft = fresh.presenter.state.value.bankPadEditor.draft as BankPadDraft.PadSound
                        assertEquals("-50", draft.pan); assertEquals("20", draft.attack); assertEquals("80", draft.decay)
                        assertEquals("35", draft.sustain); assertEquals("45", draft.release)
                        // The restored dialog has just entered; capture its settled surface, not the fade-in frame.
                        repeat(3) { reopenedScene.settle() }
                        reopenedScene.capture("pad-${width}x$height-reopened.png")
                        reopenedScene.click("ce-bank-pad-cancel")
                    } finally { reopenedScene.close(); fresh.close() }
                } finally { scene.close(); h.close(); directory.toFile().deleteRecursively() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun presenterRejectsConfirmationDuringSourceAndPadRecordingBusyWorkOrAChangedSelectionAndStillAllowsCancel() = runBlocking {
        val directory = Files.createTempDirectory("bank-pad-guards-test-")
        val h = Harness(directory.resolve("assets"), directory.resolve("music.choplab"))
        suspend fun edit(action: BankPadEditAction) = h.presenter.dispatch(ContinuousEditorAction.BankPadEdit(action))
        try {
            val before = h.studio.document.value.project
            for (source in listOf(true, false)) {
                assertTrue(edit(BankPadEditAction.OpenBank))
                assertTrue(edit(BankPadEditAction.Change(BankPadEditField.NAME, "Refused")))
                assertTrue(h.presenter.dispatch(if (source) ContinuousEditorAction.RecordSource else ContinuousEditorAction.RecordHits))
                h.until { it.bankPadBlocked == BankPadEditProblem.RECORDING }
                assertFalse(edit(BankPadEditAction.Apply))
                assertEquals(before, h.studio.document.value.project)
                assertTrue(edit(BankPadEditAction.Cancel))
                h.until { it.bankPadEditor.draft == null }
                assertTrue(h.presenter.dispatch(if (source) ContinuousEditorAction.StopSourceRecording else ContinuousEditorAction.StopHits))
                h.until { it.bankPadBlocked == null }
            }
            assertTrue(edit(BankPadEditAction.OpenBank))
            assertTrue(edit(BankPadEditAction.Change(BankPadEditField.NAME, "Refused")))
            h.saveGate = CompletableDeferred()
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SaveProject))
            h.until { it.bankPadBlocked == BankPadEditProblem.BUSY }
            assertFalse(edit(BankPadEditAction.Apply))
            assertTrue(edit(BankPadEditAction.Cancel))
            h.saveGate!!.complete(Unit)
            h.until { it.bankPadBlocked == null }
            assertEquals(before, h.studio.document.value.project)
            assertTrue(edit(BankPadEditAction.OpenPad))
            assertTrue(edit(BankPadEditAction.Change(BankPadEditField.PAN, "50")))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SelectPad(1)))
            assertFalse(edit(BankPadEditAction.Apply))
            h.until { it.bankPadEditor.problem == BankPadEditProblem.STALE }
            assertTrue(edit(BankPadEditAction.Cancel))
            assertEquals(before, h.studio.document.value.project)
            assertFalse(h.studio.document.value.canUndo)
        } finally { h.saveGate?.complete(Unit); h.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun completedVoiceTakeMakesBankPadEditingAvailableAgain() = runBlocking {
        val directory = Files.createTempDirectory("bank-pad-recording-end-test-")
        val h = Harness(directory.resolve("assets"), directory.resolve("music.choplab"))
        try {
            h.voiceTake = VoiceTake(h.asset, 0)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            h.until { it.bankPadBlocked == BankPadEditProblem.RECORDING }
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.StopVoice))
            h.until { !it.recordingVoice && it.bankPadBlocked == null }
            assertTrue(h.studio.document.value.canUndo)
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.BankPadEdit(BankPadEditAction.OpenBank)))
            h.until { it.bankPadEditor.draft != null }
        } finally { h.close(); directory.toFile().deleteRecursively() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = requireNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }) { value }
    private fun ImageComposeScene.description(value: String) = tag(value).config[SemanticsProperties.ContentDescription].joinToString()
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private fun ImageComposeScene.fullHit(value: String, width: Int, height: Int) {
        val node = tag(value); val rect = node.boundsInWindow
        assertTrue(rect.width >= node.size.width - 1 && rect.height >= node.size.height - 1, "$value clipped: $rect / ${node.size}")
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height, "$value outside window: $rect")
        assertTrue(rect.width >= 48 && rect.height >= 48, "$value smaller than 48 dp: $rect")
    }
    private suspend fun ImageComposeScene.reach(value: String) {
        fun SemanticsNode.contains(value: String): Boolean = config.getOrNull(SemanticsProperties.TestTag) == value || children.any { it.contains(value) }
        repeat(3) {
            for (ancestor in nodes().filter { it.contains(value) && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }) {
                val target = tag(value); val rect = ancestor.boundsInRoot
                if (rect.width <= 0 || rect.height <= 0) continue
                val x = target.positionInRoot.x; val y = target.positionInRoot.y
                val horizontal = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                val vertical = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val dx = if (horizontal != null && (x < rect.left || x + target.size.width > rect.right)) x - rect.left else 0f
                val dy = if (vertical != null && (y < rect.top || y + target.size.height > rect.bottom)) y - rect.top else 0f
                if (dx != 0f || dy != 0f) {
                    requireNotNull(ancestor.config.getOrNull(SemanticsActions.ScrollBy)?.action)(dx, dy)
                    var previous: Pair<Float?, Float?>? = null
                    for (frame in 0..12) {
                        settle(); val position = horizontal?.value?.invoke() to vertical?.value?.invoke()
                        if (position == previous) break; previous = position
                    }
                }
            }
        }
        val node = tag(value)
        assertTrue(node.boundsInWindow.width >= node.size.width - 1 && node.boundsInWindow.height >= node.size.height - 1, "$value not reachable")
    }
    private suspend fun ImageComposeScene.click(value: String) {
        reach(value)
        val center = tag(value).boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private suspend fun ImageComposeScene.setText(field: String, text: String) {
        val tag = "ce-bank-pad-$field"
        reach(tag)
        assertTrue(requireNotNull(tag(tag).config.getOrNull(SemanticsActions.SetText)?.action)(AnnotatedString(text)))
        settle()
        assertEquals(text, tag(tag).config[SemanticsProperties.EditableText].text)
    }
    private fun ImageComposeScene.capture(name: String) = render(System.nanoTime()).use { image ->
        requireNotNull(image.encodeToData()).use { File(output, name).writeBytes(it.bytes) }
    }

    private class Harness(directory: Path, archive: Path, restoring: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(96_000) { i -> if (i % 2 == 0) .1f else -.2f }) }.toByteArray()
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 48_000, "Synthetic")
        val store = FileAssetStore(directory)
        private val pcm = WavPcmPort(store)
        val engine = Engine(ProgramCompiler(pcm))
        var saveGate: CompletableDeferred<Unit>? = null
        var voiceTake: VoiceTake? = null
        private val fileProjects = FileProjectPort(store, { archive })
        private val project = if (restoring) Project() else Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, asset.frames)),
            pads = (0..127).map { if (it < 16) Pad(it, asset.hash, FrameRange(0, asset.frames), "Sound $it", gain = .7f, tone = .6f) else Pad(it) }.frozen(),
            tracks = frozenListOf(Track("song", "Song", TrackKind.BANK)),
            clips = frozenListOf(Clip("clip", "song", asset.hash, FrameRange(0, asset.frames))))
        init { if (!restoring) store.publish(asset, bytes.inputStream()) }
        val studio = Studio(scope, Services(store, object : ImportPort { override suspend fun import(location: Location) = asset },
            object : ProjectPort {
                override suspend fun save(project: Project, revision: Long, location: Location) { saveGate?.await(); fileProjects.save(project, revision, location) }
                override suspend fun open(location: Location): Project = fileProjects.open(location)
            }, WavExportPort(ProgramCompiler(pcm), { archive }), engine), project)
        val presenter = ContinuousEditorPresenter(studio, scope, object : ContinuousEditorPorts {
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen() = Location("saved")
            override suspend fun chooseSave() = Location("saved")
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset) = List(64) { .5f }
            override fun readout() = ContinuousEditorReadout(songFrame = engine.transport.sequenceFrame)
            override suspend fun setSongMonitorGain(gain: Float) = true
            override val voiceAvailable = true
            override val originalAvailable = true
            override suspend fun startVoice(maxSeconds: Int) = VoiceStart.STARTED
            override suspend fun stopVoice(name: String): VoiceTake? = voiceTake
            override suspend fun stopOriginal() = true
        })
        suspend fun until(predicate: (ContinuousEditorState) -> Boolean) = withTimeout(10_000) {
            while (!predicate(presenter.state.value)) delay(10)
        }
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel(); pcm.close() }
    }

    private class Engine(private val compiler: ProgramCompiler) : EnginePort {
        val commands = mutableListOf<EngineCommand>()
        var transport = TransportState(outputAttached = true)
        override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram = compiler.compile(project, patternId, revision)
        override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram = compiler.compile(project, target, revision)
        override suspend fun apply(command: EngineCommand): Boolean {
            commands += command
            transport = transport.copy(frame = transport.frame + 1,
                playing = when (command) { is EngineCommand.StartSequence, is EngineCommand.Resume -> true; is EngineCommand.Stop, is EngineCommand.Pause -> false; else -> transport.playing },
                programRevision = (command as? EngineCommand.SwapProgram)?.program?.revision ?: transport.programRevision)
            return true
        }
        override fun snapshot() = transport
    }
}
