package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.desktop.DesktopProfile
import com.choplab.desktop.applyMacOsHostProperties
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.Window as AwtWindow
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
    val title = if (Locale.getDefault().language == "ja") "おとひろい Preview" else "Earth Song Preview"
    applyMacOsHostProperties(title)
    val directory = DesktopProfile.dataDirectory(preview = true).toPath().resolve("next-v10")
    val backend = if (java.lang.Boolean.getBoolean("choplab.silentSmoke"))
        NextBackend.create(directory, sinkFactory = { error("Audio disabled for isolated lifecycle verification") })
        else NextBackend.create(directory)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val parent = AtomicReference<AwtWindow?>(null)
    val ports = DesktopEditorPorts(backend) { parent.get() }
    val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
    val closedWithoutAutosave = AtomicBoolean(false)
    try {
        application {
            var closing by remember { mutableStateOf(false) }
            val requestClose: () -> Unit = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        val closed = try {
                            closeAfterAutosave(backend::flushAutosave,
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
                val state by presenter.state.collectAsState()
                val refresh by presenter.refreshKey.collectAsState()
                val failed by backend.persistenceFailure.collectAsState()
                ContinuousEditor(if (failed) state.copy(status = ContinuousStatus.FAILED) else state,
                    presenter::onAction, presenter::readout, refresh)
            }
        }
    } finally { runBlocking { backend.shutdown(flush = !closedWithoutAutosave.get()) }; scope.cancel() }
}

/**
 * A failing autosave (full or read-only disk) must never trap the window open. After a failed
 * final save the user decides; declining keeps the window and its unsaved work.
 */
internal suspend fun closeAfterAutosave(flush: suspend () -> Unit, confirmWithoutAutosave: suspend () -> Boolean,
                                        finish: suspend () -> Unit): Boolean {
    val saved = try { flush(); true } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
    if (!saved && !confirmWithoutAutosave()) return false
    finish()
    return true
}

private class DesktopEditorPorts(private val backend: NextBackend, private val parent: () -> AwtWindow?) : ContinuousEditorPorts {
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
    override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
    override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.playback().sequenceRenderFrames)
    override suspend fun peaks(asset: Asset) = backend.loadPeaks(asset)
    override suspend fun chooseAudio() = choose(false, "wav", if (japanese) "音源を開く" else "Open audio")?.let(backend.files::register)
    override suspend fun chooseOpen() = choose(false, "choplab", if (japanese) "制作を開く" else "Open project")?.let(backend.files::register)
    override suspend fun chooseSave() = choose(true, "choplab", if (japanese) "制作を保存" else "Save project")?.let(backend.files::register)
    override suspend fun chooseExport(frames: Long): ExportRequest? {
        val path = choose(true, "wav", if (japanese) "WAVを書き出す" else "Export WAV") ?: return null
        return ExportRequest(backend.files.register(path), Math.toIntExact(frames), bits = 24)
    }
    suspend fun confirmCloseWithoutAutosave(): Boolean = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val choice = JOptionPane.showConfirmDialog(parent(),
                if (japanese) "自動保存できませんでした。このまま閉じると、最後に自動保存できた後の変更は失われます。閉じますか？"
                else "Autosave failed. Closing now loses the changes made after the last successful autosave. Close anyway?",
                if (japanese) "おとひろい Preview" else "Earth Song Preview", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (answer.isActive) answer.resume(choice == JOptionPane.YES_OPTION)
        }
    }
    private suspend fun choose(save: Boolean, extension: String, title: String): Path? = suspendCancellableCoroutine { answer ->
        SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val picker = JFileChooser().apply {
                dialogTitle = title
                fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
                isAcceptAllFileFilterUsed = false
            }
            val result = if (save) picker.showSaveDialog(parent()) else picker.showOpenDialog(parent())
            var selected: Path? = null
            if (result == JFileChooser.APPROVE_OPTION) {
                val file = picker.selectedFile.toPath()
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
