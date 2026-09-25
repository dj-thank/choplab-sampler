package com.choplab.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.choplab.desktop.source.DesktopYoutubeBackend
import com.choplab.desktop.source.SpotifyAutoImport
import com.choplab.sampler.source.*
import com.choplab.sampler.ui.AudioSourceHub
import com.choplab.sampler.ui.SpotifySourcePicker
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.WindowsAudioDiagnostics
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.DrumSeparationPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.redoRequestEnabled
import com.choplab.sampler.model.undoRequestEnabled
import com.choplab.sampler.model.visiblePads
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.DocumentAction
import com.choplab.sampler.ui.documentPickerCanceledMessage
import com.choplab.sampler.ui.externalDocumentActionsEnabled
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFileChooser

internal enum class DesktopHistoryAction {
    UNDO,
    REDO,
}

internal fun desktopHistoryActionEnabled(
    state: SamplerUiState,
    action: DesktopHistoryAction,
): Boolean = when (action) {
    DesktopHistoryAction.UNDO -> state.undoRequestEnabled
    DesktopHistoryAction.REDO -> state.redoRequestEnabled
}

fun main(args: Array<String>) {
    applyMacOsHostProperties(desktopAppName())
    runDesktopApplication(args)
}

private fun runDesktopApplication(args: Array<String>) = application {
    val startupFile = remember {
        args.asSequence()
            .map(::File)
            .firstOrNull { file -> file.isFile && file.extension.lowercase() in (LocalAudioLibrary.extensions + "choplab") }
    }
    val player = remember { JavaSoundWavPlayer() }
    val controller = remember {
        DesktopSamplerController(
            player,
            recoverAutosaveOnStart = startupFile == null,
            preserveAutosaveUntilInitialProjectReplacement = startupFile != null,
        )
    }
    val spotify = remember { SpotifyDesktopSession(controller::setStatus) }
    val spotifyState by spotify.state.collectAsState()
    var spotifyPanelVisible by remember { mutableStateOf(false) }
    val sourceHub = remember {
        AudioSourceController(LocalAudioLibrary(File(DesktopProfile.dataDirectory(),"audio-library")) { com.choplab.desktop.source.DesktopAudioDecoder.decode(it);Unit },DesktopYoutubeBackend())
    }
    val sourceState by sourceHub.state.collectAsState()
    val spotifySync = remember { SpotifyAutoImport(spotify.state,sourceHub,spotify::loadImportLibrary) }
    fun disconnectSpotify() { spotifySync.cancel();spotify.disconnect() }
    var sourceHubVisible by remember { mutableStateOf(false) }
    var pendingSourceReplace by remember { mutableStateOf(false) }
    fun openAudioSource(section:SourceSection, replaceProduction:Boolean = false) {
        sourceHub.section(section)
        sourceHub.refresh()
        spotifyPanelVisible=false
        pendingSourceReplace=replaceProduction
        sourceHubVisible=true
    }
    fun pickSourceFiles() {
        openAudioSource(SourceSection.LIBRARY)
        val chooser=importChooser
        lastDocumentDirectory?.let { chooser.currentDirectory=File(it) }
        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
            val files=chooser.selectedFiles.toList()
            files.firstOrNull()?.parentFile?.let { lastDocumentDirectory=it.absolutePath }
            sourceHub.importFiles(files)
        }
    }
    val audioDiagnostics = remember { WindowsAudioDiagnostics(controller::setStatus) }
    val state by controller.state.collectAsState()
    val padKeyOwner = remember { DesktopPadKeyOwner() }
    val closeApplication = {
        padKeyOwner.releaseAll().forEach {
            controller.releasePadIfOwned(it.padIndex, it.ownership)
        }
        spotifySync.close()
        sourceHub.close()
        spotify.close()
        audioDiagnostics.close()
        controller.close()
        exitApplication()
    }
    val currentCloseApplication by rememberUpdatedState(closeApplication)
    DisposableEffect(Unit) {
        // The macOS application menu otherwise exits the JVM without the owned shutdown.
        val desktop = if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
        } else {
            null
        }
        desktop?.setQuitHandler { _, response ->
            response.cancelQuit()
            currentCloseApplication()
        }
        onDispose { desktop?.setQuitHandler(null) }
    }

    LaunchedEffect(startupFile?.absolutePath) {
        startupFile?.let { file ->
            if (file.extension.equals("choplab", ignoreCase = true)) controller.openProject(file) else controller.loadWav(file)
        }
    }

    Window(
        state = remember {
            WindowState(
                placement = WindowPlacement.Maximized,
            )
        },
        onCloseRequest = closeApplication,
        title = desktopAppName(),
        icon = DesktopWindowIcon,
        resizable = true,
        onPreviewKeyEvent = { event ->
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val visiblePads = state.visiblePads()
                    val action = padKeyOwner.press(
                        key = event.key,
                        visiblePadIndices = visiblePads.map { it.globalIndex },
                        playablePadIndices = visiblePads.filter { it.isAssigned }.mapTo(mutableSetOf()) { it.globalIndex },
                        inputEnabled = !sourceHubVisible && !state.isLoading &&
                            !state.sourcePlaying &&
                            state.pendingSourceCommand == PendingSourceCommand.NONE &&
                            state.recordingSession == RecordingSession.Idle,
                        ctrl = event.isCtrlPressed,
                        alt = event.isAltPressed,
                        meta = event.isMetaPressed,
                    )
                    if (action == null) {
                        false
                    } else {
                        controller.selectPlayablePad(action.padIndex)
                        val ownership = controller.triggerPadWithOwnership(action.padIndex)
                        padKeyOwner.bindOwnership(event.key, ownership)
                        true
                    }
                }
                KeyEventType.KeyUp -> {
                    val action = padKeyOwner.release(event.key)
                    if (action == null) {
                        false
                    } else {
                        controller.releasePadIfOwned(action.padIndex, action.ownership)
                        true
                    }
                }
                else -> false
            }
        },
    ) {
        LaunchedEffect(window) {
            // The shared deck needs at least this much room before rows start clipping.
            window.minimumSize = Dimension(MINIMUM_WINDOW_WIDTH_PX, MINIMUM_WINDOW_HEIGHT_PX)
        }
        DisposableEffect(window, padKeyOwner) {
            val focusListener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) {
                    padKeyOwner.releaseAll().forEach {
                        controller.releasePadIfOwned(it.padIndex, it.ownership)
                    }
                }
            }
            window.addWindowFocusListener(focusListener)
            onDispose {
                window.removeWindowFocusListener(focusListener)
                padKeyOwner.releaseAll()
            }
        }
        MenuBar {
            Menu("ファイル") {
                Item(
                    "音源ライブラリを開く",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.OPEN_LIBRARY),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { openAudioSource(SourceSection.LIBRARY) },
                )
                Item(
                    "制作を開く",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.OPEN_PROJECT),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseProject(controller, FileDialog.LOAD) },
                )
                Item(
                    "制作を保存",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.SAVE_PROJECT),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseProject(controller, FileDialog.SAVE) },
                )
                Item(
                    "ビートをWAV書き出し",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.EXPORT_WAV),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseExportWav(controller) },
                )
                Separator()
                Item("終了", shortcut = desktopMenuShortcut(DesktopMenuCommand.QUIT), onClick = closeApplication)
            }
            Menu("編集") {
                Item(
                    "元に戻す",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.UNDO),
                    enabled = desktopHistoryActionEnabled(state, DesktopHistoryAction.UNDO),
                    onClick = controller::undoEdit,
                )
                Item(
                    "やり直す",
                    shortcut = desktopMenuShortcut(DesktopMenuCommand.REDO),
                    enabled = desktopHistoryActionEnabled(state, DesktopHistoryAction.REDO),
                    onClick = controller::redoEdit,
                )
            }
            Menu("トランスポート") {
                Item(
                    if (state.sourcePlaying) "素材を停止" else "素材を再生",
                    shortcut = KeyShortcut(Key.Spacebar),
                    enabled = state.currentAudio != null && !state.isLoading,
                    onClick = controller::toggleSourcePlayback,
                )
                Item(
                    "すべての音を停止",
                    shortcut = KeyShortcut(Key.Escape),
                    onClick = controller::stopAllSounds,
                )
                Separator()
                Item("PADキー  1234 / QWER / ASDF / ZXCV", enabled = false, onClick = {})
            }
            Menu("連携") {
                Item("音源を追加…", onClick = { openAudioSource(SourceSection.LIBRARY) })
                Item("PCのファイルから追加…", onClick = ::pickSourceFiles)
                Separator()
                Item("Spotifyで曲を検索して追加…", onClick = { openAudioSource(SourceSection.SPOTIFY) })
                Item("Spotifyのお気に入りから追加…", onClick = { openAudioSource(SourceSection.SPOTIFY) })
                Item("Spotify コネクトパネル…", onClick = { spotifyPanelVisible = true })
                Separator()
                Item("YouTubeから追加…", onClick = { openAudioSource(SourceSection.YOUTUBE) })
            }
            Menu("診断") {
                Item("Windows 音声エンドポイント", onClick = audioDiagnostics::run)
            }
        }
        fun useLibrary(id:String) {
            if(!externalDocumentActionsEnabled(controller.state.value)) return
            val item=sourceHub.state.value.library.firstOrNull{it.id==id}?:return
            if(pendingSourceReplace) controller.replaceLibrarySource(sourceHub.file(id),item.title)
            else controller.addLibrarySource(sourceHub.file(id),item.title)
            pendingSourceReplace=false
            sourceHub.consumed(id);sourceHubVisible=false
        }
        LaunchedEffect(sourceState.pendingUseId) { sourceState.pendingUseId?.let(::useLibrary) }
        val separation = state.drumSeparation
        LaunchedEffect(separation?.phase, separation?.resultPath) {
            if (separation?.phase == DrumSeparationPhase.DONE && separation.resultPath != null) {
                val stem = controller.consumeDrumSeparationResult()
                if (stem != null) sourceHub.importFiles(listOf(stem))
                controller.discardDrumSeparationWork()
            }
        }
        ChopLabTheme {
            OtohiroiDeck(
                state = state,
                onImportAudio = { openAudioSource(SourceSection.LIBRARY) },
                onReplaceAudio = { openAudioSource(SourceSection.LIBRARY, replaceProduction = true) },
                onSeparateDrums = controller::separateDrumsFromCurrentSource,
                onCancelDrumSeparation = controller::cancelDrumSeparation,
                onToggleMicrophoneRecording = controller::toggleMicrophoneRecording,
                onToggleVocalRecording = controller::toggleVocalRecording,
                onToggleSystemAudioRecording = controller::toggleSystemAudioRecording,
                onExportBeat = { chooseExportWav(controller) },
                onOpenProject = { chooseProject(controller, FileDialog.LOAD) },
                onSaveProject = { chooseProject(controller, FileDialog.SAVE) },
                viewModel = controller,
            )
        }
        if(sourceHubVisible) ChopLabTheme {
            AudioSourceHub(sourceState,externalDocumentActionsEnabled(state),
                sourceHub::section,sourceHub::query,sourceHub::search,sourceHub::download,
                onPickFiles=::pickSourceFiles,
                onUse={id ->
                    if(externalDocumentActionsEnabled(controller.state.value)) {
                        val item=sourceHub.state.value.library.firstOrNull{it.id==id}
                        if(item!=null) {
                            if(pendingSourceReplace) controller.replaceLibrarySource(sourceHub.file(id),item.title)
                            else controller.addLibrarySource(sourceHub.file(id),item.title)
                            pendingSourceReplace=false
                            sourceHub.consumed(id);sourceHubVisible=false
                        }
                    }
                },onCancel={spotifySync.cancel();sourceHub.cancel()},onClose={
                    sourceHub.dismiss()
                    if(spotifyState.canCancelLogin)spotify.cancelLogin()
                    pendingSourceReplace=false
                    sourceHubVisible=false
                },
                spotifyContent={if(spotify.connected) SpotifySearchPanel(
                    spotifyState,sourceState.busy,spotify::setSearchQuery,spotify::searchForImport,spotifySync::addTrack,
                    {sourceHub.section(SourceSection.LIBRARY)},spotifySync::syncAgain,::disconnectSpotify,
                ) else SpotifySourcePicker(
                    SpotifyImportState(connected=spotify.connected,busy=spotifyState.busy,configured=spotifyState.clientIdConfigured,message=spotifyState.message,tracks=spotifyState.sourceTracks,hasMore=spotifyState.sourceHasMore),
                    sourceState.busy,"http://127.0.0.1/callback",
                    onLogin={client ->if(client.isBlank() || spotify.configureClientId(client))spotify.login()},
                    onDisconnect=::disconnectSpotify,onMore=spotifySync::syncAgain,onPick=sourceHub::importFavorite,
                    onOpen={link->java.awt.Desktop.getDesktop().browse(java.net.URI(link))},
                    automaticSync=true,onLibrary={sourceHub.section(SourceSection.LIBRARY)},
                )},
                spotifyLabel="Spotify",
            )
        }

    }
    if (spotifyPanelVisible) {
        Window(
            onCloseRequest = {
                if (spotifyState.canCancelLogin) spotify.cancelLogin()
                spotifyPanelVisible = false
            },
            title = desktopSourceWindowTitle(),
            state = remember { WindowState(width=760.dp,height=660.dp) },
        ) {
            ChopLabTheme {
                SpotifyPanel(spotifyState,sourceState.library.size,::openAudioSource,
                    spotify::showCurrentPlayback,spotify::pause,spotify::resume,::disconnectSpotify,::pickSourceFiles)
            }
        }
    }
}

