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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.sin
import kotlin.test.*

/** Real VOCAL entry, shared SOURCE loop, production render/export/archive. Synthetic endpoint, no devices. */
class VocalPracticeHostTest {
    private class Harness {
        val directory = Files.createTempDirectory("practice-host-")
        val lost = AtomicBoolean()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(!lost.get())
                LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000 / 48_000)
                return length
            }
            override fun close() = Unit
        } }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend, parent = { null })
        val archive = directory.resolve("song.choplab")
        val exported = directory.resolve("song.wav")
        val source = directory.resolve("original.wav")
        val ports = object : ContinuousEditorPorts by host {
            override suspend fun chooseSave() = backend.files.register(archive)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(exported), frames.toInt(), bits=24, tailMode=ExportTailMode.EXACT)
        }
        suspend fun prepare() {
            val samples = FloatArray(96_000 * 2) { i -> (sin(i / 2 * 2 * Math.PI * 440 / 48_000) * if (i % 2 == 0) .1 else -.037).toFloat() }
            Files.newOutputStream(source).use { WavCodec.writeFloat(it, samples, 48_000, 2) }
            assertTrue(backend.importAudio(source).accepted)
            withTimeout(10_000) { while (backend.studio.work.value.jobId != null || backend.engine.status.value.phase != DriverPhase.ATTACHED) delay(5) }
            val hash = backend.studio.document.value.project.source!!.assetHash
            val track = Track("voice", "Voice", TrackKind.VOCAL)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(track), frozenListOf(
                Clip("voice", track.id, hash, FrameRange(0,96_000), timelineStartFrame=0)), frozenListOf()))).accepted)
            assertTrue(backend.audition.seek(backend.studio.document.value.project.asset(hash),5_000))
            assertTrue(backend.audition.originalGain(.25f))
        }
        suspend fun close() { host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }
    @Test fun realVocalEntryLoopsStopsAndKeepsExportUndoAndArchiveAtWideAndCompactInJaEn() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width,height,font) in listOf(Triple(1440,838,1f), Triple(390,844,2f))) {
            Locale.setDefault(locale)
            val h = Harness(); h.prepare()
            val presenter = ContinuousEditorPresenter(h.backend.studio,h.scope,h.ports)
            val scene = ImageComposeScene(width=width,height=height,density=Density(1f,font),coroutineContext=coroutineContext) {
                val state by presenter.state.collectAsState()
                val practice by presenter.vocalPractice.collectAsState()
                ContinuousEditor(state,presenter::onAction,presenter::readout,vocalPractice=practice)
            }
            try {
                until { presenter.state.value.permits(ContinuousCapability.VOCAL_PRACTICE) }
                assertTrue(presenter.dispatch(ContinuousEditorAction.SetOriginalPitch(6f)))
                val before = h.backend.studio.document.value
                assertTrue(presenter.dispatch(ContinuousEditorAction.ExportWav))
                until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                val exportedBefore = Files.readAllBytes(h.exported)
                scene.pointer("ce-nav-BEAT")
                scene.pointer("ce-lyrics-open")
                scene.pointer("ce-practice-open")
                scene.awaitOwners(2)
                val controller = requireNotNull(presenter.vocalPractice.value)
                scene.setText("practice-start","0.2"); scene.setText("practice-end","0.32")
                scene.pointer("practice-speed-80")
                scene.pointer("practice-preview")
                try { until { controller.state.value.phase == PracticePhase.PLAYING } }
                catch (failure: TimeoutCancellationException) {
                    error("Practice did not start: ${controller.state.value}; preview=${h.host.vocalPractice.preview.state.value}; driver=${h.backend.engine.status.value}")
                }
                val frame = h.backend.engine.snapshot().frame
                until { h.backend.engine.snapshot().frame >= frame + 7_200*3 }
                assertTrue(h.backend.engine.originalPlayback().playing)
                assertTrue(h.backend.audition.nativeFrame() in 0 until 7_200)
                assertEquals(1f,h.backend.engine.originalPlayback().gain)
                assertEquals(before,h.backend.studio.document.value)
                for (tag in listOf("practice-stop","practice-close")) scene.fullHit(tag,width,height)
                val evidence = java.io.File("build/reports/ui-evidence/vocal-practice").apply { mkdirs() }
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { evidence.resolve("${locale.language}-${width}-font${(font*100).toInt()}.png").writeBytes(it.bytes) } }
                scene.pointer("practice-stop")
                until { controller.state.value.phase == PracticePhase.EDITING }
                assertFalse(h.backend.engine.originalPlayback().playing)
                assertEquals(5_000L,h.backend.audition.nativeFrame())
                assertEquals(.25f,h.backend.engine.originalPlayback().gain)
                assertEquals(before,h.backend.studio.document.value)
                scene.pointer("practice-loop") // An explicit single play ends and restores by itself.
                scene.pointer("practice-preview")
                until { controller.state.value.phase == PracticePhase.EDITING }
                assertFalse(h.backend.engine.originalPlayback().playing)
                scene.pointer("practice-close"); scene.awaitOwners(1)
                until { presenter.vocalPractice.value == null }
                scene.pointer("ce-nav-SAVE"); scene.pointer("ce-export")
                until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                assertContentEquals(exportedBefore,Files.readAllBytes(h.exported))
                scene.pointer("ce-save")
                until { presenter.state.value.status == ContinuousStatus.SAVED }
                assertEquals(before.project,h.backend.studio.document.value.project)
                val restored = NextBackend.create(h.directory.resolve("restored"),sinkFactory={ error("No device") },microphone={null})
                try {
                    assertTrue(restored.openProject(h.archive).accepted)
                    until { restored.studio.work.value.jobId == null }
                    assertEquals(before.project,restored.studio.document.value.project)
                    assertContentEquals(Files.readAllBytes(h.source),restored.assets.read(before.project.asset(before.project.source!!.assetHash)))
                    assertEquals(before.project.assets,restored.studio.document.value.project.assets,"Practice audio is not added to the saved song")
                } finally { restored.shutdown() }
                assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
                assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
                assertEquals(before.project,h.backend.studio.document.value.project)
            } finally { scene.close(); presenter.close(); h.close() }
        } } finally { Locale.setDefault(previous) }
        assertEquals(0L,PcmMemoryBudget.shared.statistics().usedBytes)
    }

    @Test fun recordingEntryStopsPracticeBeforePermissionAndStaleOrClosedWorkersCannotClaimSource() = runBlocking<Unit> {
        for (ending in listOf("record","stale","close")) {
            val h=Harness(); h.prepare()
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val returned=CompletableDeferred<Unit>()
            val claimed=h.host.vocalPractice.preview
            val practicePort=object:VocalPracticePort {
                override val preview=claimed
                override val renderer=object:VocalPracticeRenderer {
                    override suspend fun render(project:Project, revision:Long, request:VocalPracticeRequest, progress:(PracticeProgress)->Unit):PracticeResult<Asset> {
                        withContext(NonCancellable) { entered.complete(Unit); release.await() }
                        returned.complete(Unit)
                        return PracticeResult.Success(project.assets.first().copy(role=AssetRole.RENDERED))
                    }
                }
            }
            var permissions=0
            val presenter=ContinuousEditorPresenter(h.backend.studio,h.scope,object:ContinuousEditorPorts by h.ports {
                override val vocalPractice=practicePort
                override val recordingCue: RecordingCuePort? = null
                override val voiceAvailable=true
                override suspend fun startVoice(maxSeconds:Int):VoiceStart {
                    assertFalse(claimed.state.value.ownsSource)
                    permissions++; return VoiceStart.DENIED
                }
            })
            try {
                val before=h.backend.studio.document.value
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
                val controller=presenter.vocalPractice.value!!
                val preparation=async { controller.preview() }
                withTimeout(10_000) { entered.await() }
                val end=when(ending) {
                    "record" -> async { presenter.dispatch(ContinuousEditorAction.RecordVoice) }
                    "stale" -> async { h.backend.studio.dispatch(Action.Edit(Intent.Rename("Changed"))).accepted }
                    else -> async { presenter.close(); true }
                }
                until { controller.state.value.phase==PracticePhase.CANCELLING }
                assertFalse(claimed.state.value.ownsSource)
                assertFalse(preparation.isCompleted,"Non-cooperative worker remains owned until it actually exits")
                if (ending=="close") {
                    presenter.onAction(ContinuousEditorAction.OpenVocalPractice)
                    presenter.onAction(ContinuousEditorAction.RecordVoice)
                }
                release.complete(Unit)
                withTimeout(10_000) { returned.await(); end.await(); preparation.await() }
                assertFalse(claimed.state.value.ownsSource)
                assertFalse(h.backend.engine.originalPlayback().playing)
                assertEquals(if(ending=="record")1 else 0,permissions)
                assertEquals(if(ending=="stale")before.revision+1 else before.revision,h.backend.studio.document.value.revision)
            } finally { release.complete(Unit); presenter.close(); h.close() }
        }
    }

    @Test fun outputLossRetainsRestorationOwnershipAndRecordingCannotStartOverThePlayingPreview() = runBlocking<Unit> {
        val h=Harness(); h.prepare()
        var permissions=0
        val presenter=ContinuousEditorPresenter(h.backend.studio,h.scope,object:ContinuousEditorPorts by h.ports {
            override val recordingCue:RecordingCuePort?=null
            override val voiceAvailable=true
            override suspend fun startVoice(maxSeconds:Int):VoiceStart {
                assertFalse(h.host.vocalPractice.preview.state.value.ownsSource)
                assertFalse(h.backend.engine.originalPlayback().playing)
                permissions++; return VoiceStart.STARTED
            }
            override fun cueVoice() = Unit
            override fun voiceFull()=false
            override fun voiceInterrupted()=false
            override suspend fun stopVoice(name:String):VoiceTake?=null
        })
        try {
            val before=h.backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            val controller=presenter.vocalPractice.value!!
            controller.update { it.copy(startSeconds="0.2",endSeconds="0.4") }
            assertTrue(controller.preview())
            h.lost.set(true)
            until { h.backend.engine.status.value.phase==DriverPhase.EDITING_ONLY }
            until { controller.state.value.phase==PracticePhase.STOPPING }
            assertEquals(PracticeProblem.RESTORE_FAILED,controller.state.value.problem)
            assertTrue(h.host.vocalPractice.preview.state.value.ownsSource)
            h.lost.set(false); h.backend.engine.reattach()
            until { !h.host.vocalPractice.preview.state.value.ownsSource }
            until { controller.state.value.phase==PracticePhase.EDITING }
            assertFalse(h.backend.engine.originalPlayback().playing)
            assertEquals(5_000L,h.backend.audition.nativeFrame())
            assertTrue(controller.preview())
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertEquals(1,permissions)
            assertEquals(PracticePhase.CLOSED,controller.state.value.phase)
            assertFalse(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            assertTrue(presenter.dispatch(ContinuousEditorAction.StopVoice))
            assertEquals(before,h.backend.studio.document.value)
        } finally { presenter.close(); h.close() }
    }

    @Test fun practiceAndAnalysisTransferSourceOwnershipAndRejectLateAnalysisWithoutChangingTheSong() = runBlocking<Unit> {
        val h = Harness(); h.prepare()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val presenter = ContinuousEditorPresenter(h.backend.studio, h.scope, object : ContinuousEditorPorts by h.ports {
            override suspend fun analyseSource(asset: Asset, range: FrameRange): com.choplab.core.analysis.SourceMusicResult {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return analysisResult(asset, range)
            }
        })
        try {
            val before = h.backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            val analysis = assertNotNull(presenter.sourceAnalysis.value)
            val analysing = async { analysis.dispatch(com.choplab.ui.analysis.SourceAnalysisAction.Analyse) }
            withTimeout(5_000) { entered.await() }
            assertTrue(withTimeout(1_000) { presenter.dispatch(ContinuousEditorAction.OpenVocalPractice) })
            assertNull(presenter.sourceAnalysis.value)
            assertEquals(com.choplab.ui.analysis.SourceAnalysisPhase.CLOSED, analysis.state.value.phase)
            val practice = assertNotNull(presenter.vocalPractice.value)
            practice.update { it.copy(startSeconds = "0.2", endSeconds = "0.4") }
            assertTrue(practice.preview())
            release.complete(Unit)
            assertFalse(analysing.await())
            assertNull(analysis.state.value.result)
            assertEquals(PracticePhase.PLAYING, practice.state.value.phase)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            assertEquals(PracticePhase.CLOSED, practice.state.value.phase)
            assertFalse(h.host.vocalPractice.preview.state.value.ownsSource)
            assertFalse(h.backend.engine.originalPlayback().playing)
            assertEquals(5_000L, h.backend.audition.nativeFrame())
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            assertNull(presenter.sourceAnalysis.value)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Mixer(com.choplab.ui.mixer.MixerAction.Open())))
            until { presenter.vocalPractice.value == null && presenter.state.value.mixer.draft != null }
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            until { presenter.state.value.mixer.draft == null }
            assertEquals(before, h.backend.studio.document.value)
        } finally { release.complete(Unit); presenter.close(); h.close() }
    }

    @Test fun applyingAnalysisKeepsItsOwnerWhilePracticeEntryIsRefusedOutsideTheEditLock() = runBlocking<Unit> {
        val h = Harness(); h.prepare()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val presenter = ContinuousEditorPresenter(h.backend.studio, h.scope, object : ContinuousEditorPorts by h.ports {
            override suspend fun analyseSource(asset: Asset, range: FrameRange) = analysisResult(asset, range)
            override suspend fun setSongMonitorGain(gain: Float): Boolean {
                entered.complete(Unit); release.await(); return true
            }
        })
        try {
            val before = h.backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            val analysis = assertNotNull(presenter.sourceAnalysis.value)
            assertTrue(analysis.dispatch(com.choplab.ui.analysis.SourceAnalysisAction.Analyse))
            assertTrue(analysis.dispatch(com.choplab.ui.analysis.SourceAnalysisAction.SelectTempo(98_000)))
            val holding = async { presenter.dispatch(ContinuousEditorAction.SetSongMonitorGain(.5f)) }
            withTimeout(5_000) { entered.await() }
            val applying = async { analysis.dispatch(com.choplab.ui.analysis.SourceAnalysisAction.Apply) }
            until { analysis.state.value.phase == com.choplab.ui.analysis.SourceAnalysisPhase.APPLYING }
            assertFalse(withTimeout(1_000) { presenter.dispatch(ContinuousEditorAction.OpenVocalPractice) })
            assertSame(analysis, presenter.sourceAnalysis.value)
            assertNull(presenter.vocalPractice.value)
            assertEquals(before, h.backend.studio.document.value)
            release.complete(Unit)
            assertTrue(holding.await()); assertTrue(applying.await())
            assertEquals(before.revision + 1, h.backend.studio.document.value.revision)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalPractice))
            assertNull(presenter.sourceAnalysis.value)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before.project, h.backend.studio.document.value.project)
        } finally { release.complete(Unit); presenter.close(); h.close() }
    }

    private fun analysisResult(asset: Asset, range: FrameRange): com.choplab.core.analysis.SourceMusicResult {
        val rate = com.choplab.core.analysis.SourceMusicAnalysis.RATE
        val first = (range.start * rate + asset.sampleRate - 1) / asset.sampleRate
        val end = range.end * rate / asset.sampleRate
        return com.choplab.core.analysis.SourceMusicResult((end - first).toInt(),
            frozenListOf(com.choplab.core.analysis.TempoCandidate(98_000, .8)), frozenListOf())
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
