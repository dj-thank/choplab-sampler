package com.choplab.ui.mixer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.engine.MixFilterMode
import com.choplab.engine.MixerProgram
import com.choplab.engine.MixerSnapshot
import com.choplab.ui.CEButton
import com.choplab.ui.CEColor
import com.choplab.ui.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable internal fun CEMixerButton(enabled: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CEButton(stringResource(Res.string.mixer_open), onOpen, modifier, enabled = enabled, tag = "ce-mixer-open")
}

/** Fits the existing instrument as a dialog. Draft changes remain silent until one Apply. */
@Composable internal fun CEMixerPanel(
    state: MixerEditorState,
    onAction: (MixerAction) -> Unit,
    blocked: MixerProblem?,
    onStopAll: () -> Unit,
    readMeters: ((MixerSnapshot) -> Boolean)? = null,
    active: Boolean = true,
) {
    val draft = state.draft ?: return
    val editable = !state.applying && state.problem != MixerProblem.STALE
    Dialog({ if (!state.applying) onAction(MixerAction.Cancel) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("ce-mixer-panel"),
                shape = RoundedCornerShape(12.dp), color = CEColor.Cream, border = BorderStroke(2.dp, CEColor.Ink)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.mixer_open), fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("ce-mixer-title"))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ce-mixer-fields"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChannelSelector(state, editable, onAction)
                        Text(stringResource(Res.string.mixer_draft_hint), fontSize = 14.sp)
                        MixerMeters(draft.channel, readMeters, active)
                        Field(state, MixerField.GAIN, editable, onAction)
                        if (draft.channel.target != MixerTarget.Master) {
                            Field(state, MixerField.PAN, editable, onAction)
                            Toggle(draft, MixerSwitch.MUTE, editable, onAction)
                            Toggle(draft, MixerSwitch.SOLO, editable, onAction)
                        }
                        Section(Res.string.mixer_eq)
                        for (field in listOf(MixerField.LOW_DB, MixerField.MID_DB, MixerField.HIGH_DB)) Field(state, field, editable, onAction)
                        Section(Res.string.mixer_filter)
                        for (mode in MixFilterMode.entries) {
                            val label = stringResource(when (mode) {
                                MixFilterMode.OFF -> Res.string.mixer_filter_off
                                MixFilterMode.LOW_PASS -> Res.string.mixer_filter_low
                                MixFilterMode.HIGH_PASS -> Res.string.mixer_filter_high
                            })
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draft.filter == mode, { onAction(MixerAction.Filter(mode)) }, enabled = editable,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ce-mixer-filter-${mode.name.lowercase()}")
                                        .semantics { contentDescription = label })
                                Text(label, fontSize = 16.sp)
                            }
                        }
                        Field(state, MixerField.CUTOFF, editable, onAction)
                        Section(Res.string.mixer_compressor)
                        Toggle(draft, MixerSwitch.COMPRESSOR, editable, onAction)
                        for (field in listOf(MixerField.THRESHOLD, MixerField.RATIO, MixerField.ATTACK, MixerField.RELEASE, MixerField.MAKEUP))
                            Field(state, field, editable, onAction)
                        if (draft.channel.target == MixerTarget.Master) {
                            Section(Res.string.mixer_returns)
                            Toggle(draft, MixerSwitch.DELAY, editable, onAction)
                            for (field in listOf(MixerField.DELAY_TIME, MixerField.FEEDBACK, MixerField.DELAY_RETURN)) Field(state, field, editable, onAction)
                            Toggle(draft, MixerSwitch.REVERB, editable, onAction)
                            for (field in listOf(MixerField.REVERB_DECAY, MixerField.DAMPING, MixerField.REVERB_RETURN)) Field(state, field, editable, onAction)
                            Text(stringResource(Res.string.mixer_master_boundary), fontSize = 14.sp)
                        } else {
                            Section(Res.string.mixer_sends)
                            Field(state, MixerField.DELAY_SEND, editable, onAction)
                            Field(state, MixerField.REVERB_SEND, editable, onAction)
                            Text(stringResource(Res.string.mixer_sends_hint), fontSize = 14.sp)
                        }
                        (blocked ?: state.problem)?.let {
                            Text(mixerProblem(it), Modifier.testTag("ce-mixer-problem").semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.mixer_cancel), { onAction(MixerAction.Cancel) }, Modifier.weight(1f),
                            enabled = !state.applying, tag = "ce-mixer-cancel")
                        CEButton(stringResource(Res.string.mixer_apply), { onAction(MixerAction.Apply) }, Modifier.weight(1f),
                            enabled = state.canApply && editable && blocked == null, primary = true, tag = "ce-mixer-apply")
                    }
                    CEButton(stringResource(Res.string.ce_stop_all), onStopAll, Modifier.fillMaxWidth(), tag = "ce-mixer-stop")
                }
            }
        }
    }
}

