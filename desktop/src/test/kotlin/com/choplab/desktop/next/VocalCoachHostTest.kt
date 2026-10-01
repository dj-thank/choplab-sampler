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
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.ai.*
import com.choplab.ui.analysis.*
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.*
import kotlin.test.*

/** Real editor/PCM/preview/recording/export/archive, synthetic input and output; no actual microphone or TTS quality claim. */
class VocalCoachHostTest {
    @Test fun historyToLocalEvidenceSelectedTakePracticeAndGuideResponsePreserveTheSongAcrossBothHostLayouts() = runBlocking<Unit> {
        val previousLocale = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val directory = Files.createTempDirectory("coach-host-")
                val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = ::pacedSink, microphone = ::SineMic)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = DesktopEditorPorts(backend) { null }
                val archive = directory.resolve("song.choplab")
                val exported = directory.resolve("song.wav")
                val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by host {
                    override suspend fun chooseSave() = backend.files.register(archive)
                    override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(exported), frames.toInt(), bits = 24)
                })
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    val state by presenter.state.collectAsState()
                    val coach by presenter.vocalCoach.collectAsState()
                    val practice by presenter.vocalPractice.collectAsState()
                    val punch by presenter.vocalPunch.collectAsState()
                    ContinuousEditor(state, presenter::onAction, presenter::readout, vocalCoach = coach, vocalPractice = practice, vocalPunch = punch)
                }
                try {
                    until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
                    fun put(hz: Double, onset: Double, name: String, role: AssetRole = AssetRole.ORIGINAL): Pair<Asset, ByteArray> {
                        val samples = FloatArray(48_000 * 2) { index ->
                            val t = index / 2 / 48_000.0
                            if (t < onset || t > .85) 0f else (.12 * sin(2 * PI * hz * t) * if (index % 2 == 0) 1.0 else -.5).toFloat()
                        }
                        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples) }.toByteArray()
                        return Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 48_000, name, role)
                            .also { backend.assets.publish(it, bytes.inputStream()) } to bytes
                    }
                    val (take, original) = put(220 * 2.0.pow(50.0 / 1200), .25, "Recorded take")
                    val (reference, _) = put(220.0, .1, "Reference")
                    val (guide, _) = put(330.0, .1, "Placed guide fixture", AssetRole.RENDERED)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.ImportAsset(take))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(
                        frozenListOf(Track("voice", "Voice", TrackKind.VOCAL), Track("guide", "Guide", TrackKind.GUIDE)),
                        frozenListOf(Clip("voice", "voice", take.hash, FrameRange(0, take.frames)),
                            Clip("guide", "guide", guide.hash, FrameRange(0, guide.frames))),
                        frozenListOf(Take("reference", "voice", reference.hash, FrameRange(0, reference.frames), 0),
                            Take("take", "voice", take.hash, FrameRange(0, take.frames), 0)), frozenListOf(reference, guide)))).accepted)
                    assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(LyricLine("line", "A measured line", 0, 1920))))).accepted)
                    assertTrue(backend.audition.originalGain(.23f)); assertTrue(backend.audition.seek(take, 1234))
                    until { backend.audition.nativeFrame() == 1234L }
                    assertTrue(backend.studio.dispatch(Action.SetMetronome(true)).accepted)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.ExportWav))
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val baselineExport = Files.readAllBytes(exported)
                    val before = backend.studio.document.value
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)))
                    scene.settle()
                    if (width == 1440) for (pad in 0..15) scene.fullHit("ce-pad-$pad", width, height)
                    scene.pointer("ce-lyrics-open")
                    scene.pointer("ce-coach-open")
                    val coach = assertNotNull(presenter.vocalCoach.value)
                    assertEquals(2, coach.state.value.takes.size)
                    scene.pointer("coach-reference-reference")
                    scene.pointer("coach-input-VOICE_ONLY")
                    scene.pointer("coach-analyze")
                    until { coach.state.value.report != null }
                    assertNull(coach.state.value.problem)
                    assertEquals(150, coach.state.value.line?.onsetDifferenceMillis)
                    assertTrue(abs(assertNotNull(coach.state.value.line?.meanAbsolutePitchCents) - 50) <= 2)
                    scene.settle()
                    assertNotNull(scene.tag("coach-observations"))
                    assertEquals(before, backend.studio.document.value)
                    for (tag in listOf("coach-stop", "coach-close")) scene.fullHit(tag, width, height)
                    scene.pointer("coach-practice")
                    until { presenter.vocalPractice.value != null }
                    val practice = assertNotNull(presenter.vocalPractice.value)
                    assertNull(presenter.vocalCoach.value)
                    assertEquals("0.0", practice.state.value.startSeconds); assertEquals("1.0", practice.state.value.endSeconds)
                    scene.pointer("practice-preview")
                    until { practice.state.value.phase == PracticePhase.PLAYING }
                    assertEquals(VocalPreviewOwner.PRACTICE, host.vocalPractice.preview.state.value.owner)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("practice-stop")
                    until { !host.vocalPractice.preview.state.value.ownsSource }
                    assertEquals(1234L, backend.audition.nativeFrame()); assertEquals(.23f, backend.engine.originalPlayback().gain)
                    scene.pointer("practice-close")
                    until { presenter.vocalPractice.value == null }
                    scene.pointer("ce-lyrics-open"); scene.pointer("ce-coach-open")
                    val response = assertNotNull(presenter.vocalCoach.value)
                    scene.pointer("coach-input-VOICE_ONLY"); scene.pointer("coach-analyze")
                    until { response.state.value.report != null }
                    scene.pointer("coach-listen")
                    until { response.state.value.phase == CoachPhase.READY_RESPONSE }
                    assertFalse(host.vocalCoach.preview.state.value.ownsSource)
                    assertEquals(1234L, backend.audition.nativeFrame()); assertEquals(.23f, backend.engine.originalPlayback().gain)
                    assertEquals(before, backend.studio.document.value)
                    scene.pointer("coach-respond")
                    until { presenter.vocalPunch.value != null }
                    val punch = assertNotNull(presenter.vocalPunch.value)
                    assertFalse(punch.state.value.busy, "Preparing a response never opens the microphone")
                    assertEquals("0.0", punch.state.value.startSeconds); assertEquals("1.0", punch.state.value.endSeconds)
                    punch.update { it.copy(countInBars = 0, preRollBars = 0) }
                    assertTrue(punch.record())
                    val after = backend.studio.document.value
                    assertEquals(before.project.takes.size + 1, after.project.takes.size)
                    assertEquals(before.project.clips, after.project.clips)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.CloseVocalPunch))
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(after.project, backend.studio.document.value.project)
                    assertTrue(presenter.dispatch(ContinuousEditorAction.ExportWav))
                    until { presenter.state.value.status == ContinuousStatus.EXPORTED }
                    val wav = Files.newInputStream(exported).use(WavCodec::read)
                    assertEquals(24, wav.info.bits); assertEquals(48_000L, wav.info.frames)
                    assertContentEquals(baselineExport, Files.readAllBytes(exported),
                        "Practice/guide SOURCE audio, monitor click and an unselected response take never enter the saved arrangement mix")
                    assertTrue(presenter.dispatch(ContinuousEditorAction.SaveProject))
                    until { presenter.state.value.status == ContinuousStatus.SAVED }
                    val reopened = NextBackend.create(directory.resolve("reopened"), sinkFactory = { error("No native output") }, microphone = { null })
                    try {
                        assertTrue(reopened.openProject(archive).accepted)
                        until { reopened.studio.work.value.jobId == null }
                        assertEquals(after.project, reopened.studio.document.value.project)
                        assertContentEquals(original, reopened.assets.read(take))
                    } finally { reopened.shutdown() }
                } finally { scene.close(); presenter.close(); host.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively() }
            }
        } finally { Locale.setDefault(previousLocale) }
    }

    @Test fun coachAndSourceAnalysisCancelLateWorkAndRestoreSourceBeforeTransferringOwnership() = runBlocking<Unit> {
        val h = Harness(); h.prepare()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val presenter = ContinuousEditorPresenter(h.backend.studio, h.scope, object : ContinuousEditorPorts by h.host {
            override suspend fun analyseSource(asset: Asset, range: FrameRange): com.choplab.core.analysis.SourceMusicResult {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                return analysisResult(asset, range)
            }
        })
        try {
            val before = h.backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            val analysis = assertNotNull(presenter.sourceAnalysis.value)
            val pending = async { analysis.dispatch(SourceAnalysisAction.Analyse) }
            withTimeout(5_000) { entered.await() }
            assertTrue(withTimeout(1_000) { presenter.dispatch(ContinuousEditorAction.OpenVocalCoach) })
            assertNull(presenter.sourceAnalysis.value)
            assertEquals(SourceAnalysisPhase.CLOSED, analysis.state.value.phase)
            val coach = assertNotNull(presenter.vocalCoach.value)
            assertTrue(coach.dispatch(CoachAction.Input(CoachVoiceInput.VOICE_ONLY)))
            assertTrue(coach.dispatch(CoachAction.Analyze))
            assertTrue(coach.dispatch(CoachAction.ListenGuide))
            assertEquals(VocalPreviewOwner.COACH, h.host.vocalCoach.preview.state.value.owner)
            release.complete(Unit)
            assertFalse(pending.await()); assertNull(analysis.state.value.result)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
            assertEquals(CoachPhase.CLOSED, coach.state.value.phase)
            assertFalse(coach.dispatch(CoachAction.Respond))
            assertNull(presenter.vocalCoach.value)
            assertFalse(h.host.vocalCoach.preview.state.value.ownsSource)
            assertEquals(5_000L, h.backend.audition.nativeFrame())
            assertEquals(.25f, h.backend.engine.originalPlayback().gain)
            assertFalse(h.backend.engine.originalPlayback().playing)
            assertEquals(before, h.backend.studio.document.value)
        } finally { release.complete(Unit); presenter.close(); h.close() }
    }

    @Test fun applyingAnalysisAndGuideKeepTheirOwnerAndUndoWhileCoachEntryIsRefused() = runBlocking<Unit> {
        for (kind in listOf("analysis", "guide")) {
            val h = Harness(); h.prepare()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val presenter = ContinuousEditorPresenter(h.backend.studio, h.scope, object : ContinuousEditorPorts by h.host {
                override suspend fun analyseSource(asset: Asset, range: FrameRange) = analysisResult(asset, range)
                override suspend fun setSongMonitorGain(gain: Float): Boolean {
                    entered.complete(Unit); release.await(); return true
                }
                override val vocalGuide = object : VocalGuidePort {
                    override val preview = h.host.vocalGuide.preview
                    override fun createSynthesis() = object : VocalSynthesisPort {
                        override suspend fun voices() = TtsResult.Success(frozenListOf(TtsVoice(
                            TtsEngine("device-test", "1", "system", "1"), "offline", "Offline", "ja-JP", "1", LyricLanguage.JAPANESE)))
                        override suspend fun prepare(row: FlowRow, tempo: com.choplab.engine.Tempo, voice: TtsVoice,
                            settings: TtsSettings, regenerate: Boolean) = TtsResult.Success(PreparedVocalLine(row.line, h.guide, 1.0, 0, false, false))
                        override fun close() = Unit
                    }
                }
            })
            try {
                val before = h.backend.studio.document.value
                var analysis: SourceAnalysisController? = null
                var guide: VocalGuideController? = null
                if (kind == "analysis") {
                    assertTrue(presenter.dispatch(ContinuousEditorAction.OpenSourceAnalysis))
                    analysis = assertNotNull(presenter.sourceAnalysis.value)
                    assertTrue(analysis.dispatch(SourceAnalysisAction.Analyse))
                    assertTrue(analysis.dispatch(SourceAnalysisAction.SelectTempo(98_000)))
                } else {
                    assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalGuide))
                    guide = assertNotNull(presenter.vocalGuide.value)
                    until { !guide.state.value.loadingVoices }
                    assertTrue(guide.prepare()); until { guide.state.value.phase == VocalGuidePhase.READY }
                }
                val holding = async { presenter.dispatch(ContinuousEditorAction.SetSongMonitorGain(.5f)) }
                withTimeout(5_000) { entered.await() }
                val applying = async { if (analysis != null) analysis.dispatch(SourceAnalysisAction.Apply) else guide!!.apply() }
                until { analysis?.state?.value?.phase == SourceAnalysisPhase.APPLYING || guide?.state?.value?.phase == VocalGuidePhase.APPLYING }
                assertFalse(withTimeout(1_000) { presenter.dispatch(ContinuousEditorAction.OpenVocalCoach) }, kind)
                assertNull(presenter.vocalCoach.value)
                if (analysis != null) assertSame(analysis, presenter.sourceAnalysis.value)
                else assertSame(guide, presenter.vocalGuide.value)
                assertEquals(before, h.backend.studio.document.value)
                release.complete(Unit)
                assertTrue(holding.await()); assertTrue(applying.await(), kind)
                until { h.backend.studio.work.value.jobId == null }
                assertEquals(before.revision + 1, h.backend.studio.document.value.revision)
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalCoach))
                assertNull(presenter.sourceAnalysis.value); assertNull(presenter.vocalGuide.value)
                assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
                assertEquals(before.project, h.backend.studio.document.value.project)
            } finally { release.complete(Unit); presenter.close(); h.close() }
        }
    }

    @Test fun lateCoachAnalysisCannotSurviveSourceAnalysisRecordingOrStop() = runBlocking<Unit> {
        for (ending in listOf("analysis", "record", "stop")) {
            val h = Harness(); h.prepare()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var permissions = 0
            val presenter = ContinuousEditorPresenter(h.backend.studio, h.scope, object : ContinuousEditorPorts by h.host {
                override val voiceAvailable = true
                override val recordingCue: RecordingCuePort? = null
                override suspend fun startVoice(maxSeconds: Int): VoiceStart {
                    assertFalse(h.host.vocalCoach.preview.state.value.ownsSource)
                    // The ordinary recording entry stops the restored SOURCE before opening the microphone.
                    assertEquals(0L, h.backend.audition.nativeFrame())
                    permissions++; return VoiceStart.DENIED
                }
                override val vocalCoach = object : VocalCoachHost by h.host.vocalCoach {
                    override val analyzer = object : VocalCoachAnalyzer {
                        override suspend fun analyze(project: Project, revision: Long, request: VocalCoachRequest): CoachResult<VocalCoachReport> {
                            entered.complete(Unit); withContext(NonCancellable) { release.await() }
                            return h.host.vocalCoach.analyzer.analyze(project, revision, request)
                        }
                    }
                }
            })
            try {
                val before = h.backend.studio.document.value
                assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalCoach))
                val coach = assertNotNull(presenter.vocalCoach.value)
                assertTrue(coach.dispatch(CoachAction.Input(CoachVoiceInput.VOICE_ONLY)))
                val pending = async { coach.dispatch(CoachAction.Analyze) }
                withTimeout(5_000) { entered.await() }
                val action = when (ending) {
                    "analysis" -> ContinuousEditorAction.OpenSourceAnalysis
                    "record" -> ContinuousEditorAction.RecordVoice
                    else -> ContinuousEditorAction.StopAll
                }
                withTimeout(1_000) { presenter.dispatch(action) }
                release.complete(Unit)
                assertFalse(pending.await(), ending)
                assertNull(coach.state.value.report)
                assertFalse(coach.dispatch(CoachAction.Respond))
                assertFalse(h.host.vocalCoach.preview.state.value.ownsSource)
                assertEquals(if (ending == "record") 1 else 0, permissions)
                assertEquals(before, h.backend.studio.document.value)
            } finally { release.complete(Unit); presenter.close(); h.close() }
        }
    }

    private inner class Harness {
        val directory = Files.createTempDirectory("coach-owner-")
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = ::pacedSink, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEditorPorts(backend) { null }
        lateinit var guide: Asset
        suspend fun prepare() {
            until { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            fun put(hz: Double, role: AssetRole): Asset {
                val samples = FloatArray(96_000 * 2) { i -> (sin(i / 2 * 2 * PI * hz / 48_000) * if (i % 2 == 0) .1 else -.037).toFloat() }
                val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples) }.toByteArray()
                return Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 96_000, "Synthetic voice", role)
                    .also { backend.assets.publish(it, bytes.inputStream()) }
            }
            val take = put(220.0, AssetRole.ORIGINAL); guide = put(330.0, AssetRole.RENDERED)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.ImportAsset(take))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetArrangement(
                frozenListOf(Track("voice", "Voice", TrackKind.VOCAL), Track("guide", "Guide", TrackKind.GUIDE)),
                frozenListOf(Clip("voice", "voice", take.hash, FrameRange(0, take.frames)), Clip("guide", "guide", guide.hash, FrameRange(0, guide.frames))),
                frozenListOf(Take("take", "voice", take.hash, FrameRange(0, take.frames), 0)), frozenListOf(guide)))).accepted)
            val lyrics = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetStructuredLyrics(lyrics.lines, lyrics.structure))).accepted)
            assertTrue(backend.audition.seek(take, 5_000)); assertTrue(backend.audition.originalGain(.25f))
        }
        suspend fun close() { host.close(); backend.shutdown(); scope.cancel(); directory.toFile().deleteRecursively() }
    }

    private fun analysisResult(asset: Asset, range: FrameRange): com.choplab.core.analysis.SourceMusicResult {
        val rate = com.choplab.core.analysis.SourceMusicAnalysis.RATE
        val first = (range.start * rate + asset.sampleRate - 1) / asset.sampleRate
        val end = range.end * rate / asset.sampleRate
        return com.choplab.core.analysis.SourceMusicResult((end - first).toInt(),
            frozenListOf(com.choplab.core.analysis.TempoCandidate(98_000, .8)), frozenListOf())
    }

    private class SineMic : MicInput {
        override val sampleRate = 48_000
        private var frame = 0L
        private var started = 0L
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            if (started == 0L) started = System.nanoTime()
            val count = minOf(480, buffer.size)
            for (i in 0 until count) buffer[i] = (.12 * sin(2 * PI * 220 * (frame + i) / 48_000)).toFloat()
            frame += count
            val due = started + frame * 1_000_000_000L / sampleRate
            while (!stopped && System.nanoTime() < due) LockSupport.parkNanos((due - System.nanoTime()).coerceAtLeast(1))
            return if (stopped) -1 else count
        }
        override fun stop() { stopped = true }
        override fun close() = stop()
    }
    private fun pacedSink() = object : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000); return length
        }
        override fun close() = Unit
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
