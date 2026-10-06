package com.choplab.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CELoopOverdubDialog(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, close: () -> Unit) {
    var bars by remember { mutableIntStateOf(1) }
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-overdub-panel"),
        title = { Text(stringResource(Res.string.ce_overdub)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.ce_overdub_help))
                for (number in 1..8) CEButton(stringResource(Res.string.ce_overdub_bars, number), { bars = number },
                    Modifier.fillMaxWidth().semantics { selected = bars == number }, primary = bars == number,
                    tag = "ce-overdub-bars-$number")
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_overdub_start), {
            onAction(ContinuousEditorAction.RecordLoopOverdub(bars)); close()
        }, enabled = state.permits(ContinuousCapability.LOOP_OVERDUB), tag = "ce-overdub-start") },
        dismissButton = { CEButton(stringResource(Res.string.ce_close), close, tag = "ce-overdub-close") })
}
