package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.choplab.core.*
import com.choplab.desktop.DesktopProfile
import com.choplab.desktop.applyMacOsHostProperties
import com.choplab.desktop.prepareDesktopOnnxRuntime
import com.choplab.jvm.FileQuickStartStore
import com.choplab.jvm.OutputRecovery
import com.choplab.jvm.closeAfterAutosave
import com.choplab.jvm.ai.*
import com.choplab.jvm.separation.*
import com.choplab.ui.*
import com.choplab.ui.vocal.*
import com.choplab.ui.onboarding.QuickStartController
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.Window as AwtWindow
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Development Preview entry; the existing production/default launcher remains unchanged. */
fun main() {
    check(java.lang.Boolean.getBoolean("choplab.preview")) { "Linked editor requires the isolated Preview profile" }
    val directory = DesktopProfile.dataDirectory(preview = true).toPath().resolve("next-v10")
    val display = NextDisplaySettings(directory.resolve("ui/display.properties"))
    val title = if (Locale.getDefault().language == "ja") "おとひろい NEXT" else "Earth Song NEXT"
    applyMacOsHostProperties(title)
    val backend = startNextWithRecovery(directory) {
        prepareDesktopOnnxRuntime()
        if (java.lang.Boolean.getBoolean("choplab.silentSmoke"))
            NextBackend.create(directory, sinkFactory = { error("Audio disabled for isolated lifecycle verification") }, microphone = { null })
        else NextBackend.create(directory)
    } ?: run { display.close(); return }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val parent = AtomicReference<AwtWindow?>(null)
    val ports = DesktopEditorPorts(backend) { parent.get() }
    val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
    val guideStore = FileQuickStartStore(directory.resolve("ui"))
    val quickStart = QuickStartController(scope, autoShow = backend.studio.document.value.revision == 0L,
        guideStore::completed, guideStore::complete)
    // A device change or a stalled driver drops output to editing-only; bring it back while the window is open.
    val recovery = if (backend.windowsAudio == null) OutputRecovery(backend.engine, scope).apply { start() } else null
    val closedWithoutAutosave = AtomicBoolean(false)
    try {
        application {
            var closing by remember { mutableStateOf(false) }
            val requestClose: () -> Unit = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        val closed = try {
                            presenter.prepareToClose()
                            closeAfterAutosave({ check(presenter.finishRecording()); backend.flushAutosave() },
                                { ports.confirmCloseWithoutAutosave().also { if (it) closedWithoutAutosave.set(true) } }) {
                                presenter.close(); exitApplication()
                            }
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
                        if (!closed) { presenter.cancelClose(); closing = false }
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
            val displayValue by display.preferences.collectAsState()
            val windowTitle = remember(displayValue.language) { if (Locale.getDefault().language == "ja") "おとひろい NEXT" else "Earth Song NEXT" }
            Window(title = windowTitle,
                state = rememberWindowState(width = 1440.dp, height = 1024.dp),
                onCloseRequest = requestClose) {
                NextDisplayEnvironment {
                SideEffect { parent.set(window) }
                DisposableEffect(window) {
                    val dialogOwner = NextDialogOwners.register(window) { presenter.onAction(ContinuousEditorAction.StopAll) }
                    onDispose { dialogOwner.close() }
                }
                var editorDialogOpen by remember { mutableStateOf(false) }
                DisposableEffect(window) {
                    // Java Sound reports no device changes: coming back to the window tries a lost output once more.
                    val focus = object : WindowAdapter() {
                        override fun windowGainedFocus(event: WindowEvent) { editorDialogOpen = false; recovery?.retry() }
                        override fun windowLostFocus(event: WindowEvent) {
                            // A background launch can have no focus event at all. Only an owned dialog
                            // suppresses the editor's commands; switching apps does not change readiness.
                            editorDialogOpen = window.ownedWindows.filterIsInstance<java.awt.Dialog>().any { it.isVisible }
                        }
                    }
                    window.addWindowFocusListener(focus)
                    onDispose { window.removeWindowFocusListener(focus) }
                }
                val state by presenter.state.collectAsState()
                val refresh by presenter.refreshKey.collectAsState()
                val lyricProposal by presenter.lyricProposal.collectAsState()
                val vocalPractice by presenter.vocalPractice.collectAsState()
                val vocalPitch by presenter.vocalPitch.collectAsState()
                val vocalCoach by presenter.vocalCoach.collectAsState()
                val beatStretch by presenter.beatStretch.collectAsState()
                val stepPatterns by presenter.stepPatterns.collectAsState()
                val vocalGuide by presenter.vocalGuide.collectAsState()
                val fourStems by presenter.fourStems.collectAsState()
                val onlineSource by presenter.onlineSource.collectAsState()
                val autoChop by presenter.autoChop.collectAsState()
                val sourceAnalysis by presenter.sourceAnalysis.collectAsState()
                val vocalTakes by presenter.vocalTakes.collectAsState()
                val vocalPunch by presenter.vocalPunch.collectAsState()
                val currentState by rememberUpdatedState(state)
                val currentClosing by rememberUpdatedState(closing)
                val dropHelp = stringResource(Res.string.next_drop_help)
                val dropTitle = stringResource(Res.string.ce_load_audio)
                DisposableEffect(window, dropHelp, dropTitle) {
                    val drop = installNextFileDrop(window, { if (currentClosing) currentState.copy(capabilities = emptySet()) else currentState }, backend.files, {
                        if (!currentClosing) presenter.onAction(it)
                    }, {
                        SwingUtilities.invokeLater { JOptionPane.showMessageDialog(window, dropHelp, dropTitle, JOptionPane.INFORMATION_MESSAGE) }
                    })
                    onDispose { drop.close() }
                }
                MenuBar {
                    NextDesktopMenus(state, closing, onAction = { action ->
                        // A text editor/dialog keeps its own shortcuts and pending edits.
                        if (action == ContinuousEditorAction.StopAll || window.ownedWindows.filterIsInstance<java.awt.Dialog>().none { it.isVisible }) presenter.onAction(action)
                    }, dialogOpen = editorDialogOpen)
                    NextDisplayMenu(display, closing || editorDialogOpen)
                    NextMacAudioMenus(backend, state, closing || editorDialogOpen)
                    backend.windowsAudio?.let { audio ->
                        val route by audio.route.collectAsState()
                        var changing by remember { mutableStateOf(false) }
                        val menu = stringResource(Res.string.next_audio_menu)
                        val wasapi = stringResource(Res.string.next_audio_wasapi)
                        val javaSound = stringResource(Res.string.next_audio_java_sound)
                        val retry = stringResource(Res.string.next_audio_retry)
                        val refusal = stringResource(Res.string.next_audio_refused)
                        val allowed = !changing && !state.recordingVoice && !state.startingVoiceRecording && !state.recordingSource &&
                            !state.startingSourceRecording && !state.recordingSystemAudio && !state.recordingHits && !state.liveChopping && !state.vocalPreview
                        fun choose(next: NextAudioRoute) {
                            if (!allowed) return
                            changing = true
                            scope.launch {
                                try {
                                    if (!backend.chooseAudioRoute(next)) SwingUtilities.invokeLater {
                                        JOptionPane.showMessageDialog(window, refusal, menu, JOptionPane.INFORMATION_MESSAGE)
                                    }
                                } finally { changing = false }
                            }
                        }
                        Menu(menu) {
                            CheckboxItem(wasapi, checked = route == NextAudioRoute.WASAPI, enabled = allowed, onCheckedChange = { choose(NextAudioRoute.WASAPI) })
                            CheckboxItem(javaSound, checked = route == NextAudioRoute.JAVA_SOUND, enabled = allowed, onCheckedChange = { choose(NextAudioRoute.JAVA_SOUND) })
                            Separator()
                            Item(retry, enabled = allowed, onClick = { choose(route) })
                        }
                    }
                }
                ContinuousEditor(state,
                    presenter::onAction, presenter::readout, refresh, diagnostics = presenter::diagnostics, mixerReadout = presenter::readMixer,
                    lyricProposal = lyricProposal, stepPatterns = stepPatterns, vocalGuide = vocalGuide, fourStems = fourStems,
                    autoChop = autoChop, onlineSource = onlineSource, sourceAnalysis = sourceAnalysis, vocalTakes = vocalTakes, vocalPunch = vocalPunch, vocalPractice = vocalPractice, vocalPitch = vocalPitch, vocalCoach = vocalCoach, beatStretch = beatStretch, quickStart = quickStart)
                }
            }
        }
    } finally { quickStart.close(); ports.close(); recovery?.stop(); runBlocking { backend.shutdown(flush = !closedWithoutAutosave.get()) }; scope.cancel(); display.close() }
}
