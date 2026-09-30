package com.choplab.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun noteRepeatLabel(rate: ContinuousNoteRepeat) = when (rate) {
    ContinuousNoteRepeat.OFF -> Res.string.ce_note_repeat_off
    ContinuousNoteRepeat.QUARTER -> Res.string.ce_note_repeat_quarter
    ContinuousNoteRepeat.EIGHTH -> Res.string.ce_note_repeat_eighth
    ContinuousNoteRepeat.SIXTEENTH -> Res.string.ce_note_repeat_sixteenth
    ContinuousNoteRepeat.THIRTY_SECOND -> Res.string.ce_note_repeat_thirty_second
    ContinuousNoteRepeat.EIGHTH_TRIPLET -> Res.string.ce_note_repeat_eighth_triplet
    ContinuousNoteRepeat.SIXTEENTH_TRIPLET -> Res.string.ce_note_repeat_sixteenth_triplet
}

@Composable internal fun CENoteRepeatDialog(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-note-repeat-panel"),
        title = { Text(stringResource(Res.string.ce_note_repeat)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.ce_note_repeat_help))
                for (rate in ContinuousNoteRepeat.entries) CEButton(stringResource(noteRepeatLabel(rate)),
                    { onAction(ContinuousEditorAction.SetNoteRepeat(rate)) },
                    Modifier.fillMaxWidth().semantics { selected = state.noteRepeat == rate },
                    enabled = state.permits(ContinuousCapability.NOTE_REPEAT), primary = state.noteRepeat == rate,
                    reason = CEReason(state, ContinuousCapability.NOTE_REPEAT), tag = "ce-note-repeat-${rate.name.lowercase()}")
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_close), close, tag = "ce-note-repeat-close") },
        dismissButton = { CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) }, tag = "ce-note-repeat-stop") })
}
