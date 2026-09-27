package com.choplab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The normal BANK selector reads committed metadata; a small color strip leaves text contrast unchanged. */
@Composable internal fun CEBankMetadataButton(
    id: Int, name: String, color: Int, role: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val description = bankMetadataDescription(id, name, role)
    CEButton("${'A' + id} $name", onClick, modifier.drawWithContent {
        drawContent()
        drawRect(Color(0xff000000L or color.toLong()), Offset(6.dp.toPx(), size.height - 5.dp.toPx()),
            Size((size.width - 12.dp.toPx()).coerceAtLeast(0f), 3.dp.toPx()))
    }.semantics { contentDescription = description; this.selected = selected }, primary = selected, tag = "ce-bank-$id")
}

@Composable internal fun bankMetadataDescription(id: Int, name: String, role: String): String =
    stringResource(Res.string.bp_bank_description, ('A' + id).toString(), name, role)

/** These entries belong beside the existing BEAT details, so they never take space from the large PAD grid. */
@Composable internal fun CEBankPadEditButtons(
    state: ContinuousEditorState, onAction: (BankPadEditAction) -> Unit,
    blocked: BankPadEditProblem?, modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val reason = blocked?.let { bankPadEditProblem(it) }.orEmpty()
        CEButton(stringResource(Res.string.bp_bank_edit), { onAction(BankPadEditAction.OpenBank) }, Modifier.fillMaxWidth(),
            enabled = blocked == null, reason = reason, tag = "ce-bank-edit")
        CEButton(stringResource(Res.string.bp_pad_edit), { onAction(BankPadEditAction.OpenPad) }, Modifier.fillMaxWidth(),
            enabled = blocked == null && state.selectedPad?.kind?.let { it != ContinuousPadKind.EMPTY } == true,
            reason = reason.ifEmpty { stringResource(Res.string.bp_empty_pad) }, tag = "ce-pad-sound-edit")
    }
}

/** A bounded dialog with a scrollable form and always reachable cancel/apply/stop controls, including font scale 2. */
@Composable internal fun CEBankPadEditor(
    state: BankPadEditorState, onAction: (BankPadEditAction) -> Unit, blocked: BankPadEditProblem?, onStopAll: () -> Unit,
) {
    val draft = state.draft ?: return
    val editable = !state.applying && state.problem != BankPadEditProblem.STALE
    Dialog(onDismissRequest = { if (!state.applying) onAction(BankPadEditAction.Cancel) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("ce-bank-pad-panel"),
                shape = RoundedCornerShape(12.dp), color = CEColor.Cream, border = androidx.compose.foundation.BorderStroke(2.dp, CEColor.Ink)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (draft is BankPadDraft.BankMetadata) Res.string.bp_bank_title else Res.string.bp_pad_title,
                        if (draft is BankPadDraft.BankMetadata) ('A' + draft.id).toString() else cePadName(draft.id)),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("ce-bank-pad-title"))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ce-bank-pad-fields"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (draft) {
                            is BankPadDraft.BankMetadata -> {
                                Field(state, BankPadEditField.NAME, draft.name, Res.string.bp_name, Res.string.bp_label_hint, editable, onAction)
                                Field(state, BankPadEditField.COLOR, draft.color, Res.string.bp_color, Res.string.bp_color_hint, editable, onAction)
                                draft.color.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { rgb ->
                                    if (rgb in 0..0xffffff) Box(Modifier.fillMaxWidth().height(24.dp)
                                        .background(Color(0xff000000L or rgb.toLong())).border(1.dp, CEColor.Ink).testTag("ce-bank-color-preview"))
                                }
                                Field(state, BankPadEditField.ROLE, draft.role, Res.string.bp_role, Res.string.bp_role_hint, editable, onAction)
                            }
                            is BankPadDraft.PadSound -> {
                                Text(draft.name, fontSize = 16.sp, modifier = Modifier.testTag("ce-pad-sound-name"))
                                Text(stringResource(Res.string.bp_sound_hint), fontSize = 14.sp)
                                Field(state, BankPadEditField.PAN, draft.pan, Res.string.bp_pan, Res.string.bp_pan_hint, editable, onAction)
                                Field(state, BankPadEditField.ATTACK, draft.attack, Res.string.bp_attack, Res.string.bp_time_hint, editable, onAction)
                                Field(state, BankPadEditField.DECAY, draft.decay, Res.string.bp_decay, Res.string.bp_time_hint, editable, onAction)
                                Field(state, BankPadEditField.SUSTAIN, draft.sustain, Res.string.bp_sustain, Res.string.bp_sustain_hint, editable, onAction)
                                Field(state, BankPadEditField.RELEASE, draft.release, Res.string.bp_release, Res.string.bp_release_hint, editable, onAction)
                            }
                        }
                        (blocked ?: state.problem)?.let { problem ->
                            Text(bankPadEditProblem(problem), Modifier.testTag("ce-bank-pad-problem").semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.bp_cancel), { onAction(BankPadEditAction.Cancel) }, Modifier.weight(1f),
                            enabled = !state.applying, tag = "ce-bank-pad-cancel")
                        CEButton(stringResource(Res.string.bp_apply), { onAction(BankPadEditAction.Apply) }, Modifier.weight(1f),
                            enabled = state.canApply && editable && blocked == null, primary = true,
                            reason = blocked?.let { bankPadEditProblem(it) }.orEmpty(), tag = "ce-bank-pad-apply")
                    }
                    CEButton(stringResource(Res.string.ce_stop_all), onStopAll, Modifier.fillMaxWidth(), tag = "ce-bank-pad-stop")
                }
            }
        }
    }
}

@Composable private fun Field(
    state: BankPadEditorState, field: BankPadEditField, text: String, label: StringResource, hint: StringResource,
    enabled: Boolean, onAction: (BankPadEditAction) -> Unit,
) {
    val invalid = field in state.invalidFields
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(text, { onAction(BankPadEditAction.Change(field, it)) },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ce-bank-pad-${field.name.lowercase()}"),
            enabled = enabled, singleLine = true, isError = invalid,
            // A decimal-only keyboard can omit the minus sign needed for left pan.
            keyboardOptions = KeyboardOptions(keyboardType = if (field in setOf(BankPadEditField.NAME, BankPadEditField.COLOR, BankPadEditField.ROLE, BankPadEditField.PAN))
                KeyboardType.Text else KeyboardType.Decimal),
            label = { Text(stringResource(label)) })
        Text(stringResource(hint), color = if (invalid) MaterialTheme.colorScheme.error else CEColor.Ink, fontSize = 14.sp)
    }
}

@Composable internal fun bankPadEditProblem(problem: BankPadEditProblem): String = stringResource(when (problem) {
    BankPadEditProblem.BUSY -> Res.string.bp_busy
    BankPadEditProblem.RECORDING -> Res.string.bp_recording
    BankPadEditProblem.EMPTY_PAD -> Res.string.bp_empty_pad
    BankPadEditProblem.STALE -> Res.string.bp_stale
    BankPadEditProblem.INVALID -> Res.string.bp_invalid
    BankPadEditProblem.APPLY_FAILED -> Res.string.bp_apply_failed
})
