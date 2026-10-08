package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.choplab.core.vocal.PunchPhase
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Input measurements come from the capture endpoint, independently of the output/song meter. */
@Composable internal fun CERecordingStatus(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val active = state.recordingSource || state.recordingVoice || state.recordingHits || state.recordingPunch || state.startingVoiceRecording
    val live by CELive(active, refreshKey, readout)
    var discard by remember { mutableStateOf<ContinuousEditorAction?>(null) }
    var recoverAsSource by remember { mutableStateOf(false) }
    if (active || state.pendingRecording) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.startingSourceRecording || state.startingVoiceRecording) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.ce_input_opening))
                CEButton(stringResource(Res.string.ce_cancel), { onAction(if (state.startingSourceRecording) ContinuousEditorAction.StopSourceRecording else ContinuousEditorAction.StopVoice) },
                    tag = "ce-cancel-input-open")
            }
        } else if (active) {
            val input = live.input
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.ce_recorded_time, ceRecordingTime(input.recordedMillis)), Modifier.testTag("ce-recorded-time"))
                input.limitMillis?.let { limit -> Text(stringResource(Res.string.ce_recording_remaining,
                    ceRecordingTime((limit - input.recordedMillis).coerceAtLeast(0))), Modifier.testTag("ce-recording-remaining")) }
                if (input.channels == 2) {
                    CEInputMeter(input.leftPeak ?: 0f, stringResource(Res.string.ce_input_level_left), "ce-input-level-left")
                    CEInputMeter(input.rightPeak ?: 0f, stringResource(Res.string.ce_input_level_right), "ce-input-level-right")
                } else input.peakLevel?.let { level ->
                    CEInputMeter(level, stringResource(if (input.channels == 1) Res.string.ce_input_level_mono else Res.string.ce_input_level), "ce-input-level")
                }
                if (state.recordingPunch) Text(stringResource(when (live.punchPhase) {
                    PunchPhase.OPENING -> Res.string.ce_punch_opening
                    PunchPhase.PRE_ROLL -> Res.string.ce_punch_preroll
                    PunchPhase.CAPTURING -> Res.string.ce_punch_capturing
                    PunchPhase.SAVING -> Res.string.ce_punch_saving
                    else -> Res.string.ce_punch_waiting
                }), Modifier.testTag("ce-punch-phase").semantics { liveRegion = LiveRegionMode.Polite })
                if (state.recordingVoice || (state.recordingHits && state.loopOverdubBars == 0)) CEButton(stringResource(Res.string.ce_discard_recording), {
                    discard = if (state.recordingVoice) ContinuousEditorAction.DiscardVoice else ContinuousEditorAction.DiscardHits
                }, tag = "ce-discard-recording")
            }
        }
        if (state.pendingRecording && !active) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (state.pendingRecordingApplied) Res.string.ce_recording_added_pending else Res.string.ce_recording_pending), Modifier.testTag("ce-recording-pending").semantics { liveRegion = LiveRegionMode.Polite })
            CEButton(stringResource(Res.string.ce_retry_recording), { onAction(ContinuousEditorAction.RetryRecordingSave) }, tag = "ce-retry-recording")
            if (state.pendingRecordingCanRecoverSource && !state.pendingRecordingApplied) CEButton(stringResource(Res.string.ce_recover_as_source),
                { recoverAsSource = true }, tag = "ce-recover-as-source")
            if (!state.pendingRecordingApplied) CEButton(stringResource(Res.string.ce_discard_recording), { discard = ContinuousEditorAction.DiscardPendingRecording }, tag = "ce-discard-pending")
        }
    }
    state.recordingInterruption?.let { reason -> Text(stringResource(when (reason) {
        RecordingInterruption.DEVICE_LOST -> Res.string.ce_input_device_lost
        RecordingInterruption.READ_FAILED -> Res.string.ce_input_read_failed
        RecordingInterruption.STORAGE_FAILED -> Res.string.ce_input_storage_failed
        RecordingInterruption.OUTPUT_LOST -> Res.string.ce_input_output_lost
        RecordingInterruption.PERMISSION -> Res.string.ce_input_permission_lost
        RecordingInterruption.UNKNOWN -> Res.string.ce_input_interrupted
    }), Modifier.testTag("ce-recording-interruption").semantics { liveRegion = LiveRegionMode.Polite }) }
    if (recoverAsSource) AlertDialog(onDismissRequest = { recoverAsSource = false },
        title = { Text(stringResource(Res.string.ce_recover_as_source)) }, text = { Text(stringResource(Res.string.ce_recover_as_source_hint)) },
        confirmButton = { CEButton(stringResource(Res.string.ce_recover_as_source), {
            recoverAsSource = false; onAction(ContinuousEditorAction.RecoverRecordingAsSource)
        }, tag = "ce-confirm-recover-as-source") },
        dismissButton = { CEButton(stringResource(Res.string.ce_cancel), { recoverAsSource = false }) })
    discard?.let { action -> AlertDialog(onDismissRequest = { discard = null },
        title = { Text(stringResource(Res.string.ce_discard_recording)) }, text = { Text(stringResource(Res.string.ce_discard_recording_hint)) },
        confirmButton = { CEButton(stringResource(Res.string.ce_discard_recording), { discard = null; onAction(action) }, tag = "ce-confirm-discard-recording") },
        dismissButton = { CEButton(stringResource(Res.string.ce_cancel), { discard = null }, tag = "ce-cancel-discard-recording") }) }
}
@Composable private fun CEInputMeter(level: Float, label: String, tag: String) {
    val bounded = level.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { bounded }, modifier = Modifier.width(100.dp).height(12.dp)
            .testTag(tag).semantics { contentDescription = label })
    }
}
internal fun ceRecordingTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Kept beside the CAPTURE input choices so no idle status row displaces the BEAT instrument. */
@Composable internal fun CERecordingEstimates(state: ContinuousEditorState) {
    if (state.recordingSource || state.recordingVoice || state.recordingPunch || state.startingVoiceRecording || state.pendingRecording) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.permits(ContinuousCapability.RECORD_SOURCE)) Text(
            stringResource(Res.string.ce_mic_estimate, state.voiceRecordingEstimateMillis?.let(::ceRecordingTime)
                ?: stringResource(Res.string.ce_estimate_unknown)), Modifier.weight(1f).testTag("ce-mic-estimate"), style = MaterialTheme.typography.bodySmall)
        if (state.permits(ContinuousCapability.RECORD_SYSTEM_SOURCE)) Text(
            stringResource(Res.string.ce_system_estimate, state.systemRecordingEstimateMillis?.let(::ceRecordingTime)
                ?: stringResource(Res.string.ce_estimate_unknown)), Modifier.weight(1f).testTag("ce-system-estimate"), style = MaterialTheme.typography.bodySmall)
    }
}
