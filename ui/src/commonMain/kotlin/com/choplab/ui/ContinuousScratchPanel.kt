package com.choplab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** SOURCE listens independently on the left; HAND moves its own voice on the right. */
@Composable internal fun CEScratchPanel(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
                                       readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val sheet = state.scratch ?: return
    Dialog(onDismissRequest = { onAction(ContinuousEditorAction.CloseScratch) },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth(.96f).widthIn(max = 1320.dp).fillMaxHeight(.94f)
            .clip(RoundedCornerShape(12.dp)).background(CEColor.Cream).padding(12.dp).testTag("ce-scratch-panel")) {
            val columns = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.3f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.ce_scratch), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    CEButton(stringResource(Res.string.ce_close), { onAction(ContinuousEditorAction.CloseScratch) }, tag = "ce-scratch-close")
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("ce-scratch-scroll"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CEScratchSource(state, onAction, readout, refreshKey, Modifier.weight(1f))
                        CEScratchHand(state, sheet, onAction, readout, refreshKey, Modifier.weight(1f))
                    } else {
                        CEScratchSource(state, onAction, readout, refreshKey, Modifier.fillMaxWidth())
                        CEScratchHand(state, sheet, onAction, readout, refreshKey, Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_scratch_hand_stop), { onAction(ContinuousEditorAction.ScratchLetGo) },
                        Modifier.weight(1f), enabled = sheet.holding, tag = "ce-scratch-hand-stop")
                    CEActionButton(stringResource(Res.string.ce_stop_all), ContinuousEditorAction.StopAll, state,
                        ContinuousCapability.STOP_ALL, onAction, Modifier.weight(1f), tag = "ce-scratch-stop-all")
                }
            }
        }
    }
}

@Composable private fun CEScratchSource(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
                                        readout: () -> ContinuousEditorReadout, refreshKey: Long, modifier: Modifier) {
    val source = state.original
    val live by CELive(state.originalPlaying, refreshKey, readout)
    val fraction = if (source == null) 0f else (live.originalFrame.toDouble() / source.frames).toFloat().coerceIn(0f, 1f)
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(CEColor.Ink).padding(12.dp).testTag("ce-scratch-source"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.ce_scratch_source_title), color = CEColor.Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(source?.title ?: stringResource(Res.string.ce_no_source), color = CEColor.Tan)
        CERecordFace(fraction, source != null, state.originalPlaying, CEColor.Green, stringResource(Res.string.ce_scratch_source_disc),
            Modifier.testTag("ce-scratch-source-platter").semantics {
                stateDescription = if (source == null) "–" else ceTime(live.originalFrame, source.sampleRate, true)
            })
        CEWaveform(source?.peaks.orEmpty(), Modifier.fillMaxWidth().height(80.dp),
            stringResource(Res.string.ce_original_wave, source?.title.orEmpty()), position = { fraction },
            onSeek = if (source != null && state.permits(ContinuousCapability.ORIGINAL_SEEK))
                ({ onAction(ContinuousEditorAction.SeekOriginal((it * source.frames).roundToLong())) }) else null,
            tag = "ce-scratch-source-wave")
        Text(if (source == null) "–" else "${ceTime(live.originalFrame, source.sampleRate, true)} / ${ceTime(source.frames, source.sampleRate, true)}",
            color = CEColor.Green, modifier = Modifier.testTag("ce-scratch-source-position"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(Res.string.ce_scratch_source_start), ContinuousEditorAction.SeekOriginal(0), state,
                ContinuousCapability.ORIGINAL_SEEK, onAction, Modifier.weight(1f), dark = true, tag = "ce-scratch-source-start")
            CEActionButton(stringResource(Res.string.ce_play), ContinuousEditorAction.PlayOriginal, state,
                ContinuousCapability.ORIGINAL_PLAYBACK, onAction, Modifier.weight(1f), tag = "ce-scratch-source-play")
            CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopOriginal, state,
                ContinuousCapability.ORIGINAL_PLAYBACK, onAction, Modifier.weight(1f), dark = true, tag = "ce-scratch-source-stop")
        }
        CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
            { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.fillMaxWidth(), dark = true,
            tag = "ce-scratch-source-gain", stacked = true)
    }
}

