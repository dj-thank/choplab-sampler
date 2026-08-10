package com.choplab.sampler.ui

import androidx.compose.runtime.Composable
import com.choplab.sampler.SamplerViewModel
import com.choplab.sampler.model.SamplerUiState

@Composable
fun SamplerScreen(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
) {
    OtohiroiDeck(
        state = state,
        onImportAudio = onImportAudio,
        onToggleMicrophoneRecording = onToggleMicrophoneRecording,
        onToggleSystemAudioRecording = onToggleSystemAudioRecording,
        onExportBeat = onExportBeat,
        viewModel = viewModel,
    )
}
