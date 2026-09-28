package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Pad
import com.choplab.desktop.DesktopProfile
import com.choplab.desktop.applyMacOsHostProperties
import com.choplab.desktop.isMacOsHost
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.SpotifySessionPurpose
import com.choplab.jvm.OriginalAudioImportPort
import com.choplab.jvm.OutputRecovery
import com.choplab.jvm.VoiceTakes
import com.choplab.jvm.closeAfterAutosave
import com.choplab.jvm.ai.*
import com.choplab.ui.*
import com.choplab.ui.ai.LyricProposalPort
import com.choplab.ui.ai.VocalGuidePort
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

/** Development Preview entry; the existing production/default launcher remains unchanged. */
fun main() {
    check(java.lang.Boolean.getBoolean("choplab.preview")) { "Linked editor requires the isolated Preview profile" }
    val title = if (Locale.getDefault().language == "ja") "おとひろい NEXT" else "Earth Song NEXT"
    applyMacOsHostProperties(title)
    val directory = DesktopProfile.dataDirectory(preview = true).toPath().resolve("next-v10")
    val backend = if (java.lang.Boolean.getBoolean("choplab.silentSmoke"))
        NextBackend.create(directory, sinkFactory = { error("Audio disabled for isolated lifecycle verification") }, microphone = { null })
        else NextBackend.create(directory)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val parent = AtomicReference<AwtWindow?>(null)
    val ports = DesktopEditorPorts(backend) { parent.get() }
    val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
    // A device change or a stalled driver drops output to editing-only; bring it back while the window is open.
    val recovery = OutputRecovery(backend.engine, scope).apply { start() }
    val closedWithoutAutosave = AtomicBoolean(false)
    try {
        application {
            var closing by remember { mutableStateOf(false) }
            val requestClose: () -> Unit = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        val closed = try {
                            closeAfterAutosave({ check(presenter.finishRecording()); backend.flushAutosave() },
                                { ports.confirmCloseWithoutAutosave().also { if (it) closedWithoutAutosave.set(true) } }) {
                                presenter.close(); exitApplication()
                            }
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
                        if (!closed) closing = false
                    }
                }
            }
            val currentRequestClose by rememberUpdatedState(requestClose)
            DisposableEffect(Unit) {
                // Command-Q in the macOS application menu otherwise exits the JVM before the final autosave.
                val desktop = if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
                } else {
                    null
                }
                desktop?.setQuitHandler { _, response ->
                    response.cancelQuit()
                    currentRequestClose()
                }
                onDispose { desktop?.setQuitHandler(null) }
            }
            Window(title = title,
                state = rememberWindowState(width = 1440.dp, height = 1024.dp),
                onCloseRequest = requestClose) {
                SideEffect { parent.set(window) }
                DisposableEffect(window) {
                    // Java Sound reports no device changes: coming back to the window tries a lost output once more.
                    val focus = object : WindowAdapter() {
                        override fun windowGainedFocus(event: WindowEvent) { recovery.retry() }
                    }
                    window.addWindowFocusListener(focus)
                    onDispose { window.removeWindowFocusListener(focus) }
                }
                val state by presenter.state.collectAsState()
                val refresh by presenter.refreshKey.collectAsState()
                val lyricProposal by presenter.lyricProposal.collectAsState()
                val stepPatterns by presenter.stepPatterns.collectAsState()
                val vocalGuide by presenter.vocalGuide.collectAsState()
                val failed by backend.persistenceFailure.collectAsState()
                ContinuousEditor(if (failed) state.copy(status = ContinuousStatus.FAILED) else state,
                    presenter::onAction, presenter::readout, refresh, diagnostics = presenter::diagnostics,
                    lyricProposal = lyricProposal, stepPatterns = stepPatterns, vocalGuide = vocalGuide)
            }
        }
    } finally { ports.close(); recovery.stop(); runBlocking { backend.shutdown(flush = !closedWithoutAutosave.get()) }; scope.cancel() }
}