@Composable private fun CEScratchHand(state: ContinuousEditorState, sheet: ContinuousScratch,
                                      onAction: (ContinuousEditorAction) -> Unit, readout: () -> ContinuousEditorReadout,
                                      refreshKey: Long, modifier: Modifier) {
    val live by CELive(sheet.holding, refreshKey, readout)
    val latestAction by rememberUpdatedState(onAction)
    val pad = state.selectedPad
    val source = state.original
    val ready = (if (sheet.target == ContinuousScratchTarget.PAD) sheet.padAvailable else sheet.originalAvailable) && state.permits(ContinuousCapability.SCRATCH)
    var keyboardFocus by remember { mutableStateOf(false) }
    var pressedDirection by remember { mutableStateOf<Key?>(null) }
    val label = stringResource(Res.string.ce_scratch_platter)
    val status = stringResource(if (sheet.holding) Res.string.ce_scratch_holding else Res.string.ce_scratch_resting)
    val back = stringResource(Res.string.ce_scratch_back)
    val forward = stringResource(Res.string.ce_scratch_forward)
    val peaks = if (sheet.target == ContinuousScratchTarget.PAD) pad?.peaks.orEmpty() else remember(source) {
        source?.let { s ->
            val first = (s.rangeStartFrame.toDouble() / s.frames * s.peaks.size).toInt().coerceIn(0, s.peaks.size)
            val last = kotlin.math.ceil(s.rangeEndFrame.toDouble() / s.frames * s.peaks.size).toInt().coerceIn(first, s.peaks.size)
            s.peaks.subList(first, last)
        }.orEmpty()
    }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).padding(12.dp).testTag("ce-scratch-hand"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.ce_scratch_hand_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEButton(stringResource(Res.string.ce_scratch_pad, "${cePadName(state.selectedPadId)} ${pad?.name.orEmpty()}".trim()),
                { onAction(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.PAD)) },
                Modifier.weight(1f).semantics { selected = sheet.target == ContinuousScratchTarget.PAD },
                enabled = sheet.padAvailable && !sheet.holding, primary = sheet.target == ContinuousScratchTarget.PAD, tag = "ce-scratch-target-pad")
            CEButton(stringResource(Res.string.ce_scratch_original, source?.let { ceTime(it.rangeStartFrame, it.sampleRate) } ?: "–",
                source?.let { ceTime(it.rangeEndFrame, it.sampleRate) } ?: "–"),
                { onAction(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL)) },
                Modifier.weight(1f).semantics { selected = sheet.target == ContinuousScratchTarget.ORIGINAL },
                enabled = sheet.originalAvailable && !sheet.holding, primary = sheet.target == ContinuousScratchTarget.ORIGINAL, tag = "ce-scratch-target-original")
        }
        CERecordFace(live.scratchFraction, ready, sheet.holding, CEColor.Orange, stringResource(Res.string.ce_scratch_hand_disc),
            Modifier.testTag("ce-scratch-platter")
                .then(if (keyboardFocus) Modifier.border(3.dp, CEColor.Ink, CircleShape) else Modifier)
                .onFocusChanged { keyboardFocus = it.isFocused; if (!it.isFocused) pressedDirection = null }
                .onKeyEvent { event ->
                    if (!ready || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed ||
                        (event.key != Key.DirectionLeft && event.key != Key.DirectionRight)) false
                    else {
                        if (event.type == KeyEventType.KeyDown && pressedDirection == null) {
                            pressedDirection = event.key
                            latestAction(ContinuousEditorAction.ScratchNudge(event.key == Key.DirectionRight))
                        } else if (event.type == KeyEventType.KeyUp && pressedDirection == event.key) pressedDirection = null
                        true
                    }
                }.focusable(enabled = ready).pointerInput(ready) {
                if (!ready) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume(); latestAction(ContinuousEditorAction.ScratchHold)
                    var previous = down.position.x
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val moved = change.position.x - previous
                            previous = change.position.x
                            if (moved != 0f) latestAction(ContinuousEditorAction.ScratchDrag(moved))
                        }
                    } finally { latestAction(ContinuousEditorAction.ScratchLetGo) }
                }
            }.semantics {
                contentDescription = label; stateDescription = status
                if (ready) customActions = listOf(
                    CustomAccessibilityAction(back) { latestAction(ContinuousEditorAction.ScratchNudge(false)); true },
                    CustomAccessibilityAction(forward) { latestAction(ContinuousEditorAction.ScratchNudge(true)); true })
            })
        Text(stringResource(Res.string.ce_scratch_independent_hint), fontSize = 13.sp)
        CEWaveform(peaks, Modifier.fillMaxWidth().height(64.dp), label, position = { live.scratchFraction }, tag = "ce-scratch-hand-wave")
        CEValueSlider(stringResource(Res.string.ce_scratch_hand_gain), sheet.handMonitorGain, state, ContinuousCapability.SCRATCH,
            { onAction(ContinuousEditorAction.SetHandMonitorGain(it)) }, Modifier.fillMaxWidth(), tag = "ce-scratch-hand-gain", stacked = true)
        CEValueSlider(stringResource(Res.string.ce_scratch_cut), sheet.cut, state, ContinuousCapability.SCRATCH,
            { onAction(ContinuousEditorAction.SetScratchCut(it)) }, Modifier.fillMaxWidth(), tag = "ce-scratch-cut", stacked = true)
        Text(stringResource(Res.string.ce_scratch_sensitivity), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in ContinuousScratchSensitivity.entries) CEButton(stringResource(when (option) {
                ContinuousScratchSensitivity.FINE -> Res.string.ce_scratch_fine
                ContinuousScratchSensitivity.NORMAL -> Res.string.ce_scratch_normal
                ContinuousScratchSensitivity.WIDE -> Res.string.ce_scratch_wide
            }), { onAction(ContinuousEditorAction.SetScratchSensitivity(option)) },
                Modifier.weight(1f).semantics { selected = sheet.sensitivity == option }, primary = sheet.sensitivity == option,
                tag = "ce-scratch-${option.name.lowercase()}")
        }
    }
}

@Composable private fun CERecordFace(fraction: Float, ready: Boolean, active: Boolean, accent: Color, label: String, modifier: Modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 280.dp)
        Box(modifier.size(side).clip(CircleShape).background(CEColor.Deep)
            .border(4.dp, if (active) accent else CEColor.Border, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val radius = size.minDimension / 2
                for (ring in 1..10) drawCircle(if (ring % 2 == 0) CEColor.Border else CEColor.Empty, radius * (1f - ring * .064f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                val angle = fraction * 2.0 * kotlin.math.PI - kotlin.math.PI / 2
                drawLine(if (ready) accent else CEColor.Border, center,
                    Offset(center.x + (kotlin.math.cos(angle) * radius * .88).toFloat(), center.y + (kotlin.math.sin(angle) * radius * .88).toFloat()), 5f)
                drawCircle(if (ready) accent else CEColor.Border, radius * .28f)
            }
            Text(label, color = CEColor.Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
