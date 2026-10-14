package com.choplab.ui.chop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.core.chop.*
import com.choplab.ui.*
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

enum class LiveChopTimingProblem { UNAVAILABLE, INVALIDATED, STALE }

data class LiveChopTimingState(
    val open: Boolean = false,
    val route: LiveChopRoute? = null,
    val correction: LiveChopCorrection = LiveChopCorrection(),
    val estimatedMillis: Int? = null,
    val problem: LiveChopTimingProblem? = null,
    val lastCut: LiveChopCut? = null,
)

@Composable internal fun CELiveChopTimingLabel(state: LiveChopTimingState) {
    Text(when {
        state.correction.mode == LiveChopTimingMode.MANUAL -> stringResource(Res.string.chop_timing_manual_value, state.correction.manualMillis)
        state.estimatedMillis != null -> stringResource(Res.string.chop_timing_estimated_value, state.estimatedMillis)
        else -> stringResource(Res.string.chop_timing_unknown)
    }, Modifier.testTag("ce-live-timing-value"), color = CEColor.Ink, fontSize = 12.sp)
    state.problem?.let { Text(stringResource(when (it) {
        LiveChopTimingProblem.INVALIDATED -> Res.string.chop_timing_invalidated
        LiveChopTimingProblem.STALE -> Res.string.chop_timing_stale
        LiveChopTimingProblem.UNAVAILABLE -> Res.string.chop_timing_unavailable
    }), Modifier.testTag("ce-live-timing-problem"), color = CEColor.Ink) }
}

@Composable internal fun CELiveChopTimingDialog(state: LiveChopTimingState, onAction: (ContinuousEditorAction) -> Unit) {
    if (!state.open) return
    var draft by remember(state.route, state.correction) { mutableStateOf(state.correction) }
    fun close() = onAction(ContinuousEditorAction.CloseLiveChopTiming)
    AlertDialog(onDismissRequest = ::close, modifier = Modifier.testTag("ce-live-timing-panel"),
        containerColor = CEColor.Cream, titleContentColor = CEColor.Ink, textContentColor = CEColor.Ink,
        title = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.chop_timing_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) }, Modifier.fillMaxWidth(), tag = "ce-live-timing-stop-all")
        } },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CELiveChopTimingLabel(state)
                Text(stringResource(Res.string.chop_timing_hint))
                LiveChopTimingMode.entries.forEach { mode ->
                    CEButton(stringResource(if (mode == LiveChopTimingMode.ESTIMATED) Res.string.chop_timing_estimated else Res.string.chop_timing_manual),
                        { draft = if (mode == LiveChopTimingMode.MANUAL && draft.mode != mode)
                            LiveChopCorrection(mode, (state.estimatedMillis ?: 0).coerceIn(0, 1_000)) else draft.copy(mode = mode) },
                        Modifier.fillMaxWidth().semantics { selected = draft.mode == mode }, primary = draft.mode == mode,
                        tag = "ce-live-timing-$mode")
                }
                if (draft.mode == LiveChopTimingMode.MANUAL) {
                    val label = stringResource(Res.string.chop_timing_manual_value, draft.manualMillis)
                    Text(label, Modifier.testTag("ce-live-timing-manual-value"))
                    Slider(draft.manualMillis.toFloat(), { draft = draft.copy(manualMillis = it.roundToInt()) },
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 60.dp).semantics { contentDescription = label }.testTag("ce-live-timing-slider"),
                        valueRange = 0f..1_000f, colors = SliderDefaults.colors(thumbColor = CEColor.Orange,
                            activeTrackColor = CEColor.Orange, inactiveTrackColor = CEColor.Tan))
                    val less = stringResource(Res.string.ce_decrease, label)
                    val more = stringResource(Res.string.ce_increase, label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton("−1 ms", { draft = draft.copy(manualMillis = draft.manualMillis - 1) },
                            Modifier.weight(1f).semantics { contentDescription = less }, enabled = draft.manualMillis > 0, tag = "ce-live-timing-less")
                        CEButton("+1 ms", { draft = draft.copy(manualMillis = draft.manualMillis + 1) },
                            Modifier.weight(1f).semantics { contentDescription = more }, enabled = draft.manualMillis < 1_000, tag = "ce-live-timing-more")
                    }
                    Text(stringResource(Res.string.chop_timing_manual_hint))
                }
            }
        }, confirmButton = { CEButton(stringResource(Res.string.chop_timing_apply), {
            state.route?.let { onAction(ContinuousEditorAction.SetLiveChopCorrection(it, draft)) }
        }, primary = true, enabled = state.route != null && (draft.mode == LiveChopTimingMode.MANUAL || state.estimatedMillis != null), tag = "ce-live-timing-apply") },
        dismissButton = { CEButton(stringResource(Res.string.chop_auto_cancel), ::close, tag = "ce-live-timing-cancel") })
}
