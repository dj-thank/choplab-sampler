@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.choplab.ui.stretch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.core.edit.StretchProblem
import com.choplab.ui.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CEStretchDialog(controller: BeatStretchController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val send: (StretchAction) -> Unit = { scope.launch { controller.dispatch(it) } }
    Dialog({ onAction(ContinuousEditorAction.CloseBeatStretch) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("ce-stretch-panel"),
                shape = RoundedCornerShape(12.dp), color = CEColor.Cream, border = BorderStroke(2.dp, CEColor.Ink)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.stretch_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ce-stretch-fields"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.name, fontWeight = FontWeight.Bold)
                        Text(stringResource(Res.string.stretch_boundary), fontSize = 14.sp)
                        OutlinedTextField(state.sourceBpm, { send(StretchAction.Bpm(it)) }, label = { Text(stringResource(Res.string.stretch_source_bpm)) },
                            singleLine = true, enabled = state.editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ce-stretch-bpm"), isError = state.sourceBpm.isNotEmpty() && state.milliBpm() == null)
                        Text(stringResource(Res.string.stretch_target_bpm, (state.project.tempo.milliBpm / 1000.0).toString()), Modifier.testTag("ce-stretch-target"))
                        state.milliBpm()?.let { source -> Text(stringResource(Res.string.stretch_ratio,
                            ((source.toLong() * 1000 / state.project.tempo.milliBpm) / 1000.0).toString())) }
                        state.saved?.let { old -> Text(stringResource(Res.string.stretch_saved,
                            (old.sourceMilliBpm / 1000.0).toString(), (old.targetMilliBpm / 1000.0).toString()), Modifier.testTag("ce-stretch-saved")) }
                        val ready = state.editable && state.milliBpm() != null
                        CEButton(stringResource(Res.string.stretch_prepare), { send(StretchAction.Prepare) }, Modifier.fillMaxWidth(),
                            enabled = ready, tag = "ce-stretch-prepare")
                        if (state.phase == StretchPhase.PREPARING) Text(stringResource(Res.string.stretch_progress, state.progress),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        CEButton(stringResource(Res.string.stretch_original), { send(StretchAction.Original) }, Modifier.fillMaxWidth(),
                            enabled = ready, primary = state.audition == StretchAudition.ORIGINAL, tag = "ce-stretch-original")
                        CEButton(stringResource(Res.string.stretch_processed), { send(StretchAction.Stretched) }, Modifier.fillMaxWidth(),
                            enabled = ready && state.prepared, primary = state.audition == StretchAudition.STRETCHED, tag = "ce-stretch-processed")
                        CEButton(stringResource(Res.string.stretch_apply), { send(StretchAction.Apply) }, Modifier.fillMaxWidth(),
                            enabled = ready && state.prepared, primary = true, tag = "ce-stretch-apply")
                        if (state.applied) Text(stringResource(Res.string.stretch_applied), Modifier.testTag("ce-stretch-applied").semantics { liveRegion = LiveRegionMode.Polite })
                        state.problem?.let { problem -> Text(stringResource(when (problem) {
                            StretchProblem.STALE -> Res.string.stretch_stale
                            StretchProblem.NO_TARGET -> Res.string.stretch_no_target
                            StretchProblem.INVALID_INPUT -> Res.string.stretch_invalid
                            StretchProblem.LIMIT -> Res.string.stretch_limit
                            StretchProblem.RECORDING -> Res.string.stretch_recording
                            StretchProblem.BUSY -> Res.string.stretch_busy
                            StretchProblem.PREVIEW_FAILED -> Res.string.stretch_preview_failed
                            StretchProblem.FAILED -> Res.string.stretch_failed
                        }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ce-stretch-problem").semantics { liveRegion = LiveRegionMode.Polite }) }
                        CEButton(stringResource(Res.string.stretch_reload), { send(StretchAction.Reload) }, Modifier.fillMaxWidth(),
                            enabled = state.phase == StretchPhase.EDITING, tag = "ce-stretch-reload")
                    }
                    CEButton(stringResource(Res.string.stretch_cancel), { send(StretchAction.Cancel) }, Modifier.fillMaxWidth(),
                        enabled = state.phase != StretchPhase.APPLYING, tag = "ce-stretch-cancel")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.ce_close), { onAction(ContinuousEditorAction.CloseBeatStretch) }, Modifier.weight(1f), tag = "ce-stretch-close")
                        CEButton(stringResource(Res.string.ce_stop_all), { send(StretchAction.Cancel); onAction(ContinuousEditorAction.StopAll) }, Modifier.weight(1f), tag = "ce-stretch-stop")
                    }
                }
            }
        }
    }
}
