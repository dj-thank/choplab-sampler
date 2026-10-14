package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Waveform availability is independent of audio silence and never changes the document. */
@Composable internal fun CEWaveformStatus(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    val failed = state.assetWaveforms.count { it.value == WaveformLoadState.FAILED }
    val loading = state.assetWaveforms.count { it.value == WaveformLoadState.LOADING }
    var open by remember { mutableStateOf(false) }
    if (failed > 0) CEButton(stringResource(Res.string.ce_waveforms_failed, failed), { open = true },
        Modifier.fillMaxWidth(), tag = "ce-waveforms-failed")
    else if (loading > 0) Text(stringResource(Res.string.ce_waveforms_loading, loading), Modifier.testTag("ce-waveforms-loading"))
    if (open) AlertDialog(onDismissRequest = { open = false }, modifier = Modifier.testTag("ce-waveforms-dialog"),
        title = { Text(stringResource(Res.string.ce_waveforms_title)) }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val pending = state.assetWaveforms.entries.filter { it.value != WaveformLoadState.READY }
                if (pending.isEmpty()) item { Text(stringResource(Res.string.ce_waveforms_ready), Modifier.testTag("ce-waveforms-ready")) }
                items(pending, key = { it.key }) { (hash, phase) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val name = state.assetWaveformNames[hash]?.takeIf(String::isNotBlank) ?: stringResource(Res.string.ce_waveform_unnamed)
                        Text(name, Modifier.testTag("ce-waveform-name-$hash"))
                        CEButton(stringResource(if (phase == WaveformLoadState.FAILED) Res.string.ce_waveform_retry else Res.string.ce_waveform_preparing),
                            { onAction(ContinuousEditorAction.RetryWaveforms(hash)) }, Modifier.fillMaxWidth(),
                            enabled = phase == WaveformLoadState.FAILED, tag = "ce-waveform-retry-$hash")
                    }
                }
            }
        }, dismissButton = { CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) }, tag = "ce-waveforms-stop") },
        confirmButton = { CEButton(stringResource(Res.string.ce_close), { open = false }, tag = "ce-waveforms-close") })
}
