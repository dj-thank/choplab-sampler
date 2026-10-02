package com.choplab.ui.chop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.choplab.core.chop.*
import com.choplab.core.ai.VocalPreviewPhase
import com.choplab.ui.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CEAutoChopDialog(controller: AutoChopController, close: () -> Unit, stopAll: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun act(action: AutoChopAction) { scope.launch { controller.dispatch(action) } }
    val settings = state.settings
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-auto-chop-panel"),
        containerColor = CEColor.Cream, titleContentColor = CEColor.Ink, textContentColor = CEColor.Ink,
        title = { Text(stringResource(Res.string.chop_auto_title), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.chop_auto_range, ceTime(state.source.range.start, state.sampleRate, true),
                    ceTime(state.source.range.end, state.sampleRate, true)))
                AutoChopMode.entries.forEach { mode -> CEButton(stringResource(if (mode == AutoChopMode.EQUAL) Res.string.chop_auto_equal else Res.string.chop_auto_attack),
                    { act(AutoChopAction.Settings(settings.copy(mode = mode))) }, Modifier.fillMaxWidth().semantics { selected = settings.mode == mode },
                    primary = settings.mode == mode, enabled = !state.applying && (mode == AutoChopMode.EQUAL || controller.attackAvailable), tag = "ce-auto-mode-$mode") }
                @Composable fun Select(value: Int, choices: List<Int>, text: String, tag: String, change: (Int) -> Unit) {
                    Text(text, Modifier.testTag("$tag-value"))
                    val less = stringResource(Res.string.ce_decrease, text)
                    val more = stringResource(Res.string.ce_increase, text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton("−", { change(choices.last { it < value }) }, Modifier.weight(1f).semantics { contentDescription = less },
                            enabled = !state.applying && choices.any { it < value }, tag = "$tag-less")
                        CEButton("+", { change(choices.first { it > value }) }, Modifier.weight(1f).semantics { contentDescription = more },
                            enabled = !state.applying && choices.any { it > value }, tag = "$tag-more")
                    }
                }
                Select(settings.slices, listOf(1, 2, 4, 8, 16, 32, 64, 128),
                    stringResource(if (settings.mode == AutoChopMode.EQUAL) Res.string.chop_auto_count else Res.string.chop_auto_max_count, settings.slices), "ce-auto-count") {
                    act(AutoChopAction.Settings(settings.copy(slices = it)))
                }
                if (settings.mode == AutoChopMode.ATTACK) {
                    Select(settings.thresholdDb, listOf(-60, -48, -36, -24, -12, -6), stringResource(Res.string.chop_auto_threshold, settings.thresholdDb), "ce-auto-threshold") {
                        act(AutoChopAction.Settings(settings.copy(thresholdDb = it)))
                    }
                    Select(settings.minimumGapMs, listOf(10, 20, 50, 100, 200, 500), stringResource(Res.string.chop_auto_gap, settings.minimumGapMs), "ce-auto-gap") {
                        act(AutoChopAction.Settings(settings.copy(minimumGapMs = it)))
                    }
                }
                Text(stringResource(Res.string.chop_auto_hint))
                CEButton(stringResource(if (state.working) Res.string.chop_auto_working else Res.string.chop_auto_prepare),
                    { act(AutoChopAction.Prepare) }, Modifier.fillMaxWidth(), enabled = !state.working && !state.applying, tag = "ce-auto-prepare")
                state.problem?.let { problem -> Text(stringResource(when (problem) {
                    AutoChopProblem.TOO_SHORT -> Res.string.chop_auto_short
                    AutoChopProblem.NO_ATTACKS -> Res.string.chop_auto_none
                    AutoChopProblem.TOO_DENSE -> Res.string.chop_auto_dense
                    AutoChopProblem.NO_MEMORY -> Res.string.chop_auto_memory
                    AutoChopProblem.BUSY -> Res.string.chop_auto_busy
                    AutoChopProblem.RECORDING -> Res.string.chop_auto_recording
                    AutoChopProblem.STALE -> Res.string.chop_auto_stale
                    else -> Res.string.chop_auto_failed
                }), Modifier.testTag("ce-auto-problem")) }
                val slices = state.slices
                slices.getOrNull(state.selectedSlice)?.let { range ->
                    Text(stringResource(Res.string.chop_auto_slice, state.selectedSlice + 1, slices.size,
                        ceTime(range.start, state.sampleRate, true), ceTime(range.end, state.sampleRate, true)), Modifier.testTag("ce-auto-slice"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.chop_auto_previous), { act(AutoChopAction.SelectSlice(state.selectedSlice - 1)) }, Modifier.weight(1f),
                            enabled = state.selectedSlice > 0, tag = "ce-auto-previous")
                        CEButton(stringResource(Res.string.chop_auto_next), { act(AutoChopAction.SelectSlice(state.selectedSlice + 1)) }, Modifier.weight(1f),
                            enabled = state.selectedSlice + 1 < slices.size, tag = "ce-auto-next")
                    }
                    CEButton(stringResource(Res.string.chop_auto_preview), { act(AutoChopAction.Preview) }, Modifier.fillMaxWidth(), enabled = !state.applying, tag = "ce-auto-preview")
                    if (state.previewPhase in listOf(VocalPreviewPhase.LOADING, VocalPreviewPhase.PLAYING))
                        Text(stringResource(if (state.previewPhase == VocalPreviewPhase.LOADING) Res.string.chop_auto_preview_loading else Res.string.chop_auto_preview_playing))
                    CEButton(stringResource(Res.string.chop_auto_stop_preview), { act(AutoChopAction.StopPreview) }, Modifier.fillMaxWidth(), tag = "ce-auto-preview-stop")
                }
                CEButton(stringResource(Res.string.ce_stop_all), stopAll, Modifier.fillMaxWidth(), tag = "ce-auto-stop-all")
            }
        }, confirmButton = { CEButton(stringResource(Res.string.chop_auto_apply), { act(AutoChopAction.Apply) },
            primary = true, enabled = state.canApply, tag = "ce-auto-apply") },
        dismissButton = { CEButton(stringResource(Res.string.chop_auto_cancel), close, tag = "ce-auto-cancel") })
}

/** Saved native cuts remain visible and individually assignable after closing the proposal or reopening a project. */
@Composable internal fun CEChopSlices(source: ContinuousSource, padId: Int, enabled: Boolean, onAction: (ContinuousEditorAction) -> Unit) {
    if (source.markers.isEmpty()) return
    val ends = listOf(source.rangeStartFrame) + source.markers + source.rangeEndFrame
    var selected by remember(source.id, source.rangeStartFrame, source.rangeEndFrame, source.markers) { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.chop_auto_slice, selected + 1, ends.size - 1,
            ceTime(ends[selected], source.sampleRate, true), ceTime(ends[selected + 1], source.sampleRate, true)), Modifier.testTag("ce-chop-slice"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEButton(stringResource(Res.string.chop_auto_previous), { selected-- }, Modifier.weight(1f), enabled = selected > 0, tag = "ce-chop-slice-previous")
            CEButton(stringResource(Res.string.chop_auto_next), { selected++ }, Modifier.weight(1f), enabled = selected < ends.size - 2, tag = "ce-chop-slice-next")
        }
        CEButton(stringResource(Res.string.chop_auto_assign, ('A' + padId / 16).toString(), padId % 16 + 1),
            { onAction(ContinuousEditorAction.AssignSourceSlice(selected, padId, source.id, ends[selected], ends[selected + 1])) },
            Modifier.fillMaxWidth(), enabled = enabled, tag = "ce-chop-assign-slice")
    }
}
