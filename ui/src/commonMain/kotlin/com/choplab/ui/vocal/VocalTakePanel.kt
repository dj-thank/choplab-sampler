package com.choplab.ui.vocal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.choplab.core.model.Take
import com.choplab.core.vocal.VocalProblem
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Fixed Apply/Cancel/Close, with a lazy list of candidates and line choices at desktop and phone font sizes. */
@Composable
fun VocalTakePanel(controller: VocalTakeController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun action(value: VocalAction) { scope.launch { controller.dispatch(value) } }
    val compName = stringResource(Res.string.vocal_comp_name)
    DisposableEffect(controller) { onDispose { controller.close() } }
    Surface(modifier.fillMaxSize().testTag("vocal-takes")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("vocal-take-fields"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(Res.string.vocal_take_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(Res.string.vocal_take_retained))
                    if (state.project.takes.isEmpty()) Text(stringResource(Res.string.vocal_take_empty))
                    else {
                        TakeChoice(state.project.takes, state.selectedTake, state.editable, "vocal-select-take",
                            label = { take -> state.project.asset(take.assetHash).name }, select = { action(VocalAction.SelectTake(it)) })
                        OutlinedButton({ action(VocalAction.PreviewTake) }, enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-preview-take")) { Text(stringResource(Res.string.vocal_take_preview)) }
                        OutlinedButton({ action(VocalAction.WholeTake) }, enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-whole-take")) { Text(stringResource(Res.string.vocal_take_whole)) }
                        OutlinedButton({ action(VocalAction.FromLyrics) }, enabled = state.editable && state.project.lyrics.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-from-lyrics")) { Text(stringResource(Res.string.vocal_take_lines)) }
                    }
                }
                items(state.project.vocalComps, key = { "saved-${it.id}" }) { comp ->
                    OutlinedButton({ action(VocalAction.SelectComp(comp.id)) }, enabled = state.editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-saved-${comp.id}")) {
                        Text(stringResource(Res.string.vocal_comp_reopen, state.project.asset(comp.renderedAssetHash).name))
                    }
                }
                val draft = state.draft
                if (draft != null) {
                    item { Text(stringResource(Res.string.vocal_comp_crossfade)) }
                    items(draft.segments, key = { "line-${it.id}" }) { line ->
                        val label = state.project.lyrics.firstOrNull { it.id == line.lyricLineId }?.text
                            ?: stringResource(Res.string.vocal_take_whole)
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.vocal_comp_range, time(line.startFrame), time(line.endFrame)))
                        TakeChoice(state.project.takes, line.takeId, state.editable, "vocal-line-${line.id}",
                            label = { take -> state.project.asset(take.assetHash).name }, select = { action(VocalAction.Choose(line.id, it)) })
                    }
                    item { Text(stringResource(Res.string.vocal_comp_replacements)) }
                    items(state.replaceableClips, key = { "replace-${it.id}" }) { clip ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-replace-${clip.id}")
                            .toggleable(clip.id in state.replaceClipIds, enabled = state.editable, role = Role.Checkbox,
                                onValueChange = { action(VocalAction.ReplaceClip(clip.id, it)) }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(clip.id in state.replaceClipIds, onCheckedChange = null, enabled = state.editable)
                            Text(stringResource(Res.string.vocal_comp_placement, state.project.asset(clip.assetHash).name, time(clip.timelineStartFrame ?: com.choplab.core.ProgramCompiler.clipTickToFrame(clip.startTick, state.project.tempo))), modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    state.problem?.let { Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("vocal-problem")) }
                    if (state.problem == VocalProblem.STALE) OutlinedButton({ action(VocalAction.Reload) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-reload")) { Text(stringResource(Res.string.vocal_reload)) }
                    if (state.applied) Text(stringResource(Res.string.vocal_applied), Modifier.testTag("vocal-applied"))
                }
            }
            if (state.phase == VocalPhase.RENDERING || state.phase == VocalPhase.APPLYING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.previewing) OutlinedButton({ action(VocalAction.StopPreview) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-stop-preview")) {
                Text(stringResource(Res.string.vocal_stop_preview))
            }
            if (state.phase == VocalPhase.RENDERING) OutlinedButton({ action(VocalAction.Cancel) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-cancel")) { Text(stringResource(Res.string.vocal_cancel)) }
            else {
                OutlinedButton({ action(VocalAction.PreviewComp(compName)) }, enabled = state.editable && state.draft != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-preview-comp")) { Text(stringResource(Res.string.vocal_comp_preview)) }
                Button({ action(VocalAction.Apply(compName)) }, enabled = state.editable && state.draft != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-apply")) { Text(stringResource(Res.string.vocal_apply)) }
            }
            OutlinedButton({ controller.close(); onClose() }, enabled = state.phase != VocalPhase.APPLYING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-close")) { Text(stringResource(Res.string.vocal_close)) }
        }
    }
}

@Composable
private fun TakeChoice(takes: List<Take>, selected: String?, enabled: Boolean, tag: String,
                       label: (Take) -> String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val index = takes.indexOfFirst { it.id == selected }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
            Text(stringResource(Res.string.vocal_take_choice, index + 1, takes.getOrNull(index)?.let(label) ?: ""))
        }
        DropdownMenu(expanded, { expanded = false }) {
            takes.forEachIndexed { i, take -> DropdownMenuItem(text = { Text(stringResource(Res.string.vocal_take_choice, i + 1, label(take))) },
                onClick = { expanded = false; select(take.id) }, modifier = Modifier.testTag("$tag-${take.id}")) }
        }
    }
}
private fun time(frame: Long): String { val ms = frame / 48; return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}" }
private fun problemText(problem: VocalProblem) = when (problem) {
    VocalProblem.NO_TAKES -> Res.string.vocal_take_empty
    VocalProblem.NO_TIMED_LINES -> Res.string.vocal_no_lines
    VocalProblem.TAKE_TOO_SHORT -> Res.string.vocal_take_short
    VocalProblem.LIMIT -> Res.string.vocal_limit
    VocalProblem.RECORDING -> Res.string.vocal_recording
    VocalProblem.BUSY -> Res.string.vocal_busy
    VocalProblem.STALE -> Res.string.vocal_stale
    VocalProblem.CLOSED -> Res.string.vocal_closed
    VocalProblem.INVALID_INPUT -> Res.string.vocal_invalid
    VocalProblem.RENDER_FAILED -> Res.string.vocal_render_failed
    VocalProblem.APPLY_FAILED -> Res.string.vocal_apply_failed
}
