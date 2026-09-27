@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Fits in the existing BEAT details; wrapping keeps all settings reachable at large text sizes. */
@Composable internal fun ContinuousRecordingGuidePanel(state: RecordingGuideState, onAction: (RecordingGuideAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("ce-recording-guide"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val click = stringResource(if (state.metronomeEnabled) Res.string.ce_click_on else Res.string.ce_click_off)
        CEButton(click, { onAction(RecordingGuideAction.Metronome(!state.metronomeEnabled)) },
            Modifier.fillMaxWidth().testTag("ce-metronome").semantics { selected = state.metronomeEnabled },
            enabled = state.settingsEnabled)
        Text(stringResource(Res.string.ce_count_in), color = CEColor.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (bars in 0..2) {
                val label = if (bars == 0) stringResource(Res.string.ce_count_in_off)
                    else stringResource(Res.string.ce_count_in_bars, bars)
                CEButton(label, { onAction(RecordingGuideAction.CountInBars(bars)) },
                    Modifier.testTag("ce-count-in-$bars").semantics { selected = state.countInBars == bars },
                    enabled = state.settingsEnabled)
            }
        }
        Text(stringResource(Res.string.ce_click_monitor_only), color = CEColor.Border)
    }
}
