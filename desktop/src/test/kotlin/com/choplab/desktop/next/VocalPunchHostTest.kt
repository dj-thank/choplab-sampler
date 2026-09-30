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
import com.choplab.core.vocal.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/** Real shared recorder, clock, actor, comp renderer, WAV and archive reached through the production modal. */
class VocalPunchHostTest {
    @Test fun bothLanguagesAndSizesRecordTwoPassesAsOneUndoThenExplicitSpliceExportsAndReopens() = runBlocking<Unit> {
        val savedLocale = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(Triple(1440,838,1f), Triple(390,844,2f))) {
            Locale.setDefault(locale)
            val f = Fixture()
            val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext) {
                val state by f.presenter.state.collectAsState()
                val punch by f.presenter.vocalPunch.collectAsState()
                val takes by f.presenter.vocalTakes.collectAsState()
                ContinuousEditor(state, f.presenter::onAction, f.presenter::readout, vocalPunch = punch, vocalTakes = takes)
            }
            try {
                f.ready(); scene.settle(); scene.pointer("ce-nav-BEAT"); scene.pointer("ce-lyrics-open"); scene.pointer("ce-vocal-punch-open"); scene.awaitOwners(2)
                scene.setText("punch-start", "0.25"); scene.setText("punch-end", "0.75")
                repeat(3) { scene.pointer("punch-preroll") }; repeat(2) { scene.pointer("punch-countin") }
                scene.pointer("punch-passes")
                for (tag in listOf("punch-record", "punch-stop", "punch-close")) scene.fullHit(tag, width, height)
                val before = f.backend.studio.document.value
                val controller = assertNotNull(f.presenter.vocalPunch.value)
                scene.pointer("punch-record")
                until { controller.state.value.saved == 2 || controller.state.value.problem != null }
                assertNull(controller.state.value.problem)
                assertEquals(2, controller.state.value.saved)
                val captured = f.backend.studio.document.value
                assertEquals(before.revision + 1, captured.revision)
                assertEquals(before.project.clips, captured.project.clips)
                val passes = captured.project.takes.drop(1)
                assertEquals(2, passes.size); assertEquals(passes[0].assetHash, passes[1].assetHash)
                assertEquals(passes[0].range.end, passes[1].range.start)
                assertEquals(48_960, captured.project.asset(passes[0].assetHash).frames, "Two 0.51-second windows; pre-roll is not in the file")
                assertEquals(1, f.opens); assertEquals(1, f.mic.closes)
                val originals = captured.project.assets.associateWith { f.backend.assets.read(it) }
                scene.pointer("punch-close"); scene.awaitOwners(1)
                scene.pointer("ce-undo"); until { f.backend.studio.document.value.canRedo }
                assertEquals(before.project, f.backend.studio.document.value.project)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); until { f.backend.studio.document.value.project == captured.project }
                scene.pointer("ce-lyrics-open"); scene.pointer("ce-vocal-takes-open"); scene.awaitOwners(2)
                val takeEditor = assertNotNull(f.presenter.vocalTakes.value)
                scene.pointer("vocal-whole-take")
                scene.pointer("vocal-select-take"); scene.pointer("vocal-select-take-${passes.last().id}"); scene.awaitOwners(2)
                scene.pointer("vocal-splice-punch"); scene.pointer("vocal-replace-old-clip")
                assertEquals(listOf("old", passes.last().id, "old"), takeEditor.state.value.draft!!.segments.map { it.takeId })
                val beforePreview = f.backend.studio.document.value
                val energy = f.sink.leftEnergy
                scene.pointer("vocal-preview-comp"); until { takeEditor.state.value.previewing }; until { f.sink.leftEnergy > energy }
                assertEquals(beforePreview, f.backend.studio.document.value)
                scene.pointer("vocal-apply"); until { takeEditor.state.value.applied }
                val applied = f.backend.studio.document.value
                assertEquals(captured.revision + 3, applied.revision, "Undo/Redo each advance revision, Apply is one edit")
                assertEquals(captured.project.takes, applied.project.takes); assertEquals(1, applied.project.clips.size)
                scene.pointer("vocal-close"); scene.awaitOwners(1)
                scene.pointer("ce-undo"); until { f.backend.studio.document.value.canRedo }
                assertEquals(captured.project, f.backend.studio.document.value.project)
                assertTrue(f.presenter.dispatch(ContinuousEditorAction.Redo)); until { f.backend.studio.document.value.project == applied.project }
                scene.pointer("ce-nav-SAVE"); scene.pointer("ce-export"); until { f.presenter.state.value.status == ContinuousStatus.EXPORTED }
                val wav = Files.newInputStream(f.export).use(WavCodec::read)
                assertEquals(48_000, wav.info.frames); assertEquals(24, wav.info.bits)
                assertEquals(.1f, wav.samples[4800 * 2], .00001f); assertEquals(-.1f, wav.samples[4800 * 2 + 1], .00001f)
                assertEquals(.5f, wav.samples[24000 * 2], .00001f); assertEquals(.5f, wav.samples[24000 * 2 + 1], .00001f)
                assertEquals(.1f, wav.samples[42000 * 2], .00001f)
                scene.pointer("ce-save"); until { f.presenter.state.value.status == ContinuousStatus.SAVED }
                val restored = NextBackend.create(f.directory.resolve("restored"), sinkFactory = { error("No native output") }, microphone = { null })
                try {
                    assertTrue(restored.openProject(f.archive).accepted); until { restored.studio.work.value.jobId == null }
                    assertEquals(applied.project, restored.studio.document.value.project)
                    originals.forEach { (asset, bytes) -> assertContentEquals(bytes, restored.assets.read(asset)) }
                } finally { restored.shutdown() }
            } finally { scene.close(); f.close() }
        } } finally { Locale.setDefault(savedLocale) }
    }

    @Test fun permissionCancellationAndLateRevisionNeverStartAnUnownedRecordingOrAdoptOldResults() = runBlocking<Unit> {
        for (kind in listOf("cancel", "revision")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Boolean>()
            val f = Fixture { entered.complete(Unit); release.await() }
            try {
                f.ready(); assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPunch))
                val panel = assertNotNull(f.presenter.vocalPunch.value)
                panel.update { it.copy(startSeconds="0.1", endSeconds="0.8", countInBars=0, preRollBars=0) }
                val original = f.backend.studio.document.value
                val work = async { panel.record() }; entered.await()
                assertFalse(f.presenter.applyPreparedEdit(Intent.Rename("Cannot edit"), original.revision))
                assertFalse(f.presenter.dispatch(ContinuousEditorAction.RecordVoice))
                if (kind == "cancel") panel.stop() else assertTrue(f.backend.studio.dispatch(Action.Edit(Intent.Rename("New revision"))).accepted)
                release.complete(true); assertFalse(work.await())
                assertEquals(1, f.backend.studio.document.value.project.takes.size)
                assertFalse(f.backend.studio.transport.value.playing)
                assertEquals(0L, (f.backend.studio.selection.value.playbackTarget as? PlaybackTarget.Arrangement)?.minimumFrames ?: 0L)
                if (kind == "cancel") assertEquals(0, f.opens)
            } finally { release.complete(false); f.close() }
        }
    }

    @Test fun routeReplacementAndInputReroutingInvalidateManualAdjustmentWhileStopRetainsPartialAudio() = runBlocking<Unit> {
        for (kind in listOf("output", "input", "stop", "close")) {
            val f = Fixture()
            try {
                f.ready(); assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPunch))
                val panel = assertNotNull(f.presenter.vocalPunch.value)
                panel.update { it.copy(startSeconds="0.1", endSeconds="3", countInBars=0, preRollBars=0, manualMillis="4") }
                val before = f.backend.studio.document.value
                val run = async { panel.record() }
                until { f.backend.voice.recordedMillis >= 100 }
                val generation = f.backend.engine.outputRouteGeneration()
                val faults = f.backend.engine.status.value.faults
                when (kind) {
                    "output" -> { f.backend.engine.releaseOutput(); until { f.backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }; f.backend.engine.reattach() }
                    "input" -> f.mic.route.incrementAndGet()
                    "close" -> f.presenter.finishRecording()
                    else -> panel.stop()
                }
                assertTrue(run.await())
                val after = f.backend.studio.document.value
                assertEquals(before.revision + 1, after.revision); assertEquals(2, after.project.takes.size)
                assertEquals(before.project.clips, after.project.clips)
                assertEquals(if (kind == "stop") 192 else 0, after.project.takes.last().compensationFrames)
                assertEquals(if (kind == "stop") AlignmentStatus.MANUAL else AlignmentStatus.INVALIDATED, panel.progress.value.alignment.status)
                if (kind == "output") {
                    until { f.backend.engine.status.value.phase == DriverPhase.ATTACHED }
                    assertTrue(f.backend.engine.outputRouteGeneration() >= generation + 2)
                    assertEquals(faults, f.backend.engine.status.value.faults, "Normal release/reopen is still a new route")
                }
                assertEquals(1, f.mic.closes)
            } finally { f.close() }
        }
    }

    @Test fun closingDuringCountInStoresNothingAndNaturalCountInLeadsIntoTheExactWindow() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val f = Fixture()
            try {
                f.ready(); assertTrue(f.presenter.dispatch(ContinuousEditorAction.OpenVocalPunch))
                val panel = assertNotNull(f.presenter.vocalPunch.value)
                panel.update { it.copy(startSeconds="0.25", endSeconds="0.75", countInBars=1, preRollBars=1) }
                val before = f.backend.studio.document.value
                val run = async { panel.record() }
                until { f.backend.engine.snapshot().countInBeatsRemaining > 0 }
                assertEquals(0, f.backend.voice.recordedMillis)
                if (cancel) {
                    panel.requestClose(); assertFalse(run.await())
                    assertEquals(before.project, f.backend.studio.document.value.project)
                    assertEquals(before.revision, f.backend.studio.document.value.revision)
                } else {
                    val advanced = withTimeoutOrNull(10_000) {
                        while (f.backend.engine.snapshot().let { it.countInBeatsRemaining != 0 || it.sequenceFrame !in 1..8000 }) {
                            if (run.isCompleted) break
                            delay(5)
                        }
                        f.backend.engine.snapshot().let { it.countInBeatsRemaining == 0 && it.sequenceFrame in 1..8000 }
                    }
                    assertEquals(true, advanced, "Count-in did not enter pre-roll: ${panel.state.value}; ${panel.progress.value}; ${f.backend.engine.snapshot()}")
                    assertEquals(0, f.backend.voice.recordedMillis, "Pre-roll is heard but is not captured")
                    assertTrue(run.await())
                    val after = f.backend.studio.document.value
                    assertEquals(before.revision + 1, after.revision)
                    assertEquals(24_480, after.project.asset(after.project.takes.last().assetHash).frames)
                }
                assertEquals(1, f.mic.closes)
                assertEquals(0L, (f.backend.studio.selection.value.playbackTarget as? PlaybackTarget.Arrangement)?.minimumFrames ?: 0L)
            } finally { f.close() }
        }
    }

    private inner class Fixture(permission: suspend () -> Boolean = { true }) {
        val directory = Files.createTempDirectory("punch-host-")
        lateinit var sink: ClockedSink
        var opens = 0
        lateinit var mic: ConstantMic
        lateinit var backend: NextBackend
        init { backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { ClockedSink().also { sink = it } }, microphone = {
            opens++; ConstantMic { .3f + backend.voice.completedPasses * .2f }.also { mic = it }
        }) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        val capture = VocalPunchCapture(backend.studio, backend.engine, backend.voice, permission)
        val archive = directory.resolve("song.choplab")
        val export = directory.resolve("song.wav")
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by real {
            override val vocalPunch = capture
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(export), frames.toInt(), bits = 24)
        })
        suspend fun ready() {
            val file = directory.resolve("original.wav")
            Files.write(file, ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(96_000) { index -> if(index % 2 == 0) .1f else -.1f }) }.toByteArray())
            val asset = WavImportPort(backend.assets, resolve = { file }).import(Location("original"))
            val track = Track("voice", "Voice", TrackKind.VOCAL)
            val take = Take("old", track.id, asset.hash, FrameRange(0, asset.frames), 0)
            val clip = Clip("old-clip", track.id, asset.hash, take.range, timelineStartFrame=0)
            assertTrue(backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(Project(), asset, take, track, clip=clip))).accepted)
            until { presenter.state.value.permits(ContinuousCapability.VOCAL_PUNCH) && backend.engine.status.value.phase == DriverPhase.ATTACHED }
        }
        suspend fun close() { presenter.close(); real.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }
    /** Native output runs at its sample clock; per-block sleeps would invent cumulative route drift. */
    private class ClockedSink : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        @Volatile var leftEnergy = 0.0
        private var frames = 0L
        private var started = 0L
        @Volatile private var closed = false
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed && length % 8 == 0)
            if (started == 0L) started = System.nanoTime()
            var energy = leftEnergy
            for (at in offset until offset + length step 8) {
                val sample = Float.fromBits((bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8) or
                    ((bytes[at+2].toInt() and 255) shl 16) or (bytes[at+3].toInt() shl 24))
                check(sample.isFinite()); energy += sample.toDouble() * sample
            }
            leftEnergy = energy; frames += length / 8
            val deadline = started + frames * 1_000_000_000L / 48_000
            while (System.nanoTime() < deadline && !closed) LockSupport.parkNanos((deadline-System.nanoTime()).coerceAtLeast(1))
            return length
        }
        override fun close() { closed = true }
    }
    private class ConstantMic(private val value: () -> Float) : MicInput {
        override val sampleRate = 48_000
        override val bufferFrames = 480
        val route = AtomicLong()
        override val routeRevision get() = route.get()
        private var started = 0L
        private var frames = 0L
        @Volatile var closes = 0
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            if (started == 0L) started = System.nanoTime()
            frames += 480
            val deadline = started + frames * 1_000_000_000L / sampleRate
            while (System.nanoTime() < deadline && !stopped) LockSupport.parkNanos((deadline-System.nanoTime()).coerceAtLeast(1))
            if (stopped) return -1
            buffer.fill(value(), 0, 480); return 480
        }
        override fun stop() { stopped = true }
        override fun close() { closes++; stopped = true }
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
                val fields = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) in setOf("vocal-take-fields", "punch-fields") }
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
