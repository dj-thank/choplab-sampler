package com.choplab.ui.pattern

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.choplab.core.model.ProjectLimits
import com.choplab.core.pattern.PatternProblem
import com.choplab.core.pattern.PatternEdits
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepPatternPanel(controller: StepPatternController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun dispatch(action: PatternAction) { scope.launch { controller.dispatch(action) } }
    var choosePattern by remember { mutableStateOf(false) }
    var choosePad by remember { mutableStateOf(false) }
    var firstBar by remember(state.firstBar) { mutableStateOf(state.firstBar.toString()) }
    var repeats by remember(state.repeats) { mutableStateOf(state.repeats.toString()) }
    val validStart = firstBar.toIntOrNull()?.let { it in 1..26_041 } == true
    val validRepeat = repeats.toIntOrNull()?.let { it in 1..128 } == true
    val newName = stringResource(Res.string.pattern_editor_default_name, state.project.patterns.size + 1)
    val trackName = state.project.tracks.firstOrNull { it.kind == com.choplab.core.model.TrackKind.BANK }?.name
        ?: stringResource(Res.string.pattern_editor_track)
    DisposableEffect(controller) { onDispose { controller.close() } }
    Surface(modifier.fillMaxWidth().testTag("pattern-editor")) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.pattern_editor_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.pattern_editor_draft))
            Box {
                OutlinedButton(onClick = { choosePattern = true }, enabled = state.editable && !state.dirty,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-choose")) { Text(state.draft.name) }
                DropdownMenu(choosePattern, { choosePattern = false }) {
                    state.project.patterns.forEach { pattern -> DropdownMenuItem(text = { Text(pattern.name) }, onClick = {
                        choosePattern = false; dispatch(PatternAction.Select(pattern.id))
                    }, modifier = Modifier.testTag("pattern-choice-${pattern.id}")) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((copy, label) in listOf(false to Res.string.pattern_editor_new, true to Res.string.pattern_editor_copy)) {
                    OutlinedButton(onClick = { dispatch(PatternAction.New(newName, copy)) },
                        enabled = state.editable && !state.dirty && state.project.patterns.size < ProjectLimits.MAX_PATTERNS,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (copy) "pattern-copy" else "pattern-new")) { Text(stringResource(label)) }
                }
            }
            OutlinedTextField(state.name, { dispatch(PatternAction.Name(it.take(80))) }, enabled = state.editable,
                modifier = Modifier.fillMaxWidth().testTag("pattern-name"), label = { Text(stringResource(Res.string.pattern_editor_name)) })
            Text(stringResource(Res.string.pattern_editor_length, state.draft.bars, state.steps))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (bars in 1..8) FilterChip(state.draft.bars == bars, { dispatch(PatternAction.Resize(bars)) }, enabled = state.editable,
                    label = { Text(stringResource(Res.string.pattern_editor_bars, bars)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-bars-$bars"))
            }
            state.trimBars?.let { bars ->
                Text(stringResource(Res.string.pattern_editor_trim, state.draft.notes.count { it.tick >= bars * PatternEdits.BAR_TICKS }))
                Button(onClick = { dispatch(PatternAction.Resize(bars, true)) }, enabled = state.editable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-trim-confirm")) { Text(stringResource(Res.string.pattern_editor_trim_confirm)) }
                TextButton(onClick = { dispatch(PatternAction.Dismiss) }) { Text(stringResource(Res.string.pattern_editor_keep_length)) }
            }
            Text(stringResource(Res.string.pattern_editor_grid))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((ticks, label) in listOf(PatternEdits.STEP_TICKS to Res.string.pattern_editor_sixteenth,
                    PatternEdits.EIGHTH_TRIPLET_TICKS to Res.string.pattern_editor_eighth_triplet,
                    PatternEdits.SIXTEENTH_TRIPLET_TICKS to Res.string.pattern_editor_sixteenth_triplet)) {
                    FilterChip(state.gridTicks == ticks, { dispatch(PatternAction.Grid(ticks)) }, enabled = state.editable,
                        label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-grid-$ticks"))
                }
            }
            Text(stringResource(Res.string.pattern_editor_grid_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.pattern_editor_columns))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (columns in listOf(16, 32, 64)) FilterChip(state.columns == columns, { dispatch(PatternAction.Columns(columns)) }, enabled = state.editable,
                    label = { Text(stringResource(Res.string.pattern_editor_column_count, columns)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-columns-$columns"))
            }
            Text(stringResource(Res.string.pattern_editor_bank, state.project.banks[state.selectedPadId / 16].name))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (bank in 0..7) FilterChip(state.selectedPadId / 16 == bank, { dispatch(PatternAction.SelectPad(bank * 16 + state.selectedPadId % 16)) },
                    enabled = state.editable, label = { Text(('A' + bank).toString()) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-bank-$bank"))
            }
            val pad = state.project.pads[state.selectedPadId]
            Box {
                OutlinedButton(onClick = { choosePad = true }, enabled = state.editable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-pad")) {
                    Text(stringResource(Res.string.pattern_editor_pad, ('A' + pad.id / 16).toString(), pad.id % 16 + 1, pad.name))
                }
                DropdownMenu(choosePad, { choosePad = false }) {
                    for (id in pad.id / 16 * 16 until pad.id / 16 * 16 + 16) {
                        val item = state.project.pads[id]
                        DropdownMenuItem(text = { Text(stringResource(Res.string.pattern_editor_pad, ('A' + id / 16).toString(), id % 16 + 1, item.name)) },
                            onClick = { choosePad = false; dispatch(PatternAction.SelectPad(id)) }, modifier = Modifier.testTag("pattern-pad-$id"))
                    }
                }
            }
            if (pad.assetHash == null) Text(stringResource(Res.string.pattern_editor_empty_pad))
            val gridTicks = state.gridTicks
            val first = state.page * state.columns
            val end = minOf(first + state.columns, state.steps)
            Text(stringResource(Res.string.pattern_editor_page, first + 1, end, state.steps, state.page + 1, state.pages))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ dispatch(PatternAction.Page(state.page - 1)) }, enabled = state.editable && state.page > 0,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-page-previous")) { Text(stringResource(Res.string.pattern_editor_previous)) }
                OutlinedButton({ dispatch(PatternAction.Page(state.page + 1)) }, enabled = state.editable && state.page + 1 < state.pages,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-page-next")) { Text(stringResource(Res.string.pattern_editor_next)) }
            }
            Text(stringResource(Res.string.pattern_editor_scroll), style = MaterialTheme.typography.bodySmall)
            key(state.draft.id, state.page, state.columns, state.gridTicks) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("pattern-grid"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (step in first until end) {
                        val tick = step * gridTicks
                        val selected = state.draft.notes.any { it.padId == pad.id && it.tick == tick }
                        val label = stringResource(Res.string.pattern_editor_step, step + 1, tick / PatternEdits.BAR_TICKS + 1,
                            tick % PatternEdits.BAR_TICKS / ProjectLimits.PPQ + 1)
                        FilterChip(selected, { dispatch(PatternAction.Toggle(step, gridTicks)) }, enabled = state.editable && pad.assetHash != null,
                            label = { Text((step + 1).toString()) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 52.dp)
                                .testTag("pattern-step-$step").semantics { contentDescription = label })
                    }
                }
            }
            val notes = state.draft.notes.filter { it.padId == pad.id }
            Text(stringResource(Res.string.pattern_editor_notes, notes.size, notes.count { it.tick % state.gridTicks != 0 }))
            Text(stringResource(Res.string.pattern_editor_velocity))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (percent in listOf(25, 50, 75, 100)) FilterChip(state.velocity == percent / 100f, { dispatch(PatternAction.Velocity(percent / 100f)) },
                    enabled = state.editable, label = { Text("$percent%") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text(stringResource(Res.string.pattern_editor_quantize))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((ticks, label) in listOf(960 to Res.string.pattern_editor_quarter, 480 to Res.string.pattern_editor_eighth,
                    PatternEdits.STEP_TICKS to Res.string.pattern_editor_sixteenth,
                    PatternEdits.EIGHTH_TRIPLET_TICKS to Res.string.pattern_editor_eighth_triplet,
                    PatternEdits.SIXTEENTH_TRIPLET_TICKS to Res.string.pattern_editor_sixteenth_triplet)) {
                    OutlinedButton({ dispatch(PatternAction.Quantize(ticks)) }, enabled = state.editable && notes.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-quantize-$ticks")) { Text(stringResource(label)) }
                }
                OutlinedButton({ dispatch(PatternAction.ClearPad) }, enabled = state.editable && notes.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-clear-pad")) { Text(stringResource(Res.string.pattern_editor_clear)) }
            }
            Button({ dispatch(PatternAction.Save) }, enabled = state.editable && state.dirty && state.trimBars == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-save")) { Text(stringResource(Res.string.pattern_editor_save)) }
            OutlinedButton({ dispatch(PatternAction.Discard) }, enabled = state.editable && state.dirty,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-discard")) { Text(stringResource(Res.string.pattern_editor_discard)) }
            HorizontalDivider()
            Text(stringResource(Res.string.pattern_editor_arrange), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(Res.string.pattern_editor_placement_note))
            Text(stringResource(Res.string.pattern_editor_gate_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(repeats, { input -> repeats = input.take(3); input.toIntOrNull()?.takeIf { it in 1..128 }?.let { dispatch(PatternAction.Repeats(it)) } },
                enabled = state.editable, isError = !validRepeat, label = { Text(stringResource(Res.string.pattern_editor_repeat)) }, modifier = Modifier.fillMaxWidth().testTag("pattern-repeat"))
            OutlinedButton({ dispatch(PatternAction.Queue) }, enabled = state.editable && !state.dirty && validRepeat,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-queue")) { Text(stringResource(Res.string.pattern_editor_queue)) }
            for ((index, section) in state.sequence.withIndex()) {
                Text(stringResource(Res.string.pattern_editor_queued, index + 1, state.project.patterns.first { it.id == section.patternId }.name, section.repeats))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ dispatch(PatternAction.MoveQueued(index, -1)) }, enabled = state.editable && index > 0,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.pattern_editor_up)) }
                    OutlinedButton({ dispatch(PatternAction.MoveQueued(index, 1)) }, enabled = state.editable && index < state.sequence.lastIndex,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.pattern_editor_down)) }
                    OutlinedButton({ dispatch(PatternAction.RemoveQueued(index)) }, enabled = state.editable,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.pattern_editor_remove)) }
                }
            }
            OutlinedTextField(firstBar, { input -> firstBar = input.take(5); input.toIntOrNull()?.takeIf { it in 1..26_041 }?.let { dispatch(PatternAction.FirstBar(it)) } },
                enabled = state.editable, isError = !validStart, label = { Text(stringResource(Res.string.pattern_editor_start)) }, modifier = Modifier.fillMaxWidth().testTag("pattern-start"))
            val bars = state.sequence.sumOf { section -> state.project.patterns.first { it.id == section.patternId }.bars * section.repeats }
            if (state.sequence.isNotEmpty()) Text(stringResource(Res.string.pattern_editor_destination, trackName, state.firstBar, state.firstBar + bars),
                Modifier.testTag("pattern-queue-range"))
            Button({ dispatch(PatternAction.Place(trackName)) }, enabled = state.editable && !state.dirty && state.sequence.isNotEmpty() && validStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-place")) { Text(stringResource(Res.string.pattern_editor_place)) }
            if (state.phase == PatternPhase.RENDERING || state.phase == PatternPhase.APPLYING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.phase == PatternPhase.RENDERING) Res.string.pattern_editor_rendering else Res.string.pattern_editor_applying))
            }
            if (state.phase == PatternPhase.RENDERING) OutlinedButton({ dispatch(PatternAction.Cancel) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-cancel")) { Text(stringResource(Res.string.pattern_editor_cancel)) }
            state.problem?.let { Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pattern-problem")) }
            if (state.problem == PatternProblem.STALE_DOCUMENT) OutlinedButton({ dispatch(PatternAction.Reload) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-reload")) { Text(stringResource(Res.string.pattern_editor_reload)) }
            if (state.applied) Text(stringResource(Res.string.pattern_editor_applied), Modifier.testTag("pattern-applied"))
            OutlinedButton({ controller.close(); onClose() }, enabled = state.phase != PatternPhase.APPLYING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-close")) { Text(stringResource(Res.string.pattern_editor_close)) }
        }
    }
}

private fun problemText(problem: PatternProblem): StringResource = when (problem) {
    PatternProblem.INVALID_INPUT -> Res.string.pattern_editor_invalid
    PatternProblem.EMPTY_PAD -> Res.string.pattern_editor_empty_pad
    PatternProblem.PATTERN_LIMIT -> Res.string.pattern_editor_limit
    PatternProblem.TRIM_REQUIRED -> Res.string.pattern_editor_trim_required
    PatternProblem.EMPTY_SEQUENCE, PatternProblem.NO_NOTES -> Res.string.pattern_editor_no_notes
    PatternProblem.SONG_FULL -> Res.string.pattern_editor_song_full
    PatternProblem.NO_ROOM -> Res.string.pattern_editor_no_room
    PatternProblem.RENDER_FAILED -> Res.string.pattern_editor_render_failed
    PatternProblem.UNSAVED_PATTERN -> Res.string.pattern_editor_unsaved
    PatternProblem.STALE_DOCUMENT -> Res.string.pattern_editor_stale
    PatternProblem.BUSY -> Res.string.pattern_editor_busy
    PatternProblem.RECORDING -> Res.string.pattern_editor_recording
    PatternProblem.APPLY_FAILED -> Res.string.pattern_editor_apply_failed
    PatternProblem.CLOSED -> Res.string.pattern_editor_closed
}
