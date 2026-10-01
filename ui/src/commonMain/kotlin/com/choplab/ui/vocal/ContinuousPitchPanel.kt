@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.choplab.ui.vocal

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
import com.choplab.engine.PitchCorrectionPhase
import com.choplab.engine.PitchScale
import com.choplab.ui.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CEPitchDialog(controller: VocalPitchController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    CEPitchPanel(state, { action -> scope.launch { controller.dispatch(action) } },
        { onAction(ContinuousEditorAction.CloseVocalPitch) }, {
            scope.launch { controller.dispatch(PitchAction.Stop) }
            onAction(ContinuousEditorAction.StopAll)
        })
}

/** One dialog in the existing VOCAL route. All editable content scrolls; Close and Stop stay outside it. */
@Composable internal fun CEPitchPanel(state: PitchEditorState, send: (PitchAction) -> Unit, close: () -> Unit, stop: () -> Unit) {
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("ce-pitch-panel"),
                shape = RoundedCornerShape(12.dp), color = CEColor.Cream, border = BorderStroke(2.dp, CEColor.Ink)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.pitch_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ce-pitch-fields"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.pitch_boundary), fontSize = 14.sp)
                        if (state.clips.isEmpty()) Text(stringResource(Res.string.pitch_no_voice))
                        state.clips.forEachIndexed { index, clip ->
                            CEButton(stringResource(Res.string.pitch_clip, index + 1, state.project.asset(clip.assetHash).name),
                                { send(PitchAction.SelectClip(clip.id)) }, Modifier.fillMaxWidth().semantics { selected = state.clipId == clip.id },
                                enabled = state.editable, primary = state.clipId == clip.id, tag = "ce-pitch-clip-${clip.id}")
                        }
                        Text(stringResource(Res.string.pitch_key), fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B").forEachIndexed { key, label ->
                                CEButton(label, { send(PitchAction.Key(key)) }, Modifier.semantics { selected = state.key == key },
                                    enabled = state.editable, primary = state.key == key, tag = "ce-pitch-key-$key")
                            }
                        }
                        for (scale in PitchScale.entries) {
                            val label = stringResource(when (scale) {
                                PitchScale.CHROMATIC -> Res.string.pitch_chromatic
                                PitchScale.MAJOR -> Res.string.pitch_major
                                PitchScale.NATURAL_MINOR -> Res.string.pitch_minor
                            })
                            CEButton(label, { send(PitchAction.Scale(scale)) }, Modifier.fillMaxWidth().semantics { selected = state.scale == scale },
                                enabled = state.editable, primary = state.scale == scale, tag = "ce-pitch-scale-${scale.name}")
                        }
                        for (field in PitchField.entries) {
                            val label = stringResource(when (field) {
                                PitchField.AMOUNT -> Res.string.pitch_amount
                                PitchField.RETUNE -> Res.string.pitch_retune
                                PitchField.VIBRATO -> Res.string.pitch_vibrato
                            })
                            OutlinedTextField(state.fields.getValue(field), { if (it.length <= 24) send(PitchAction.Field(field, it)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ce-pitch-${field.name.lowercase()}"),
                                label = { Text(label) }, enabled = state.editable, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isError = state.settings() == null)
                        }
                        val ready = state.editable && state.clipId != null && state.settings() != null
                        CEButton(stringResource(Res.string.pitch_prepare), { send(PitchAction.Prepare) }, Modifier.fillMaxWidth(),
                            enabled = ready, tag = "ce-pitch-prepare")
                        state.progressPhase?.let { phase ->
                            Text(stringResource(if (phase == PitchCorrectionPhase.ANALYZE) Res.string.pitch_analyzing else Res.string.pitch_rendering, state.progress),
                                Modifier.testTag("ce-pitch-progress").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        state.report?.let { report ->
                            Text(stringResource(if (report.correctedFrames == 0) Res.string.pitch_unchanged else Res.string.pitch_ready,
                                (report.correctedFrames.toLong() * 100 / report.frames).toInt()), Modifier.testTag("ce-pitch-report"))
                        }
                        CEButton(stringResource(Res.string.pitch_preview_original), { send(PitchAction.PreviewOriginal) }, Modifier.fillMaxWidth(),
                            enabled = ready, primary = state.audition == PitchAudition.ORIGINAL, tag = "ce-pitch-preview-original")
                        CEButton(stringResource(Res.string.pitch_preview_corrected), { send(PitchAction.PreviewCorrected) }, Modifier.fillMaxWidth(),
                            enabled = ready && state.prepared, primary = state.audition == PitchAudition.CORRECTED, tag = "ce-pitch-preview-corrected")
                        CEButton(stringResource(Res.string.pitch_apply), { send(PitchAction.Apply) }, Modifier.fillMaxWidth(),
                            enabled = ready && state.prepared, primary = true, tag = "ce-pitch-apply")
                        state.project.pitchCorrections.firstOrNull { it.clipId == state.clipId }?.let { saved ->
                            val clip = state.clips.firstOrNull { it.id == state.clipId }
                            Text(stringResource(Res.string.pitch_saved))
                            CEButton(stringResource(Res.string.pitch_use_original), { send(PitchAction.SelectSaved(true)) }, Modifier.fillMaxWidth(),
                                enabled = ready && clip?.assetHash != saved.sourceAssetHash, tag = "ce-pitch-use-original")
                            CEButton(stringResource(Res.string.pitch_use_corrected), { send(PitchAction.SelectSaved(false)) }, Modifier.fillMaxWidth(),
                                enabled = ready && clip?.assetHash != saved.renderedAssetHash, tag = "ce-pitch-use-corrected")
                        }
                        if (state.applied) Text(stringResource(Res.string.pitch_applied), Modifier.testTag("ce-pitch-applied").semantics { liveRegion = LiveRegionMode.Polite })
                        state.problem?.let { problem -> Text(stringResource(when (problem) {
                            PitchEditorProblem.NO_VOICE -> Res.string.pitch_no_voice
                            PitchEditorProblem.INVALID_INPUT -> Res.string.pitch_invalid
                            PitchEditorProblem.STALE -> Res.string.pitch_stale
                            PitchEditorProblem.BUSY -> Res.string.pitch_busy
                            PitchEditorProblem.RECORDING -> Res.string.pitch_recording
                            PitchEditorProblem.LIMIT -> Res.string.pitch_limit
                            PitchEditorProblem.PREVIEW_FAILED -> Res.string.pitch_preview_failed
                            PitchEditorProblem.APPLY_FAILED, PitchEditorProblem.FAILED -> Res.string.pitch_failed
                        }), Modifier.testTag("ce-pitch-problem").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
                        CEButton(stringResource(Res.string.pitch_reload), { send(PitchAction.Reload) }, Modifier.fillMaxWidth(),
                            enabled = state.phase == PitchEditorPhase.EDITING, tag = "ce-pitch-reload")
                        CEButton(stringResource(Res.string.pitch_cancel), { send(PitchAction.Cancel) }, Modifier.fillMaxWidth(),
                            enabled = state.phase != PitchEditorPhase.APPLYING, tag = "ce-pitch-cancel")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f), tag = "ce-pitch-close")
                        CEButton(stringResource(Res.string.ce_stop_all), stop, Modifier.weight(1f), tag = "ce-pitch-stop")
                    }
                }
            }
        }
    }
}
