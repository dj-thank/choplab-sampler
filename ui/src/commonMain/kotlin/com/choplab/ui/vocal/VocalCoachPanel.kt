package com.choplab.ui.vocal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.core.vocal.*
import com.choplab.ui.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable internal fun CEVocalCoachDialog(controller: VocalCoachController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val send = { action: CoachAction -> scope.launch { controller.dispatch(action) }; Unit }
    Dialog({ onAction(ContinuousEditorAction.CloseVocalCoach) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("coach-dialog")) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.coach_title), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("coach-fields"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.coach_boundary))
                    Text(stringResource(Res.string.coach_history))
                    LazyColumn(Modifier.fillMaxWidth().height(144.dp).testTag("coach-takes")) {
                        itemsIndexed(state.takes, key = { _, take -> take.id }) { index, take ->
                            CEButton(stringResource(Res.string.coach_take, index + 1, state.project.asset(take.assetHash).name),
                                { send(CoachAction.Take(take.id)) }, Modifier.fillMaxWidth().semantics { selected = state.takeId == take.id },
                                enabled = state.editable, primary = state.takeId == take.id, tag = "coach-take-${take.id}")
                        }
                    }
                    Text(stringResource(Res.string.coach_reference))
                    CEButton(stringResource(Res.string.coach_no_reference), { send(CoachAction.Reference(null)) }, Modifier.fillMaxWidth(),
                        enabled = state.editable, primary = state.referenceId == null, tag = "coach-reference-none")
                    LazyColumn(Modifier.fillMaxWidth().height(120.dp).testTag("coach-references")) {
                        itemsIndexed(state.takes.filter { it.id != state.takeId }, key = { _, take -> take.id }) { index, take ->
                            CEButton(stringResource(Res.string.coach_take, index + 1, state.project.asset(take.assetHash).name),
                                { send(CoachAction.Reference(take.id)) }, Modifier.fillMaxWidth(), enabled = state.editable,
                                primary = state.referenceId == take.id, tag = "coach-reference-${take.id}")
                        }
                    }
                    for (mode in CoachMode.entries) CEButton(stringResource(if (mode == CoachMode.SINGING) Res.string.coach_singing else Res.string.coach_rap),
                        { send(CoachAction.Mode(mode)) }, Modifier.fillMaxWidth(), enabled = state.editable, primary = state.mode == mode,
                        tag = "coach-mode-${mode.name}")
                    Text(stringResource(Res.string.coach_input_hint))
                    for (input in CoachVoiceInput.entries) CEButton(stringResource(when (input) {
                        CoachVoiceInput.UNCONFIRMED -> Res.string.coach_input_unknown
                        CoachVoiceInput.VOICE_ONLY -> Res.string.coach_input_clean
                        CoachVoiceInput.ACCOMPANIMENT_PRESENT -> Res.string.coach_input_bleed
                    }), { send(CoachAction.Input(input)) }, Modifier.fillMaxWidth(), enabled = state.editable,
                        primary = state.input == input, tag = "coach-input-${input.name}")
                    OutlinedTextField(state.startSeconds, { send(CoachAction.Range(it, state.endSeconds)) }, enabled = state.editable,
                        label = { Text(stringResource(Res.string.practice_start)) }, modifier = Modifier.fillMaxWidth().testTag("coach-start"), singleLine = true)
                    OutlinedTextField(state.endSeconds, { send(CoachAction.Range(state.startSeconds, it)) }, enabled = state.editable,
                        label = { Text(stringResource(Res.string.practice_end)) }, modifier = Modifier.fillMaxWidth().testTag("coach-end"), singleLine = true)
                    CEButton(stringResource(Res.string.coach_analyze), { send(CoachAction.Analyze) }, Modifier.fillMaxWidth(),
                        enabled = state.editable && state.request() != null, tag = "coach-analyze")
                    if (state.phase == CoachPhase.ANALYZING || state.phase == CoachPhase.PREPARING_GUIDE) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.report?.let { report ->
                        report.lines.forEachIndexed { index, line ->
                            CEButton(line.text.ifEmpty { stringResource(Res.string.coach_selected_range) }, { send(CoachAction.SelectLine(index)) },
                                Modifier.fillMaxWidth(), enabled = state.editable, primary = index == state.selectedLine, tag = "coach-line-$index")
                        }
                        report.suggestedLine?.let { line -> Text(stringResource(Res.string.coach_suggested, line.text.ifEmpty { stringResource(Res.string.coach_selected_range) }),
                            Modifier.testTag("coach-suggested")) }
                    }
                    state.line?.let { line ->
                        Column(Modifier.testTag("coach-observations"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            line.onsetFromLineMillis?.let { Text(stringResource(Res.string.coach_onset, it)) }
                            line.onsetDifferenceMillis?.let { Text(stringResource(Res.string.coach_onset_difference, it)) }
                            line.observedPitchHz?.let { Text(stringResource(Res.string.coach_observed_pitch, it)) }
                            line.meanAbsolutePitchCents?.let { Text(stringResource(Res.string.coach_pitch_difference, line.pitchDifferenceCents ?: 0, it)) }
                            if (state.mode == CoachMode.SINGING) Text(stringResource(Res.string.coach_coverage,
                                line.observedPitchHops, line.comparedPitchHops, line.totalHops))
                            line.exclusions.forEach { Text(stringResource(exclusion(it))) }
                            if ((line.onsetDifferenceMillis ?: 0) >= 50) Text(stringResource(Res.string.coach_advice_late, line.onsetDifferenceMillis!!))
                            else if ((line.onsetDifferenceMillis ?: 0) <= -50) Text(stringResource(Res.string.coach_advice_early, abs(line.onsetDifferenceMillis!!)))
                            if ((line.meanAbsolutePitchCents ?: 0) >= 50) Text(stringResource(Res.string.coach_advice_pitch))
                        }
                        CEButton(stringResource(Res.string.coach_practice), { send(CoachAction.Practice) }, Modifier.fillMaxWidth(),
                            enabled = state.editable, tag = "coach-practice")
                        CEButton(stringResource(Res.string.coach_listen_guide), { send(CoachAction.ListenGuide) }, Modifier.fillMaxWidth(),
                            enabled = state.editable, tag = "coach-listen")
                        Text(stringResource(Res.string.coach_response_hint))
                        if (state.phase == CoachPhase.LISTENING) Text(stringResource(Res.string.coach_listening), Modifier.testTag("coach-listening"))
                        if (state.phase == CoachPhase.READY_RESPONSE) Text(stringResource(Res.string.coach_ready_response), Modifier.testTag("coach-ready-response"))
                        CEButton(stringResource(Res.string.coach_respond), { send(CoachAction.Respond) }, Modifier.fillMaxWidth(),
                            enabled = state.editable && state.heardLine == state.selectedLine, tag = "coach-respond")
                    }
                    state.problem?.let { Text(stringResource(problem(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("coach-problem")) }
                    CEButton(stringResource(Res.string.coach_reload), { send(CoachAction.Reload) }, Modifier.fillMaxWidth(),
                        enabled = state.editable, tag = "coach-reload")
                }
                CEButton(stringResource(Res.string.practice_stop), { send(CoachAction.Stop); onAction(ContinuousEditorAction.StopAll) },
                    Modifier.fillMaxWidth(), tag = "coach-stop")
                CEButton(stringResource(Res.string.ce_close), { onAction(ContinuousEditorAction.CloseVocalCoach) }, Modifier.fillMaxWidth(), tag = "coach-close")
            }
        }
    }
}
private fun exclusion(reason: CoachExclusion): StringResource = when (reason) {
    CoachExclusion.NO_REFERENCE -> Res.string.coach_exclusion_reference
    CoachExclusion.RAP_PITCH -> Res.string.coach_exclusion_rap
    CoachExclusion.UNCONFIRMED_VOICE -> Res.string.coach_exclusion_unknown
    CoachExclusion.ACCOMPANIMENT -> Res.string.coach_exclusion_bleed
    CoachExclusion.UNVOICED_OR_UNCERTAIN -> Res.string.coach_exclusion_uncertain
    CoachExclusion.MISSING_TAKE_RANGE -> Res.string.coach_exclusion_missing
    CoachExclusion.PARTIAL_LINE -> Res.string.coach_exclusion_partial
}
private fun problem(value: CoachProblem): StringResource = when (value) {
    CoachProblem.INVALID_INPUT -> Res.string.coach_invalid
    CoachProblem.LIMIT -> Res.string.practice_no_room
    CoachProblem.PCM_UNAVAILABLE -> Res.string.practice_pcm
    CoachProblem.STALE -> Res.string.practice_stale
    CoachProblem.RECORDING -> Res.string.practice_recording
    CoachProblem.BUSY -> Res.string.practice_busy
    CoachProblem.CANCELLED -> Res.string.practice_cancelled
    CoachProblem.NO_GUIDE -> Res.string.coach_no_guide
    CoachProblem.PREVIEW_FAILED -> Res.string.practice_restore_failed
}
