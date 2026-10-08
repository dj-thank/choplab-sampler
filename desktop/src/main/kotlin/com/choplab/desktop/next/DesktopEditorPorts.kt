package com.choplab.desktop.next

import androidx.compose.runtime.*
import com.choplab.core.*
import com.choplab.core.ai.GoogleLyricSession
import com.choplab.jvm.VocalPunchCapture
import com.choplab.core.model.Asset
import com.choplab.core.model.Pad
import com.choplab.desktop.DesktopProfile
import com.choplab.desktop.isMacOsHost
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.SpotifySessionPurpose
import com.choplab.jvm.OriginalAudioImportPort
import com.choplab.jvm.OriginalPlaybackProbe
import com.choplab.jvm.VoiceTakes
import com.choplab.jvm.ai.*
import com.choplab.jvm.separation.*
import com.choplab.ui.separation.FourStemFactory
import com.choplab.ui.*
import com.choplab.ui.vocal.*
import com.choplab.ui.ai.LyricProposalPort
import com.choplab.ui.ai.SessionLyricProposalPort
import com.choplab.ui.ai.VocalGuidePort
import com.choplab.ui.resources.*
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

/** Native host services used by the shared NEXT editor. */
internal class DesktopEditorPorts(
    private val backend: NextBackend,
    private val spotify: SpotifyDesktopSession = SpotifyDesktopSession(onStatus = {}, purpose = SpotifySessionPurpose.METADATA_ONLY),
    private val fourStemSessions: FourStemSessionFactory = OnnxFourStemFactory(FourStemModelStore(backend.assets.directory.parent.resolve("four-stem-model"))),
    private val fourStemMemory: () -> SeparationMemory = FourStemMemoryProbe()::sample,
    private val onlineDirectory: () -> Path = { DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library") },
    private val onlineBackend: () -> com.choplab.sampler.source.YoutubeSourceBackend = { com.choplab.sampler.source.newpipe.NewPipeSourceBackend() },
    private val googleTransport: () -> GeminiHttpTransport = { UrlConnectionGeminiTransport() },
    private val outputRevealer: NextOutputRevealer = NextOutputRevealer(),
    private val parent: () -> AwtWindow?,
) : ContinuousEditorPorts, AutoCloseable {
    init { require(spotify.purpose == SpotifySessionPurpose.METADATA_ONLY) }
    override val fourStems = FourStemFactory { backend.createFourStemWorker(fourStemSessions, fourStemMemory) }
    override val spotifyMetadataAvailable = true
    override suspend fun openSpotifyMetadata() = NextSpotifyDialog.show(parent(), spotify)
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speechPreview = SourceVocalPreview(backend.studio, backend.engine, backend.audition, speechScope)
    private val pitchRenderer = backend.pitchRenderer()
    /** Only this instance's current dialog/credential can receive an explicit reviewed allowance. */
    val googleLyrics = GoogleLyricSession(System::currentTimeMillis)
    override val vocalCoach = object : VocalCoachHost {
        override val analyzer = backend.coachAnalyzer()
        override val renderer = backend.practiceRenderer()
        override val preview = speechPreview
    }
    private val stretchRenderer = backend.stretchRenderer()
    override val beatStretch = object : com.choplab.ui.stretch.BeatStretchHost {
        override val preview = speechPreview
        override suspend fun render(project: com.choplab.core.model.Project, draft: com.choplab.core.edit.StretchDraft, progress: (Int, Int) -> Unit) =
            stretchRenderer.render(project, draft, if (japanese) "テンポ伸縮" else "Tempo stretch", progress)
        override suspend fun original(project: com.choplab.core.model.Project, draft: com.choplab.core.edit.StretchDraft) =
            stretchRenderer.original(project, draft, if (japanese) "元の素材" else "Original sound")
    }
    override val vocalPitch = object : VocalPitchHost {
        override val preview = speechPreview
        override suspend fun render(project: com.choplab.core.model.Project, draft: com.choplab.core.vocal.VocalPitchDraft,
            progress: (com.choplab.engine.PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch {
            val result = pitchRenderer.render(project, draft, if (japanese) "声のピッチ補正" else "Voice pitch correction", progress)
            return PreparedVocalPitch((result as? com.choplab.jvm.VocalPitchRenderResult.Rendered)?.asset, result.report)
        }
        override suspend fun original(project: com.choplab.core.model.Project, draft: com.choplab.core.vocal.VocalPitchDraft) =
            pitchRenderer.original(project, draft, if (japanese) "原音の試聴" else "Original audition")
    }
    override val vocalPunch = VocalPunchCapture(backend.studio, backend.engine, backend.voice,
        inputFailure = { if (microphoneDenied()) com.choplab.core.vocal.PunchProblem.PERMISSION else com.choplab.core.vocal.PunchProblem.NO_INPUT },
        cancelOpening = ::cancelVoiceOpening)
    override val vocalTakes = object : com.choplab.ui.vocal.VocalTakePort {
        override val preview = speechPreview
        override suspend fun render(project: com.choplab.core.model.Project, draft: com.choplab.core.vocal.VocalCompDraft, name: String) =
            backend.renderVocalComp(project, draft, name)
    }
    override val vocalGuide: VocalGuidePort = object : VocalGuidePort {
        override val preview = speechPreview
        override fun createSynthesis(): com.choplab.core.ai.VocalSynthesisPort {
            val directory = backend.assets.directory.parent.resolve("vocal-guide")
            return VocalTtsService(DesktopTtsProvider(directory.resolve("temporary")), TtsCache(directory.resolve("cache")), backend.assets)
        }
    }
    override fun close() { googleLyrics.close(); try { runBlocking { speechPreview.close() } } finally { speechScope.cancel(); spotify.close() } }
    override val vocalPractice = object : VocalPracticePort {
        override val renderer = backend.practiceRenderer()
        override val preview = speechPreview
    }
    override val lyricProposal: LyricProposalPort = SessionLyricProposalPort(googleLyrics) { GeminiLyricProvider(googleTransport()) }
    override val lyricFiles: LyricFiles = DesktopLyricFiles { save ->
        choose(save, listOf("lrc"), if (japanese) { if (save) "歌詞を書き出す" else "歌詞を読み込む" }
            else { if (save) "Export lyrics" else "Import lyrics" })
    }
    override val systemAudioCapture get() = backend.systemAudio
    private val japanese get() = Locale.getDefault().language == "ja"
    override val autoChop get() = backend.autoChop
    override val autosaveFailure get() = backend.persistenceFailure
    override val originalAvailable get() = backend.engine.status.value.phase == DriverPhase.ATTACHED
    override fun originalPlaying(): Boolean? = when (val probe = backend.engine.originalPlaybackProbe()) {
        is OriginalPlaybackProbe.Ready -> probe.playback.playing
        OriginalPlaybackProbe.Contended -> null
        OriginalPlaybackProbe.Unavailable -> if (backend.engine.status.value.phase != DriverPhase.ATTACHED) false else null
    }
    override fun playingPads() = backend.engine.playingPads()
    override fun cancelOriginalPreparation() = backend.audition.cancelPreparation()
    override suspend fun playOriginal(asset: Asset) = backend.audition.play(asset)
    override suspend fun stopOriginal() = backend.audition.pause()
    override suspend fun resetOriginal() = backend.audition.clear()
    override suspend fun seekOriginal(frame: Long): Boolean {
        val p = backend.studio.document.value.project
        val asset = p.source?.assetHash?.let(p::asset) ?: return false
        return backend.audition.seek(asset, frame)
    }
    override suspend fun setOriginalMonitorGain(gain: Float) = backend.audition.originalGain(gain)
    override suspend fun setHandMonitorGain(gain: Float) = backend.audition.handGain(gain)
    override suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long) = backend.audition.scratchStart(asset, from, start, end)
    override suspend fun scratchOriginalTo(position: Double, durationFrames: Int) = backend.audition.scratchTo(position, durationFrames)
    override suspend fun scratchOriginalCut(gain: Float) = backend.audition.scratchCut(gain)
    override suspend fun scratchOriginalEnd() = backend.audition.scratchEnd()
    override val padRenderAvailable = true
    override val stepPatternsAvailable = true
    override val noteRepeatAvailable = true
    override val sourceAnalysisAvailable = true
    override suspend fun analyseSource(asset: Asset, range: com.choplab.core.model.FrameRange) = backend.analyseSource(asset, range)
    override val loopOverdubAvailable = true
    override suspend fun createLoopOverdub(startFrame: Long, frames: Int, grid: IntArray, routes: List<com.choplab.engine.LoopOverdubRoute>) = backend.createLoopOverdub(startFrame, frames, grid, routes)
    override suspend fun renderPad(pad: Pad, source: Asset) = backend.renderPad(pad, source)
    override suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int?) =
        backend.renderPerformance(pad, source, releaseAt, limitFrames, stopAt)
    override suspend fun renderNoteRepeat(pad: Pad, source: Asset, tempo: com.choplab.engine.Tempo, ticks: Int,
                                         releaseAt: Int, limitFrames: Int, stopAt: Int?) =
        backend.renderNoteRepeat(pad, source, tempo, ticks, releaseAt, limitFrames, stopAt)
    override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
    override fun liveChopOutput() = backend.engine.liveChopOutput()
    override fun liveChopProbe() = backend.engine.liveChopProbe()
    override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.playback().sequenceRenderFrames,
        handSourceFrame = backend.audition.nativeHandFrame(), countInBeatsRemaining = backend.engine.snapshot().countInBeatsRemaining,
        pcm = pcmReadout(), input = voiceInputReadout())
    private fun pcmReadout() = backend.engine.pcmPlayback().let { ContinuousPcmReadout(it.status, it.underrunFrames, it.droppedRequests) }
    override suspend fun peaks(asset: Asset) = backend.loadPeaks(asset)
    override val drumKitsAvailable get() = true
    override suspend fun drumKit(kitId: String) = backend.prepareDrumKit(kitId)
    /** Java Sound draws no frame metrics here, so screen stutter stays unknown. */
    override fun diagnostics(): ContinuousDiagnostics = backend.engine.health().let { health ->
        ContinuousDiagnostics(outputAttached = health.attached, floatOutput = health.encoding?.let { it == SinkEncoding.FLOAT32 },
            sampleRate = health.sampleRate, blockFrames = health.blockFrames, bufferFrames = health.bufferFrames,
            pendingFrames = health.pendingFrames, underruns = health.underruns, outputLosses = health.outputLosses,
            measuredBlocks = health.measuredBlocks, renderP99 = health.renderP99, renderMax = health.renderMax, pcm = pcmReadout())
    }
    /** Java Sound reports no microphone before one is opened: a host without one answers at the first take. */
    override val voiceAvailable get() = true
    override suspend fun startVoice(maxSeconds: Int) = when (backend.voice.start(maxSeconds)) {
        VoiceTakes.Start.STARTED -> VoiceStart.STARTED
        VoiceTakes.Start.NO_ROOM -> VoiceStart.NO_ROOM
        VoiceTakes.Start.NO_INPUT -> voiceStartFailure()
    }
    private fun microphoneDenied() = backend.macAudio?.microphonePermission in setOf(
        com.choplab.desktop.audio.MacMicrophonePermission.Status.DENIED, com.choplab.desktop.audio.MacMicrophonePermission.Status.RESTRICTED)
    private fun voiceStartFailure() = when {
        backend.voice.openingFailure == com.choplab.jvm.InputOpeningFailure.TIMEOUT ||
            backend.macAudio?.microphonePermission == com.choplab.desktop.audio.MacMicrophonePermission.Status.TIMEOUT -> VoiceStart.TIMEOUT
        microphoneDenied() -> VoiceStart.DENIED
        else -> VoiceStart.UNAVAILABLE
    }
    override suspend fun prepareVoiceTakeAcceptance(project: com.choplab.core.model.Project, revision: Long) = backend.voice.prepareAcceptance(project, revision)
    override suspend fun retryPunchTake(name: String) = backend.voice.stopPunch(name)
    override fun cancelVoiceOpening() { backend.macAudio?.cancelOpening(); backend.voice.cancelOpening() }
    override fun voiceInputReadout() = backend.voice.inputReadout()
    override suspend fun voiceRecordingEstimateMillis(maxSeconds: Int) = backend.voice.estimateMillis(maxSeconds)
    override suspend fun acknowledgeVoiceTake() { backend.flushAutosave(); backend.voice.acknowledge() }
    override fun cueVoice() = backend.voice.cue()
    override val recordingCue: RecordingCuePort = object : RecordingCuePort {
        override suspend fun startArmedVoice(maxSeconds: Int) = when (backend.voice.start(maxSeconds, waitForCue = true)) {
            VoiceTakes.Start.STARTED -> VoiceStart.STARTED
            VoiceTakes.Start.NO_ROOM -> VoiceStart.NO_ROOM
            VoiceTakes.Start.NO_INPUT -> voiceStartFailure()
        }
        override fun cueVoiceAt(engineFrame: Long): Boolean = backend.engine.estimatedOutputNanos(engineFrame)?.let(backend.voice::cueAt) == true
        override fun armingTimedOut() = backend.voice.armingTimedOut
    }
    override fun voiceFull() = backend.voice.full
    override fun voiceRecordedMillis() = backend.voice.recordedMillis
    override fun voiceInterrupted() = backend.voice.interrupted
    override suspend fun stopVoice(name: String) = backend.voice.stop(name)
    override suspend fun discardVoice() = backend.voice.discard()
    override suspend fun copyText(text: String): Boolean = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            val copied = try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); true }
                catch (_: Exception) { false }
            if (answer.isActive) answer.resume(copied)
        }
    }
    override val separationAvailable = true
    override suspend fun separateSource(source: Asset) = NextSeparationDialog.choose(parent()) { suffix ->
        backend.separation(source, DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library"), "${source.name} — $suffix")
    }?.let { backend.files.registerNamed(it.path, it.title, it.hash) }
    override val onlineAvailable = true
    override val onlineSource = com.choplab.ui.source.OnlineSourceHost { scope, stop ->
        val port = NextOnlineSourcePort(onlineDirectory(), backend::validateLibraryFile, scope, stop, onlineBackend())
        com.choplab.ui.source.OnlineImportSession(port) { id -> port.saved(id)?.let {
            com.choplab.ui.source.OnlineImportSelection(backend.files.registerNamed(it.path, it.title, it.hash), it.hash)
        } }
    }
    override suspend fun chooseOnline() = NextOnlineDialog.choose(parent(),
        DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library"), backend::validateLibraryFile)?.let { backend.files.registerNamed(it.path, it.title, it.hash) }
    override val libraryAvailable = true
    override suspend fun chooseLibrary() = NextLibraryDialog.choose(parent(),
        DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library"), backend::validateLibraryFile)?.let { backend.files.registerNamed(it.path, it.title, it.hash) }
    override suspend fun chooseAudio() = choose(false, OriginalAudioImportPort.EXTENSIONS, if (japanese) "音源を開く" else "Open audio")?.let(backend.files::register)
    override suspend fun chooseOpen() = choose(false, listOf("choplab"), if (japanese) "制作を開く" else "Open project")?.let(backend.files::register)
    override suspend fun chooseSave() = choose(true, listOf("choplab"), if (japanese) "制作を保存" else "Save project", suggestedName("choplab"))?.let(backend.files::register)
    override val outputRevealAvailable get() = outputRevealer.available()
    override fun outputDisplayName(location: Location) = backend.files.resolve(location).fileName.toString()
    override suspend fun revealOutput(location: Location) = outputRevealer.reveal(backend.files.resolve(location))
    override val stemsAvailable = true
    override fun readMixer(target: com.choplab.engine.MixerSnapshot) = backend.engine.status.value.phase == DriverPhase.ATTACHED && backend.engine.copyMixerReadout(target)
    override suspend fun chooseStems(frames: Long): com.choplab.core.StemExportRequest? {
        val path = choose(true, listOf("zip"), if (japanese) "パート別WAVを書き出す" else "Export stems", suggestedName("zip")) ?: return null
        return com.choplab.core.StemExportRequest(backend.files.register(path), Math.toIntExact(frames))
    }
    override suspend fun chooseExport(frames: Long): ExportRequest? {
        val path = choose(true, listOf("wav"), if (japanese) "WAVを書き出す" else "Export WAV", suggestedName("wav")) ?: return null
        return ExportRequest(backend.files.register(path), Math.toIntExact(frames), bits = 24)
    }
    suspend fun confirmCloseWithoutAutosave(): Boolean = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val choice = JOptionPane.showConfirmDialog(parent(),
                if (japanese) "自動保存できませんでした。このまま閉じると、最後に自動保存できた後の変更は失われます。閉じますか？"
                else "Autosave failed. Closing now loses the changes made after the last successful autosave. Close anyway?",
                if (japanese) "おとひろい NEXT" else "Earth Song NEXT", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (answer.isActive) answer.resume(choice == JOptionPane.YES_OPTION)
        }
    }
    private fun suggestedName(extension: String) = nextSuggestedFilename(backend.studio.document.value.project.title, extension, japanese)
    private suspend fun choose(save: Boolean, extensions: List<String>, title: String, suggestedName: String? = null): Path? = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val extension = extensions.first()
            val file = if (isMacOsHost()) {
                val picker = FileDialog(parent() as? Frame, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
                answer.invokeOnCancellation { SwingUtilities.invokeLater { picker.dispose() } }
                try {
                    if (save && suggestedName != null) picker.file = suggestedName
                    picker.filenameFilter = java.io.FilenameFilter { _, name -> extensions.any { name.endsWith(".$it", true) } }
                    picker.isVisible = true
                    picker.files.firstOrNull()?.toPath() ?: picker.file?.let { Path.of(picker.directory, it) }
                } finally { picker.dispose() }
            } else {
                val picker = JFileChooser().apply {
                    dialogTitle = title
                    if (save && suggestedName != null) selectedFile = java.io.File(suggestedName)
                    fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { it.uppercase() }, *extensions.toTypedArray())
                    isAcceptAllFileFilterUsed = false
                }
                answer.invokeOnCancellation { SwingUtilities.invokeLater { picker.cancelSelection() } }
                val result = if (save) picker.showSaveDialog(parent()) else picker.showOpenDialog(parent())
                if (result == JFileChooser.APPROVE_OPTION) picker.selectedFile.toPath() else null
            }
            var selected: Path? = null
            if (file != null && answer.isActive) {
                selected = if (save && !file.fileName.toString().endsWith(".$extension", ignoreCase = true))
                    file.resolveSibling(file.fileName.toString() + ".$extension") else file
                if (save && Files.exists(selected)) {
                    val prompt = JOptionPane(if (japanese) "同じ名前のファイルを置き換えますか？" else "Replace the existing file?",
                        JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION)
                    val dialog = prompt.createDialog(parent(), title)
                    answer.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
                    try { if (answer.isActive) dialog.isVisible = true } finally { dialog.dispose() }
                    if (prompt.value != JOptionPane.YES_OPTION) selected = null
                }
            }
            if (answer.isActive) answer.resume(selected)
        }
    }
}
