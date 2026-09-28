package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.jvm.*
import com.choplab.jvm.ai.*
import com.choplab.ui.*
import com.choplab.ui.ai.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

/** The real desktop entry/Presenter/preview/source/Studio/export path; only the OS voice and audio endpoint are synthetic. */
class VocalGuideHostTest {
    @Test fun actualEntryRestoresSourceThenAppliesGuideWithOneUndoAndSaveReopen() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("vocal-guide-host-")
        val sink = CountingTestSink()
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        var closed = 0
        val voice = TtsVoice(TtsEngine("device-test", "1", "system", "1"), "offline", "Offline", "ja-JP", "1", LyricLanguage.JAPANESE)
        val synthesis = object : TtsProvider {
            override suspend fun voices() = TtsResult.Success(frozenListOf(voice))
            override suspend fun synthesize(request: TtsRequest) = TtsResult.Success(TtsAudio.fromPcm(
                FloatArray(44_100) { (.1 * sin(2 * PI * 220 * it / 22_050)).toFloat() }, 22_050, 1))
            override fun close() { closed++ }
        }
        val ports = object : ContinuousEditorPorts by real {
            override val vocalGuide = object : VocalGuidePort {
                override val preview = real.vocalGuide.preview
                override fun createSynthesis() = VocalTtsService(synthesis, TtsCache(directory.resolve("cache")), backend.assets)
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val input = directory.resolve("source.wav")
            Files.newOutputStream(input).use { WavCodec.writeFloat(it, FloatArray(480_000 * 2) { if (it % 2 == 0) .03f else -.06f }) }
            assertTrue(backend.importAudio(input).accepted); idle(backend)
            val original = backend.studio.document.value.project.source!!
            val originalAsset = backend.studio.document.value.project.asset(original.assetHash)
            val originalBytes = backend.assets.read(originalAsset)
            val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("一番", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetStructuredLyrics(placement.lines, placement.structure))).accepted)
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetSourcePitch(6.0))).accepted)
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetOriginalMonitorGain(.3f)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.SeekOriginal(24_000)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.PlayOriginal))
            val before = backend.studio.document.value
            assertTrue(presenter.dispatch(ContinuousEditorAction.Lyrics(LyricAction.Open)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalGuide))
            val controller = requireNotNull(presenter.vocalGuide.value)
            waitUntil { !controller.state.value.loadingVoices }
            assertNotNull(controller.state.value.plan)
            assertTrue(controller.prepare()); waitUntil { controller.state.value.phase == VocalGuidePhase.READY }
            assertEquals(before.project, backend.studio.document.value.project)
            val prepared = requireNotNull(controller.state.value.rows.single().prepared)
            assertTrue(controller.listen(placement.lines.single().id))
            waitUntil { ports.vocalGuide.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val savedFrame = ports.vocalGuide.preview.state.value.originalFrame
            val output = sink.frames
            waitUntil { sink.frames >= output + 2_048 }
            assertEquals(savedFrame, presenter.readout().originalFrame, "Guide frames never rotate the original waveform")
            assertEquals(1f, backend.engine.originalPlayback().gain)
            for (action in listOf(ContinuousEditorAction.PlayOriginal, ContinuousEditorAction.SeekOriginal(0),
                ContinuousEditorAction.SetOriginalMonitorGain(.1f), ContinuousEditorAction.SetOriginalPitch(-3f), ContinuousEditorAction.OpenScratch))
                assertFalse(presenter.dispatch(action), "SOURCE is exclusively owned during preview: $action")
            assertTrue(controller.preview.stop() is TtsResult.Success)
            assertFalse(backend.engine.originalPlayback().playing)
            assertEquals(savedFrame, backend.audition.nativeFrame())
            assertEquals(.3f, backend.engine.originalPlayback().gain)
            assertEquals(before, backend.studio.document.value)
            assertContentEquals(originalBytes, backend.assets.read(originalAsset))

            assertTrue(controller.apply())
            val applied = backend.studio.document.value.project
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            assertEquals(TrackKind.GUIDE, applied.tracks.single().kind)
            assertEquals(placement.structure, applied.lyricStructure)
            assertTrue(applied.lyrics.single().words.all { it.timingOrigin == WordTimingOrigin.ESTIMATED })
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo)); assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo)); assertEquals(applied, backend.studio.document.value.project)
            val export = directory.resolve("song.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(export), 96_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle(backend)
            val wave = Files.newInputStream(export).use(WavCodec::read)
            assertEquals(96_000L, wave.info.frames); assertEquals(24, wave.info.bits)
            assertTrue(wave.samples.any { abs(it) > .01f })
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(applied, backend.assets, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            assertEquals(applied, ArchiveCodec().read(ByteArrayInputStream(archive), fresh))
            assertContentEquals(backend.assets.read(prepared.asset), fresh.read(prepared.asset))
            backend.flushAutosave()
            assertEquals(applied, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.CloseVocalGuide))
            assertNull(presenter.vocalGuide.value); assertEquals(1, closed)
        } finally { presenter.close(); real.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively() }
        assertEquals(1, closed)
    }
    @Test fun pendingMicrophonePermissionRefusesGuideWorkWithoutWaitingForThePermission() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("vocal-guide-permission-")
        val backend = NextBackend.create(directory.resolve("profile"), sinkFactory = { CountingTestSink() }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        val entered = CompletableDeferred<Unit>()
        val permission = CompletableDeferred<Unit>()
        var synthesisCalls = 0
        var providerCloses = 0
        val voice = TtsVoice(TtsEngine("device-test", "1", "system", "1"), "offline", "Offline", "ja-JP", "1", LyricLanguage.JAPANESE)
        val ports = object : ContinuousEditorPorts by real {
            override val recordingCue: RecordingCuePort? = null
            override val voiceAvailable = true
            override suspend fun startVoice(maxSeconds: Int): VoiceStart {
                entered.complete(Unit); permission.await(); return VoiceStart.DENIED
            }
            override val vocalGuide = object : VocalGuidePort {
                override val preview = real.vocalGuide.preview
                override fun createSynthesis() = object : VocalSynthesisPort {
                    override suspend fun voices() = TtsResult.Success(frozenListOf(voice))
                    override suspend fun prepare(row: FlowRow, tempo: com.choplab.engine.Tempo, voice: TtsVoice,
                                                 settings: TtsSettings, regenerate: Boolean): TtsResult<PreparedVocalLine> {
                        synthesisCalls++; return ttsFailure(TtsProblem.FAILED)
                    }
                    override fun close() { providerCloses++ }
                }
            }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetStructuredLyrics(placement.lines, placement.structure))).accepted)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenVocalGuide))
            val controller = requireNotNull(presenter.vocalGuide.value)
            waitUntil { !controller.state.value.loadingVoices }
            val before = backend.studio.document.value
            val recording = async { presenter.dispatch(ContinuousEditorAction.RecordVoice) }
            withTimeout(5_000) { entered.await() }
            waitUntil { presenter.state.value.startingVoiceRecording }
            assertFalse(withTimeout(2_000) { controller.prepare() }, "TTS must refuse while the Presenter action lock awaits permission")
            assertFalse(controller.apply()); assertFalse(controller.listen(placement.lines.single().id))
            assertEquals(0, synthesisCalls)
            assertEquals(before, backend.studio.document.value)
            permission.complete(Unit)
            assertFalse(withTimeout(5_000) { recording.await() })
            assertEquals(before, backend.studio.document.value)
            assertEquals(TtsProblem.RECORDING, controller.state.value.failure?.problem)
        } finally { permission.complete(Unit); presenter.close(); real.close(); scope.cancel(); backend.shutdown(); directory.toFile().deleteRecursively() }
        assertEquals(1, providerCloses)
    }
    private suspend fun idle(backend: NextBackend) = waitUntil { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
}