internal class DesktopEditorPorts(
    private val backend: NextBackend,
    private val spotify: SpotifyDesktopSession = SpotifyDesktopSession(onStatus = {}, purpose = SpotifySessionPurpose.METADATA_ONLY),
    private val parent: () -> AwtWindow?,
) : ContinuousEditorPorts, AutoCloseable {
    init { require(spotify.purpose == SpotifySessionPurpose.METADATA_ONLY) }
    override val spotifyMetadataAvailable = true
    override suspend fun openSpotifyMetadata() = NextSpotifyDialog.show(parent(), spotify)
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speechPreview = SourceVocalPreview(backend.studio, backend.engine, backend.audition, speechScope)
    override val vocalGuide: VocalGuidePort = object : VocalGuidePort {
        override val preview = speechPreview
        override fun createSynthesis(): com.choplab.core.ai.VocalSynthesisPort {
            val directory = backend.assets.directory.parent.resolve("vocal-guide")
            return VocalTtsService(DesktopTtsProvider(directory.resolve("temporary")), TtsCache(directory.resolve("cache")), backend.assets)
        }
    }
    override fun close() { try { runBlocking { speechPreview.close() } } finally { speechScope.cancel(); spotify.close() } }
    override val lyricProposal: LyricProposalPort = object : LyricProposalPort {
        override fun createProvider() = GeminiLyricProvider()
    }
    override val lyricFiles: LyricFiles = DesktopLyricFiles { save ->
        choose(save, listOf("lrc"), if (japanese) { if (save) "歌詞を書き出す" else "歌詞を読み込む" }
            else { if (save) "Export lyrics" else "Import lyrics" })
    }
    override val systemAudioCapture get() = backend.systemAudio
    private val japanese get() = Locale.getDefault().language == "ja"
    override val originalAvailable get() = backend.engine.status.value.phase == DriverPhase.ATTACHED
    override fun originalPlaying() = backend.engine.originalPlayback().playing
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
    override suspend fun renderPad(pad: Pad, source: Asset) = backend.renderPad(pad, source)
    override suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int?) =
        backend.renderPerformance(pad, source, releaseAt, limitFrames, stopAt)
    override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
    override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.playback().sequenceRenderFrames,
        handSourceFrame = backend.audition.nativeHandFrame(), countInBeatsRemaining = backend.engine.snapshot().countInBeatsRemaining,
        pcm = pcmReadout())
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
        VoiceTakes.Start.NO_INPUT -> VoiceStart.UNAVAILABLE
    }
    override fun cueVoice() = backend.voice.cue()
    override val recordingCue: RecordingCuePort = object : RecordingCuePort {
        override suspend fun startArmedVoice(maxSeconds: Int) = when (backend.voice.start(maxSeconds, waitForCue = true)) {
            VoiceTakes.Start.STARTED -> VoiceStart.STARTED
            VoiceTakes.Start.NO_ROOM -> VoiceStart.NO_ROOM
            VoiceTakes.Start.NO_INPUT -> VoiceStart.UNAVAILABLE
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
    override suspend fun chooseOnline() = NextOnlineDialog.choose(parent(),
        DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library"), backend::validateLibraryFile)?.let { backend.files.registerNamed(it.path, it.title, it.hash) }
    override val libraryAvailable = true
    override suspend fun chooseLibrary() = NextLibraryDialog.choose(parent(),
        DesktopProfile.dataDirectory(preview = true).toPath().resolve("audio-library"), backend::validateLibraryFile)?.let { backend.files.registerNamed(it.path, it.title, it.hash) }
    override suspend fun chooseAudio() = choose(false, OriginalAudioImportPort.EXTENSIONS, if (japanese) "音源を開く" else "Open audio")?.let(backend.files::register)
    override suspend fun chooseOpen() = choose(false, listOf("choplab"), if (japanese) "制作を開く" else "Open project")?.let(backend.files::register)
    override suspend fun chooseSave() = choose(true, listOf("choplab"), if (japanese) "制作を保存" else "Save project")?.let(backend.files::register)
    override suspend fun chooseExport(frames: Long): ExportRequest? {
        val path = choose(true, listOf("wav"), if (japanese) "WAVを書き出す" else "Export WAV") ?: return null
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
    private suspend fun choose(save: Boolean, extensions: List<String>, title: String): Path? = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val extension = extensions.first()
            val file = if (isMacOsHost()) {
                val picker = FileDialog(parent() as? Frame, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
                answer.invokeOnCancellation { SwingUtilities.invokeLater { picker.dispose() } }
                try {
                    picker.filenameFilter = java.io.FilenameFilter { _, name -> extensions.any { name.endsWith(".$it", true) } }
                    picker.isVisible = true
                    picker.files.firstOrNull()?.toPath() ?: picker.file?.let { Path.of(picker.directory, it) }
                } finally { picker.dispose() }
            } else {
                val picker = JFileChooser().apply {
                    dialogTitle = title
                    fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { it.uppercase() }, *extensions.toTypedArray())
                    isAcceptAllFileFilterUsed = false
                }
                answer.invokeOnCancellation { SwingUtilities.invokeLater { picker.cancelSelection() } }
                val result = if (save) picker.showSaveDialog(parent()) else picker.showOpenDialog(parent())
                if (result == JFileChooser.APPROVE_OPTION) picker.selectedFile.toPath() else null
            }
            var selected: Path? = null
            if (file != null) {
                selected = if (save && !file.fileName.toString().endsWith(".$extension", ignoreCase = true))
                    file.resolveSibling(file.fileName.toString() + ".$extension") else file
                if (save && Files.exists(selected)) {
                    val overwrite = JOptionPane.showConfirmDialog(parent(),
                        if (japanese) "同じ名前のファイルを置き換えますか？" else "Replace the existing file?",
                        title, JOptionPane.YES_NO_OPTION)
                    if (overwrite != JOptionPane.YES_OPTION) selected = null
                }
            }
            if (answer.isActive) answer.resume(selected)
        }
    }
}