@Composable private fun channelName(channel: MixerChannel): String = when (val target = channel.target) {
    is MixerTarget.Bank -> stringResource(Res.string.mixer_bank, ('A' + target.id).toString(), channel.name)
    is MixerTarget.Track -> channel.name
    MixerTarget.Master -> stringResource(Res.string.mixer_master)
}

@Composable private fun ChannelSelector(state: MixerEditorState, enabled: Boolean, onAction: (MixerAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        CEButton(stringResource(Res.string.mixer_choose, channelName(requireNotNull(state.draft).channel)), { expanded = true },
            Modifier.fillMaxWidth(), enabled = enabled, tag = "ce-mixer-choose")
        DropdownMenu(expanded, { expanded = false }) {
            for ((index, channel) in state.channels.withIndex()) DropdownMenuItem(
                text = { Text(channelName(channel), fontSize = 16.sp) },
                onClick = { expanded = false; onAction(MixerAction.Select(channel.target)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("ce-mixer-channel-$index"),
            )
        }
    }
}

@Composable private fun Section(label: StringResource) { Text(stringResource(label), fontSize = 17.sp, fontWeight = FontWeight.Bold) }

@Composable private fun Field(state: MixerEditorState, field: MixerField, enabled: Boolean, onAction: (MixerAction) -> Unit) {
    val text = state.draft?.fields?.get(field) ?: return
    val label = stringResource(if (field == MixerField.GAIN && state.draft?.channel?.target == MixerTarget.Master) Res.string.mixer_master_gain else fieldLabel(field))
    OutlinedTextField(text, { onAction(MixerAction.Change(field, it)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ce-mixer-${field.name.lowercase()}"),
        enabled = enabled, singleLine = true, isError = field in state.invalidFields,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.minimum < 0) KeyboardType.Text else KeyboardType.Decimal),
        label = { Text(label) }, supportingText = {
            Text(stringResource(Res.string.mixer_range, compact(field.minimum), compact(field.maximum)))
        })
}

@Composable private fun Toggle(draft: MixerDraft, control: MixerSwitch, enabled: Boolean, onAction: (MixerAction) -> Unit) {
    val label = stringResource(when (control) {
        MixerSwitch.MUTE -> Res.string.mixer_mute
        MixerSwitch.SOLO -> Res.string.mixer_solo
        MixerSwitch.COMPRESSOR -> Res.string.mixer_compressor_on
        MixerSwitch.DELAY -> Res.string.mixer_delay_on
        MixerSwitch.REVERB -> Res.string.mixer_reverb_on
    })
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(control in draft.switches, { onAction(MixerAction.Switch(control, it)) }, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ce-mixer-switch-${control.name.lowercase()}")
                .semantics { contentDescription = label })
        Text(label, Modifier.padding(start = 8.dp).weight(1f), fontSize = 16.sp)
    }
}

/** Copies a coherent publication on the UI side; a missing/closed driver is unknown, never fabricated silence. */
@Composable private fun MixerMeters(channel: MixerChannel, read: ((MixerSnapshot) -> Boolean)?, active: Boolean) {
    val currentRead by rememberUpdatedState(read)
    val snapshot = remember { MixerSnapshot() }
    var values by remember(channel) { mutableStateOf<List<Float>?>(null) }
    LaunchedEffect(channel, active) {
        while (active) {
            values = if (currentRead?.invoke(snapshot) == true) {
                val buses = if (channel.target == MixerTarget.Master) listOf(MixerProgram.DELAY_RETURN, MixerProgram.REVERB_RETURN)
                    else listOf(if (channel.busId == null) MixerProgram.UNROUTED_BUS else
                        (0 until MixerProgram.MAX_BUSES).firstOrNull { snapshot.program.busId(it) == channel.busId } ?: -1)
                if (buses.any { it < 0 }) null else
                    (if (channel.target == MixerTarget.Master) listOf(snapshot.masterPeak[0], snapshot.masterPeak[1], snapshot.masterRms[0], snapshot.masterRms[1]) else emptyList()) +
                    buses.flatMap { bus -> listOf(snapshot.peak[bus * 2], snapshot.peak[bus * 2 + 1], snapshot.rms[bus * 2], snapshot.rms[bus * 2 + 1]) }
            } else null
            delay(100); withFrameNanos { }
        }
        values = null
    }
    Column(Modifier.fillMaxWidth().testTag("ce-mixer-meter"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.mixer_meter_point), fontSize = 14.sp)
        if (channel.target is MixerTarget.Bank && channel.busId == null) Text(stringResource(Res.string.mixer_meter_unrouted), fontSize = 14.sp)
        val levels = values
        if (levels == null) Text(stringResource(Res.string.mixer_meter_unavailable), fontSize = 14.sp)
        else for (index in levels.indices step 4) {
            if (channel.target == MixerTarget.Master) Text(stringResource(when (index) { 0 -> Res.string.mixer_master_meter; 4 -> Res.string.mixer_delay_return; else -> Res.string.mixer_reverb_return }), fontSize = 14.sp)
            Text(stringResource(Res.string.mixer_meter_levels, db(levels[index]), db(levels[index + 1]), db(levels[index + 2]), db(levels[index + 3])), fontSize = 14.sp)
            LinearProgressIndicator(progress = { maxOf(levels[index], levels[index + 1]).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun compact(value: Double) = value.toString().removeSuffix(".0")
private fun db(value: Float): String = if (value <= 0f) "−∞" else ((20 * log10(value) * 10).roundToInt() / 10.0).toString()

internal fun fieldLabel(field: MixerField): StringResource = when (field) {
    MixerField.GAIN -> Res.string.mixer_gain
    MixerField.PAN -> Res.string.mixer_pan
    MixerField.LOW_DB -> Res.string.mixer_low
    MixerField.MID_DB -> Res.string.mixer_mid
    MixerField.HIGH_DB -> Res.string.mixer_high
    MixerField.CUTOFF -> Res.string.mixer_cutoff
    MixerField.THRESHOLD -> Res.string.mixer_threshold
    MixerField.RATIO -> Res.string.mixer_ratio
    MixerField.ATTACK -> Res.string.mixer_attack
    MixerField.RELEASE -> Res.string.mixer_release
    MixerField.MAKEUP -> Res.string.mixer_makeup
    MixerField.DELAY_SEND -> Res.string.mixer_delay_send
    MixerField.REVERB_SEND -> Res.string.mixer_reverb_send
    MixerField.DELAY_TIME -> Res.string.mixer_delay_time
    MixerField.FEEDBACK -> Res.string.mixer_feedback
    MixerField.DELAY_RETURN -> Res.string.mixer_delay_return
    MixerField.REVERB_DECAY -> Res.string.mixer_decay
    MixerField.DAMPING -> Res.string.mixer_damping
    MixerField.REVERB_RETURN -> Res.string.mixer_reverb_return
}

@Composable internal fun mixerProblem(problem: MixerProblem): String = stringResource(when (problem) {
    MixerProblem.BUSY -> Res.string.mixer_busy
    MixerProblem.RECORDING -> Res.string.mixer_recording
    MixerProblem.STALE -> Res.string.mixer_stale
    MixerProblem.INVALID -> Res.string.mixer_invalid
    MixerProblem.APPLY_FAILED -> Res.string.mixer_failed
    MixerProblem.UNAPPLIED -> Res.string.mixer_unapplied
    MixerProblem.MISSING -> Res.string.mixer_missing
})
