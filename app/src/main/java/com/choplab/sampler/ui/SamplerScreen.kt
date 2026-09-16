package com.choplab.sampler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.choplab.sampler.SamplerViewModel
import com.choplab.sampler.midi.Ddj200Controls
import com.choplab.sampler.model.SamplerUiState

@Composable
fun SamplerScreen(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onReplaceAudio: () -> Unit = onImportAudio,
    onSeparateDrums: (() -> Unit)? = null,
    onCancelDrumSeparation: (() -> Unit)? = null,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleVocalRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onExportBeat: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    viewModel: SamplerViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        Ddj200Controls(viewModel)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OtohiroiDeck(
                state = state,
                onImportAudio = onImportAudio,
                onReplaceAudio = onReplaceAudio,
                onSeparateDrums = onSeparateDrums,
                onCancelDrumSeparation = onCancelDrumSeparation,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                onToggleVocalRecording = onToggleVocalRecording,
                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                onExportBeat = onExportBeat,
                onOpenProject = onOpenProject,
                onSaveProject = onSaveProject,
                viewModel = viewModel,
            )
        }
    }
}
