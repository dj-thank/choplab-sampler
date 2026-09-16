package com.choplab.sampler.ui

import androidx.compose.runtime.Composable
import com.choplab.sampler.SamplerViewModel
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
