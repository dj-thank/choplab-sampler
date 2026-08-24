package com.choplab.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
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
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.WindowsAudioDiagnostics
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.visiblePads
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.externalDocumentActionsEnabled
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

fun main(args: Array<String>) = application {
    val startupFile = remember {
        args.asSequence()
            .map(::File)
            .firstOrNull { file -> file.isFile && file.extension.lowercase() in setOf("wav", "choplab") }
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
    val audioDiagnostics = remember { WindowsAudioDiagnostics(controller::setStatus) }
    val state by controller.state.collectAsState()
    val padKeyOwner = remember { DesktopPadKeyOwner() }
    val closeApplication = {
        padKeyOwner.releaseAll().forEach { controller.releasePad(it.padIndex) }
        spotify.close()
        audioDiagnostics.close()
        controller.close()
        exitApplication()
    }

    LaunchedEffect(startupFile?.absolutePath) {
        startupFile?.let { file ->
            if (file.extension.equals("wav", ignoreCase = true)) controller.loadWav(file) else controller.openProject(file)
        }
    }

    Window(
        state = remember {
            WindowState(
                placement = WindowPlacement.Maximized,
            )
        },
        onCloseRequest = closeApplication,
        title = "ChopLab — おとひろい PC",
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
                        inputEnabled = !state.isLoading &&
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
                        controller.triggerPad(action.padIndex)
                        true
                    }
                }
                KeyEventType.KeyUp -> {
                    val action = padKeyOwner.release(event.key)
                    if (action == null) {
                        false
                    } else {
                        controller.releasePad(action.padIndex)
                        true
                    }
                }
                else -> false
            }
        },
    ) {
        DisposableEffect(window, padKeyOwner) {
            val focusListener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) {
                    padKeyOwner.releaseAll().forEach { controller.releasePad(it.padIndex) }
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
                    "WAVを読み込む",
                    shortcut = KeyShortcut(Key.O, ctrl = true),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseWav(controller) },
                )
                Item(
                    "制作を開く",
                    shortcut = KeyShortcut(Key.O, ctrl = true, shift = true),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseProject(controller, FileDialog.LOAD) },
                )
                Item(
                    "制作を保存",
                    shortcut = KeyShortcut(Key.S, ctrl = true),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseProject(controller, FileDialog.SAVE) },
                )
                Item(
                    "ビートをWAV書き出し",
                    shortcut = KeyShortcut(Key.E, ctrl = true),
                    enabled = externalDocumentActionsEnabled(state),
                    onClick = { chooseExportWav(controller) },
                )
                Separator()
                Item("終了", shortcut = KeyShortcut(Key.F4, alt = true), onClick = closeApplication)
            }
            Menu("編集") {
                Item(
                    "元に戻す",
                    shortcut = KeyShortcut(Key.Z, ctrl = true),
                    enabled = state.canUndo,
                    onClick = controller::undoEdit,
                )
                Item(
                    "やり直す",
                    shortcut = KeyShortcut(Key.Y, ctrl = true),
                    enabled = state.canRedo,
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
                Item("Spotify Connect パネル", onClick = { spotifyPanelVisible = true })
                Item("Spotify ログイン", onClick = spotify::login, enabled = spotifyState.canLogin)
                Item("Spotify 認証をキャンセル", onClick = spotify::cancelLogin, enabled = spotifyState.canCancelLogin)
                Item("Spotify 現在再生を表示", onClick = spotify::showCurrentPlayback, enabled = spotifyState.canUsePlaybackControls)
                Item("Spotify ライブラリを表示", onClick = spotify::showLibrary, enabled = spotifyState.canUsePlaybackControls)
                Item("Spotify 一時停止", onClick = spotify::pause, enabled = spotifyState.canUsePlaybackControls)
                Item("Spotify 再開", onClick = spotify::resume, enabled = spotifyState.canUsePlaybackControls)
                Separator()
                Item("Spotify 連携解除", onClick = spotify::disconnect, enabled = spotifyState.canDisconnect)
            }
            Menu("診断") {
                Item("Windows 音声エンドポイント", onClick = audioDiagnostics::run)
            }
        }
        ChopLabTheme {
            OtohiroiDeck(
                state = state,
                onImportAudio = { chooseWav(controller) },
                onToggleMicrophoneRecording = controller::toggleMicrophoneRecording,
                onToggleVocalRecording = controller::toggleVocalRecording,
                onToggleSystemAudioRecording = controller::toggleSystemAudioRecording,
                onExportBeat = { chooseExportWav(controller) },
                onOpenProject = { chooseProject(controller, FileDialog.LOAD) },
                onSaveProject = { chooseProject(controller, FileDialog.SAVE) },
                viewModel = controller,
            )
        }
    }
    if (spotifyPanelVisible) {
        Window(
            onCloseRequest = {
                if (spotifyState.canCancelLogin) spotify.cancelLogin()
                spotifyPanelVisible = false
            },
            title = "ChopLab — Spotify Connect",
        ) {
            ChopLabTheme { SpotifyPanel(spotify, spotifyState) }
        }
    }
}

private fun chooseWav(controller: DesktopSamplerController) {
    val dialog = FileDialog(null as Frame?, "ChopLabで開くWAV", FileDialog.LOAD).apply {
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".wav", ignoreCase = true) }
        isVisible = true
    }
    val file = dialog.file ?: return
    runCatching { controller.loadWav(File(dialog.directory, file)) }
        .onFailure { controller.setStatus("WAV読込失敗: ${it.message ?: it.javaClass.simpleName}") }
}

private fun chooseExportWav(controller: DesktopSamplerController) {
    val dialog = FileDialog(null as Frame?, "書き出すWAVの保存先", FileDialog.SAVE).apply {
        file = "choplab-export-4-bars.wav"
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".wav", ignoreCase = true) }
        isVisible = true
    }
    val selected = dialog.file ?: return
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
        if (saving) file = "choplab-project.choplab"
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".choplab", ignoreCase = true) }
        isVisible = true
    }
    val selected = dialog.file ?: return
    val file = File(dialog.directory, selected)
    if (saving) controller.saveProject(file) else controller.openProject(file)
}
