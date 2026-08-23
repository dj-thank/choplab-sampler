package com.choplab.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.WindowsAudioDiagnostics
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.FileDialog
import java.awt.Frame
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
        )
    }
    val spotify = remember { SpotifyDesktopSession(controller::setStatus) }
    val audioDiagnostics = remember { WindowsAudioDiagnostics(controller::setStatus) }
    val state by controller.state.collectAsState()

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
        onCloseRequest = {
            spotify.close()
            audioDiagnostics.close()
            controller.close()
            exitApplication()
        },
        title = "ChopLab — おとひろい PC",
        icon = DesktopWindowIcon,
        resizable = true,
    ) {
        MenuBar {
            Menu("連携") {
                Item("Spotify ログイン", onClick = spotify::login)
                Item("Spotify 現在再生を表示", onClick = spotify::showCurrentPlayback)
                Item("Spotify 一時停止", onClick = spotify::pause)
                Item("Spotify 再開", onClick = spotify::resume)
                Separator()
                Item("Spotify 連携解除", onClick = spotify::disconnect)
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