internal const val MINIMUM_WINDOW_WIDTH_PX = 760
internal const val MINIMUM_WINDOW_HEIGHT_PX = 600

/** Last folder any document picker used, so consecutive open/save dialogs start where the person was. */
private var lastDocumentDirectory: String? = null

/**
 * Building a Swing file chooser walks the Windows shell namespace, which costs seconds on the
 * first open. Keep one instance for the process so later imports open instantly and remember
 * the previous folder.
 */
private val importChooser: JFileChooser by lazy {
    JFileChooser().apply {
        dialogTitle = "ChopLabに音源を追加"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = true
        isAcceptAllFileFilterUsed = false
        fileFilter = DesktopAudioImportPolicy.fileFilter
    }
}

private fun chooseExportWav(controller: DesktopSamplerController) {
    val dialog = FileDialog(null as Frame?, "書き出すWAVの保存先", FileDialog.SAVE).apply {
        lastDocumentDirectory?.let { directory = it }
        file = "choplab-export-4-bars.wav"
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".wav", ignoreCase = true) }
        isVisible = true
    }
    val selected = dialog.file ?: run {
        controller.setStatus(documentPickerCanceledMessage(DocumentAction.EXPORT_WAV))
        return
    }
    dialog.directory?.let { lastDocumentDirectory = it }
    val output = File(dialog.directory, selected).let { file ->
        if (file.extension.equals("wav", ignoreCase = true)) file else File(file.parentFile, "${file.nameWithoutExtension}.wav")
    }
    controller.exportBeat(output)
}

private fun chooseProject(controller: DesktopSamplerController, mode: Int) {
    val saving = mode == FileDialog.SAVE
    val dialog = FileDialog(
        null as Frame?,
        if (saving) "ChopLab制作を保存" else "ChopLab制作を開く",
        mode,
    ).apply {
        lastDocumentDirectory?.let { directory = it }
        if (saving) file = "choplab-project.choplab"
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".choplab", ignoreCase = true) }
        isVisible = true
    }
    val selected = dialog.file ?: run {
        controller.setStatus(
            documentPickerCanceledMessage(
                if (saving) DocumentAction.SAVE_PROJECT else DocumentAction.OPEN_PROJECT,
            ),
        )
        return
    }
    dialog.directory?.let { lastDocumentDirectory = it }
    val file = File(dialog.directory, selected)
    if (saving) controller.saveProject(file) else controller.openProject(file)
}
