package com.choplab.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.core.analysis.*
import com.choplab.ui.CEButton
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable fun SourceAnalysisDialog(controller: SourceAnalysisController, stop: () -> Unit, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun dispatch(action: SourceAnalysisAction) { scope.launch { controller.dispatch(action) } }
    val canClose = state.phase != SourceAnalysisPhase.APPLYING
    Dialog(onDismissRequest = { if (canClose) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("source-analysis-dialog")) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_stop_all), stop, Modifier.weight(1f), tag = "source-analysis-stop")
                    CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f), enabled = canClose, tag = "source-analysis-close")
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("source-analysis-scroll"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.source_analysis_title), style = MaterialTheme.typography.headlineSmall)
                    Text(state.asset.name)
                    Text(stringResource(Res.string.source_analysis_range, seconds(state.range.start, state.asset.sampleRate),
                        seconds(state.range.end, state.asset.sampleRate), SourceMusicAnalysis.MAX_SECONDS))
                    Text(stringResource(Res.string.source_analysis_local))
                    Button({ dispatch(SourceAnalysisAction.Analyse) }, enabled = state.editable && state.phase != SourceAnalysisPhase.ANALYSING,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("source-analysis-start")) {
                        Text(stringResource(Res.string.source_analysis_start))
                    }
                    if (state.phase == SourceAnalysisPhase.ANALYSING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(Res.string.source_analysis_running))
                        OutlinedButton({ dispatch(SourceAnalysisAction.Cancel) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("source-analysis-cancel")) {
                            Text(stringResource(Res.string.source_analysis_cancel))
                        }
                    }
                    state.result?.let { result ->
                        if (result.frames < SourceMusicAnalysis.MIN_FRAMES) Text(stringResource(Res.string.source_analysis_short))
                        Text(stringResource(Res.string.source_analysis_tempo), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.source_analysis_tempo_hint))
                        if (result.tempos.isEmpty()) Text(stringResource(Res.string.source_analysis_no_tempo), Modifier.testTag("source-analysis-no-tempo"))
                        for (tempo in result.tempos) FilterChip(selected = tempo.milliBpm == state.selectedMilliBpm,
                            onClick = { dispatch(SourceAnalysisAction.SelectTempo(tempo.milliBpm)) }, enabled = state.editable,
                            label = { Text("${bpm(tempo.milliBpm)} BPM") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("source-analysis-tempo-${tempo.milliBpm}"))
                        Button({ dispatch(SourceAnalysisAction.Apply) }, enabled = state.canApply,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("source-analysis-apply")) {
                            Text(stringResource(Res.string.source_analysis_apply))
                        }
                        Text(stringResource(Res.string.source_analysis_key), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.source_analysis_key_hint))
                        if (result.keys.isEmpty()) Text(stringResource(Res.string.source_analysis_no_key), Modifier.testTag("source-analysis-no-key"))
                        for (key in result.keys) Text(stringResource(Res.string.source_analysis_key_value,
                            listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[key.tonic],
                            stringResource(if (key.mode == KeyMode.MAJOR) Res.string.source_analysis_major else Res.string.source_analysis_minor)))
                    }
                    if (state.applied) Text(stringResource(Res.string.source_analysis_applied), Modifier.testTag("source-analysis-applied"))
                    state.problem?.let { problem ->
                        Text(stringResource(when (problem) {
                            SourceAnalysisProblem.STALE -> Res.string.source_analysis_stale
                            SourceAnalysisProblem.BUSY -> Res.string.source_analysis_busy
                            SourceAnalysisProblem.RECORDING -> Res.string.source_analysis_recording
                            SourceAnalysisProblem.FAILED -> Res.string.source_analysis_failed
                            SourceAnalysisProblem.APPLY_FAILED -> Res.string.source_analysis_apply_failed
                        }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("source-analysis-problem"))
                    }
                }
            }
        }
    }
}
private fun seconds(frames: Long, rate: Int): String {
    val tenths = frames * 10 / rate
    return "${tenths / 10}.${tenths % 10}"
}
private fun bpm(milliBpm: Int) = "${milliBpm / 1000}.${milliBpm % 1000 / 100}"
