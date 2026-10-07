package com.choplab.ui.vocal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.choplab.core.vocal.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable fun VocalPunchPanel(controller: VocalPunchController, onChoices: () -> Unit, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val progress by controller.progress.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.closed) { if (state.closed) onClose() }
    Surface(Modifier.fillMaxSize().testTag("vocal-punch")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("punch-fields"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(stringResource(Res.string.punch_title), style = MaterialTheme.typography.headlineSmall); Text(stringResource(Res.string.punch_commit_help)) }
                item { OutlinedTextField(state.startSeconds, { value -> controller.update { it.copy(startSeconds = value) } }, enabled = !state.busy,
                    label = { Text(stringResource(Res.string.punch_start)) }, modifier = Modifier.fillMaxWidth().testTag("punch-start"), singleLine = true) }
                item { OutlinedTextField(state.endSeconds, { value -> controller.update { it.copy(endSeconds = value) } }, enabled = !state.busy,
                    label = { Text(stringResource(Res.string.punch_end)) }, modifier = Modifier.fillMaxWidth().testTag("punch-end"), singleLine = true) }
                item { OutlinedButton({ controller.update { it.copy(preRollBars = (it.preRollBars + 1) % 3) } }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-preroll")) { Text(stringResource(Res.string.punch_preroll, state.preRollBars)) } }
                item { OutlinedButton({ controller.update { it.copy(countInBars = (it.countInBars + 1) % 3) } }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-countin")) { Text(stringResource(Res.string.punch_countin, state.countInBars)) } }
                item { OutlinedButton({ controller.update { it.copy(passes = it.passes % 8 + 1) } }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-passes")) { Text(stringResource(Res.string.punch_passes, state.passes)) }
                    Text(stringResource(Res.string.punch_loop_help)) }
                item { OutlinedTextField(state.manualMillis, { value -> controller.update { it.copy(manualMillis = value) } }, enabled = !state.busy,
                    label = { Text(stringResource(Res.string.punch_manual)) }, modifier = Modifier.fillMaxWidth().testTag("punch-manual"), singleLine = true)
                    Text(stringResource(Res.string.punch_unmeasured)) }
                item {
                    progress.alignment.route?.let { route -> Text(stringResource(Res.string.punch_route, route.inputRate, route.inputBufferFrames?.toString() ?: "?", route.outputRate, route.outputBufferFrames?.toString() ?: "?")) }
                    Text(stringResource(when (progress.alignment.status) {
                        AlignmentStatus.INVALIDATED -> Res.string.punch_invalidated
                        AlignmentStatus.MANUAL -> Res.string.punch_manual_active
                        else -> Res.string.punch_estimated
                    }))
                    state.problem?.let { Text(stringResource(punchProblem(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("punch-problem")) }
                    if (state.saved > 0) Text(stringResource(Res.string.punch_saved, state.saved), modifier = Modifier.testTag("punch-saved"))
                    if (state.saved > 0) OutlinedButton(onChoices, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-choices")) { Text(stringResource(Res.string.punch_choices)) }
                }
            }
            // Progress stays visible while the configuration fields scroll.
            if (state.busy) {
                Text(stringResource(when {
                    state.closing -> Res.string.ce_punch_closing
                    state.stopping -> Res.string.ce_punch_stopping
                    state.saving -> Res.string.ce_punch_saving
                    else -> when (progress.phase) {
                        PunchPhase.OPENING -> Res.string.ce_punch_opening
                        PunchPhase.PRE_ROLL -> Res.string.ce_punch_preroll
                        PunchPhase.CAPTURING -> Res.string.ce_punch_capturing
                        PunchPhase.SAVING -> Res.string.ce_punch_saving
                        PunchPhase.IDLE -> Res.string.ce_punch_waiting
                    }
                }), Modifier.testTag("punch-phase").semantics { liveRegion = LiveRegionMode.Polite })
                if (progress.pass > 0) Text(stringResource(Res.string.punch_progress, progress.pass, progress.total))
            }
            Button({ scope.launch { controller.record() } }, enabled = !state.busy && !state.closed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-record")) { Text(stringResource(Res.string.punch_record)) }
            OutlinedButton(controller::stop, enabled = state.busy && !state.stopping && !state.saving && progress.phase != PunchPhase.SAVING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-stop")) { Text(stringResource(Res.string.punch_stop)) }
            OutlinedButton(controller::requestClose, enabled = !state.closing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("punch-close")) { Text(stringResource(Res.string.punch_close)) }
        }
    }
}
private fun punchProblem(problem: PunchProblem) = when (problem) {
    PunchProblem.PERMISSION -> Res.string.punch_permission
    PunchProblem.NO_INPUT -> Res.string.punch_no_input
    PunchProblem.NO_OUTPUT -> Res.string.punch_no_output
    PunchProblem.NO_ROOM -> Res.string.punch_no_room
    PunchProblem.STALE -> Res.string.punch_stale
    PunchProblem.INTERRUPTED -> Res.string.punch_interrupted
    PunchProblem.EMPTY -> Res.string.punch_empty
    PunchProblem.SAVE_FAILED -> Res.string.punch_save_failed
    PunchProblem.CANCELLED -> Res.string.punch_cancelled
    PunchProblem.BUSY -> Res.string.punch_busy
    PunchProblem.CUE -> Res.string.punch_cue
    PunchProblem.INVALID -> Res.string.punch_invalid
}
