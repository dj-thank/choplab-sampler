package com.choplab.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.sampler.ui.OtohiroiDeck
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    val player = remember { JavaSoundWavPlayer() }
    val controller = remember { DesktopSamplerController(player) }
    val state by controller.state.collectAsState()

    Window(
        state = remember {
            WindowState(
                width = 740.dp,
                height = 520.dp,
                position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            )
        },
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "ChopLab — おとひろい PC",
        resizable = true,
    ) {
        OtohiroiDeck(
            state = state,
            onImportAudio = { chooseWav(controller) },
            onToggleMicrophoneRecording = controller::toggleMicrophoneRecording,
            onToggleVocalRecording = controller::toggleVocalRecording,
            onToggleSystemAudioRecording = controller::toggleSystemAudioRecording,
            onExportBeat = controller::exportBeat,
            onOpenProject = controller::openProject,
            onSaveProject = controller::saveProject,
            viewModel = controller,
        )
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
