package com.choplab.ui.vocal

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
import com.choplab.core.vocal.PracticeProblem
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Stop and Close stay outside the scrolling fields, including 390dp at font scale 2. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun VocalPracticeDialog(controller: VocalPracticeController, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val editable = state.phase == PracticePhase.EDITING && !state.closing
    DisposableEffect(controller) { onDispose { controller.close() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("practice-dialog")) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("practice-fields"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.practice_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(Res.string.practice_hint))
                    OutlinedTextField(state.startSeconds, { text -> controller.update { it.copy(startSeconds = text.take(16)) } },
                        enabled = editable, singleLine = true, label = { Text(stringResource(Res.string.practice_start)) },
                        modifier = Modifier.fillMaxWidth().testTag("practice-start"))
                    OutlinedTextField(state.endSeconds, { text -> controller.update { it.copy(endSeconds = text.take(16)) } },
                        enabled = editable, singleLine = true, label = { Text(stringResource(Res.string.practice_end)) },
                        modifier = Modifier.fillMaxWidth().testTag("practice-end"))
                    Text(stringResource(Res.string.practice_speed))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (speed in listOf(.6, .8, 1.0, 1.2, 1.6)) FilterChip(state.speed == speed,
                            { controller.update { it.copy(speed = speed) } }, enabled = editable,
                            label = { Text(stringResource(Res.string.practice_speed_value, speed.toString())) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("practice-speed-${(speed * 100).toInt()}"))
                    }
                    FilterChip(state.loop, { controller.update { it.copy(loop = !it.loop) } }, enabled = editable,
                        label = { Text(stringResource(Res.string.practice_loop)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-loop"))
                    Text(stringResource(Res.string.practice_limit), style = MaterialTheme.typography.bodySmall)
                    Button({ scope.launch { controller.preview() } }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-preview")) { Text(stringResource(Res.string.practice_preview)) }
                    if (state.phase != PracticePhase.EDITING && state.phase != PracticePhase.CLOSED) {
                        Text(stringResource(when (state.phase) {
                            PracticePhase.PREPARING -> Res.string.practice_preparing
                            PracticePhase.PLAYING -> Res.string.practice_playing
                            else -> Res.string.practice_stopping
                        }), Modifier.testTag("practice-phase"))
                    }
                    state.progress?.takeIf { state.phase == PracticePhase.PREPARING }?.let { progress ->
                        LinearProgressIndicator(progress = { (progress.renderedFrames.toFloat() / progress.totalFrames.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    state.problem?.let { Text(stringResource(problem(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("practice-problem")) }
                }
                OutlinedButton(controller::stop, enabled = state.phase != PracticePhase.CLOSED,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-stop")) { Text(stringResource(Res.string.practice_stop)) }
                OutlinedButton(onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-close")) { Text(stringResource(Res.string.practice_close)) }
            }
        }
    }
}
private fun problem(problem: PracticeProblem): StringResource = when (problem) {
    PracticeProblem.INVALID_RANGE -> Res.string.practice_invalid
    PracticeProblem.NO_AUDIO -> Res.string.practice_no_audio
    PracticeProblem.LIMIT -> Res.string.practice_no_room
    PracticeProblem.PCM_UNAVAILABLE -> Res.string.practice_pcm
    PracticeProblem.FAILED -> Res.string.practice_failed
    PracticeProblem.STALE -> Res.string.practice_stale
    PracticeProblem.RECORDING -> Res.string.practice_recording
    PracticeProblem.BUSY -> Res.string.practice_busy
    PracticeProblem.CANCELLED -> Res.string.practice_cancelled
    PracticeProblem.NO_OUTPUT -> Res.string.practice_no_output
    PracticeProblem.RESTORE_FAILED -> Res.string.practice_restore_failed
}
