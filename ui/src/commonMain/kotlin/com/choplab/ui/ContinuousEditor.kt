@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.choplab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** User-selected four-stage workspace. This is intentionally not wired into a default host here. */
@Composable fun ContinuousEditor(
    state: ContinuousEditorState,
    onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout = { ContinuousEditorReadout() },
    refreshKey: Long = 0,
    modifier: Modifier = Modifier,
) {
    CETheme {
        BoxWithConstraints(modifier.fillMaxSize().background(CEColor.Ink).padding(8.dp).clip(RoundedCornerShape(16.dp)).background(CEColor.Cream)) {
            val compact = maxWidth < 900.dp
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CEHeader(state, onAction, compact)
                if (state.stage == ContinuousStage.BEAT) CEOriginalDock(state, onAction, readout, refreshKey, compact)
                Box(Modifier.weight(1f).fillMaxWidth().testTag("ce-stage-${state.stage.name}")) {
                    when (state.stage) {
                        ContinuousStage.CAPTURE -> CECapture(state, onAction, readout, refreshKey)
                        ContinuousStage.CHOP -> CEChop(state, onAction, readout, refreshKey, compact)
                        ContinuousStage.BEAT -> CEBeatWorkspace(state, onAction, readout, refreshKey, compact)
                        ContinuousStage.SAVE -> CESave(state, onAction, compact)
                    }
                }
                if (state.stage == ContinuousStage.BEAT || state.stage == ContinuousStage.SAVE) CESongTransport(state, onAction, readout, refreshKey)
                CEStatus(state.status)
            }
        }
    }
}

@Composable private fun CEHeader(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, compact: Boolean) {
    @Composable fun Brand() { Column {
        Text(stringResource(Res.string.ce_brand), color = CEColor.Cream, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(stringResource(Res.string.ce_subbrand), color = CEColor.Tan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    } }
    @Composable fun Stages(modifier: Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContinuousStage.entries.forEach { stage ->
                val title = stringResource(when (stage) {
                    ContinuousStage.CAPTURE -> Res.string.ce_stage_capture; ContinuousStage.CHOP -> Res.string.ce_stage_chop
                    ContinuousStage.BEAT -> Res.string.ce_stage_beat; ContinuousStage.SAVE -> Res.string.ce_stage_save
                })
                CEButton(title, { onAction(ContinuousEditorAction.Navigate(stage)) }, Modifier.weight(1f)
                    .semantics { selected = stage == state.stage }, primary = stage == state.stage, tag = "ce-nav-${stage.name}")
            }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 70.dp).clip(RoundedCornerShape(8.dp)).background(CEColor.Ink).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Brand()
                CEActionButton(stringResource(Res.string.ce_stop_all), ContinuousEditorAction.StopAll, state, ContinuousCapability.STOP_ALL, onAction, primary = true, tag = "ce-stop-all")
            }
            Box(Modifier.horizontalScroll(rememberScrollState())) { Stages(Modifier.widthIn(min = 420.dp)) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(160.dp)) { Brand() }
            Stages(Modifier.weight(1f))
            Text("${stringResource(Res.string.ce_bpm)} ${state.bpm}", color = CEColor.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            CEActionButton(stringResource(Res.string.ce_stop_all), ContinuousEditorAction.StopAll, state, ContinuousCapability.STOP_ALL, onAction, Modifier.widthIn(min = 100.dp), primary = true, tag = "ce-stop-all")
        }
    }
}

@Composable private fun CEOriginalDock(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, compact: Boolean) {
    val original = state.original
    val live = CELive(state.originalPlaying, refreshKey, readout)
    val title = original?.title ?: stringResource(Res.string.ce_no_source)
    val waveLabel = stringResource(Res.string.ce_original_wave, title)
    Column(Modifier.fillMaxWidth().heightIn(min = 74.dp).clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(if (compact) 108.dp else 168.dp)) {
                Text(stringResource(Res.string.ce_source_prefix, title), color = CEColor.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                CEOriginalTime(state, readout, refreshKey)
            }
            CEWaveform(original?.peaks.orEmpty(), Modifier.weight(1f).height(56.dp), waveLabel,
                position = { if (original == null) 0f else live.value.originalFrame.toFloat() / original.frames },
                onSeek = if (original != null && state.permits(ContinuousCapability.ORIGINAL_SEEK)) ({ fraction -> onAction(ContinuousEditorAction.SeekOriginal((fraction * original.frames).roundToLong())) }) else null,
                tag = "ce-original-wave")
            if (!compact) {
                CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_stop else Res.string.ce_play_original),
                    if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                    state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, Modifier.widthIn(min = 100.dp), tag = "ce-original-play")
                CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
                    { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.width(190.dp), tag = "ce-source-monitor")
                CEButton(stringResource(Res.string.ce_standard_width), { onAction(ContinuousEditorAction.ResetPanes) }, Modifier.widthIn(min = 78.dp), tag = "ce-reset-panes")
            }
        }
        if (compact && LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth()) {
            CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_stop else Res.string.ce_play_original),
                if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, tag = "ce-original-play")
            CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
                { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.fillMaxWidth(), tag = "ce-source-monitor")
        } else if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_stop else Res.string.ce_play_original),
                if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, tag = "ce-original-play")
            CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
                { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.weight(1f), tag = "ce-source-monitor")
        }
    }
}

@Composable private fun CEOriginalTime(state: ContinuousEditorState, readout: () -> ContinuousEditorReadout, refreshKey: Long, full: Boolean = false) {
    val live by CELive(state.originalPlaying, refreshKey, readout)
    val source = state.original
    val time = ceTime(live.originalFrame, source?.sampleRate ?: 48_000, full)
    Text(if (full && source != null) "$time / ${ceTime(source.frames, source.sampleRate, true)}" else time,
        color = CEColor.Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

@Composable private fun CECapture(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val waveHeight = (maxHeight * .37f).coerceAtLeast(220.dp)
        val wideTransport = maxWidth >= 900.dp && LocalDensity.current.fontScale <= 1.3f
        val original = state.original
        val live = CELive(state.originalPlaying, refreshKey, readout)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(original?.title ?: stringResource(Res.string.ce_no_source), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CEActionButton(stringResource(Res.string.ce_load_audio), ContinuousEditorAction.ImportAudio, state, ContinuousCapability.IMPORT_AUDIO, onAction, Modifier.weight(1f), tag = "ce-import")
                CEActionButton(stringResource(Res.string.ce_open_project), ContinuousEditorAction.OpenProject, state, ContinuousCapability.OPEN_PROJECT, onAction, Modifier.weight(1f), tag = "ce-open")
            }
            CEWaveform(original?.peaks.orEmpty(), Modifier.fillMaxWidth().height(waveHeight),
                stringResource(Res.string.ce_original_wave, original?.title.orEmpty()),
                position = { if (original == null) 0f else live.value.originalFrame.toFloat() / original.frames },
                onSeek = if (original != null && state.permits(ContinuousCapability.ORIGINAL_SEEK)) ({ onAction(ContinuousEditorAction.SeekOriginal((it * original.frames).roundToLong())) }) else null,
                tag = "ce-original-wave")
            CEOriginalTime(state, readout, refreshKey, true)
            if (wideTransport) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(.55f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEActionButton(stringResource(Res.string.ce_top), ContinuousEditorAction.SeekOriginal(0), state, ContinuousCapability.ORIGINAL_SEEK, onAction, Modifier.weight(1f))
                    CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_pause else Res.string.ce_play_song_source),
                        if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                        state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, Modifier.weight(1.85f), primary = true, tag = "ce-original-play")
                    CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopOriginal, state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, Modifier.weight(1f))
                }
                Row(Modifier.weight(.45f).clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.ce_song_key), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.ce_semitones, (if ((original?.pitchSemitones ?: 0f) >= 0) "+" else "") + (original?.pitchSemitones ?: 0f).roundToInt()), Modifier.weight(1f), fontSize = 14.sp)
                    CEActionButton("−", ContinuousEditorAction.SetOriginalPitch(((original?.pitchSemitones ?: 0f) - 1).coerceAtLeast(-24f)), state, ContinuousCapability.ORIGINAL_PITCH, onAction)
                    CEActionButton("+", ContinuousEditorAction.SetOriginalPitch(((original?.pitchSemitones ?: 0f) + 1).coerceAtMost(24f)), state, ContinuousCapability.ORIGINAL_PITCH, onAction)
                }
            } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CEActionButton(stringResource(Res.string.ce_top), ContinuousEditorAction.SeekOriginal(0), state, ContinuousCapability.ORIGINAL_SEEK, onAction)
                CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_pause else Res.string.ce_play_song_source),
                    if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                    state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, primary = true, tag = "ce-original-play")
                CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopOriginal, state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction)
                CEAdjustment(stringResource(Res.string.ce_song_key), original?.pitchSemitones ?: 0f,
                    state, ContinuousCapability.ORIGINAL_PITCH, { onAction(ContinuousEditorAction.SetOriginalPitch(it)) }, true)
            }
            CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
                { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.fillMaxWidth(), tag = "ce-source-monitor")
            Text(stringResource(Res.string.ce_capture_hint), fontSize = 14.sp, color = CEColor.Border)
            CEButton(stringResource(Res.string.ce_to_chop), { onAction(ContinuousEditorAction.Navigate(ContinuousStage.CHOP)) }, Modifier.fillMaxWidth(), primary = true)
            HorizontalDivider(color = CEColor.Border.copy(alpha = .4f))
            Text(stringResource(Res.string.ce_other_import), color = CEColor.Border, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CEButton(stringResource(Res.string.ce_mic_record), {}, Modifier.weight(1f), enabled = false, reason = stringResource(Res.string.ce_unavailable))
                CEButton(stringResource(Res.string.ce_device_record), {}, Modifier.weight(1f), enabled = false, reason = stringResource(Res.string.ce_unavailable))
            }
        }
    }
}

@Composable private fun CEChop(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, compact: Boolean) {
    if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CEChopSource(state, onAction, readout, refreshKey, Modifier.fillMaxWidth(), 240.dp)
        CEBanks(state, onAction)
        CEPads(state, onAction, maximumSide = 140.dp)
    } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(.58f).fillMaxHeight()) {
            CEChopSource(state, onAction, readout, refreshKey, Modifier.fillMaxSize(), null)
        }
        Column(Modifier.weight(.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CEBanks(state, onAction)
            Text(stringResource(Res.string.ce_chop_hint), fontSize = 12.sp, color = CEColor.Border)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CEPads(state, onAction, maximumSide = 160.dp) }
        }
    }
}

@Composable private fun CEChopSource(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, modifier: Modifier, fixedWaveHeight: androidx.compose.ui.unit.Dp?) {
    val original = state.original
    val live = CELive(state.originalPlaying, refreshKey, readout)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(original?.title ?: stringResource(Res.string.ce_no_source), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        CEWaveform(original?.peaks.orEmpty(), Modifier.fillMaxWidth().then(if (fixedWaveHeight == null) Modifier.weight(1f) else Modifier.height(fixedWaveHeight)),
            stringResource(Res.string.ce_original_wave, original?.title.orEmpty()), position = { if (original == null) 0f else live.value.originalFrame.toFloat() / original.frames },
            onSeek = if (original != null && state.permits(ContinuousCapability.ORIGINAL_SEEK)) ({ onAction(ContinuousEditorAction.SeekOriginal((it * original.frames).roundToLong())) }) else null,
            tag = "ce-original-wave")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(Res.string.ce_chop_start), ContinuousEditorAction.BeginLiveChop, state, ContinuousCapability.LIVE_CHOP, onAction, Modifier.weight(1f))
            CEActionButton(stringResource(Res.string.ce_play_from_start), ContinuousEditorAction.SeekOriginal(0), state, ContinuousCapability.ORIGINAL_SEEK, onAction, Modifier.weight(1f))
            CEActionButton(stringResource(Res.string.ce_add_audio), ContinuousEditorAction.ImportAudio, state, ContinuousCapability.IMPORT_AUDIO, onAction, Modifier.weight(1f))
        }
        CEAdjustment(stringResource(Res.string.ce_original_key), original?.pitchSemitones ?: 0f, state, ContinuousCapability.ORIGINAL_PITCH,
            { onAction(ContinuousEditorAction.SetOriginalPitch(it)) }, true, Modifier.fillMaxWidth())
        CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
            { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.fillMaxWidth(), tag = "ce-source-monitor")
        Text(stringResource(Res.string.ce_chop_hint), fontSize = 12.sp, color = CEColor.Border)
        val frames = original?.frames ?: 1
        fun framesOf(range: ClosedFloatingPointRange<Float>): Pair<Long, Long> {
            val a = (range.start * frames).roundToLong().coerceIn(0, frames - 1)
            return a to (range.endInclusive * frames).roundToLong().coerceIn(a + 1, frames)
        }
        // A drag previews locally and commits one range edit (one Undo step) when released. The preview
        // stays until the committed range arrives, or is dropped if the edit fails.
        var pending by remember(original?.id, original?.rangeStartFrame, original?.rangeEndFrame) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
        LaunchedEffect(state.status) { if (state.status == ContinuousStatus.FAILED) pending = null }
        val (start, end) = pending?.let(::framesOf) ?: ((original?.rangeStartFrame ?: 0L) to (original?.rangeEndFrame ?: 0L))
        Text(stringResource(Res.string.ce_range, ceTime(start, original?.sampleRate ?: 48_000, true), ceTime(end, original?.sampleRate ?: 48_000, true)), fontSize = 12.sp)
        RangeSlider(pending ?: (start.toFloat() / frames)..(end.toFloat() / frames), { pending = it },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ce-source-range"), enabled = original != null && state.permits(ContinuousCapability.SOURCE_RANGE),
            onValueChangeFinished = { pending?.let(::framesOf)?.let { (a, b) -> onAction(ContinuousEditorAction.SetSourceRange(a, b)) } },
            colors = SliderDefaults.colors(thumbColor = CEColor.Orange, activeTrackColor = CEColor.Orange, inactiveTrackColor = CEColor.Tan))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(Res.string.ce_save_cut), ContinuousEditorAction.AssignSourceRange(state.selectedPadId), state,
                ContinuousCapability.ASSIGN_SOURCE_RANGE, onAction, Modifier.weight(1f), tag = "ce-assign")
            CEActionButton(stringResource(Res.string.ce_auto_chop), ContinuousEditorAction.AutoChop, state, ContinuousCapability.AUTO_CHOP, onAction, Modifier.weight(1f))
            CEButton(stringResource(Res.string.ce_to_beat), { onAction(ContinuousEditorAction.Navigate(ContinuousStage.BEAT)) }, Modifier.weight(1f), primary = true)
        }
    }
}

@Composable internal fun CEAdjustment(label: String, value: Float, state: ContinuousEditorState, capability: ContinuousCapability,
    onValue: (Float) -> Unit, semitones: Boolean = false, modifier: Modifier = Modifier) {
    val decrease = stringResource(Res.string.ce_decrease, label)
    val increase = stringResource(Res.string.ce_increase, label)
    Column(modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (semitones) stringResource(Res.string.ce_semitones, (if (value >= 0) "+" else "") + value.roundToInt()) else stringResource(Res.string.ce_percent, (value * 100).roundToInt()),
                Modifier.weight(1f, fill = false).widthIn(min = 56.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            CEButton("−", { onValue(if (semitones) (value - 1).coerceAtLeast(-24f) else (value - .05f).coerceAtLeast(0f)) },
                enabled = state.permits(capability), reason = CEReason(state, capability), modifier = Modifier.semantics { contentDescription = decrease })
            CEButton("+", { onValue(if (semitones) (value + 1).coerceAtMost(24f) else (value + .05f).coerceAtMost(2f)) },
                enabled = state.permits(capability), reason = CEReason(state, capability), modifier = Modifier.semantics { contentDescription = increase })
        }
    }
}

@Composable private fun CESave(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, compact: Boolean) {
    @Composable fun SaveCard(audio: Boolean, modifier: Modifier) {
        val foreground = if (audio) CEColor.Cream else CEColor.Ink
        Column(modifier.clip(RoundedCornerShape(8.dp)).background(if (audio) CEColor.Ink else CEColor.Tan)
            .border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(if (audio) Res.string.ce_save_audio else Res.string.ce_save_edit), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = foreground)
            Text(stringResource(if (audio) Res.string.ce_save_audio_hint else Res.string.ce_save_edit_hint), fontSize = 14.sp, color = if (audio) CEColor.Tan else CEColor.Border)
            CEActionButton(stringResource(if (audio) Res.string.ce_export_wav else Res.string.ce_save_project),
                if (audio) ContinuousEditorAction.ExportWav else ContinuousEditorAction.SaveProject,
                state, if (audio) ContinuousCapability.EXPORT_WAV else ContinuousCapability.SAVE_PROJECT,
                onAction, Modifier.fillMaxWidth(), primary = true, tag = if (audio) "ce-export" else "ce-save")
            Text(stringResource(if (audio) Res.string.ce_wav_format else Res.string.ce_project_format), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (audio) CEColor.Tan else CEColor.Border)
            if (audio) Text(stringResource(Res.string.ce_export_monitor_hint), fontSize = 13.sp, color = CEColor.Tan)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(Res.string.ce_save_heading), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.ce_save_summary, state.clips.size, state.tracks.size, ceTime(state.timelineDurationFrames)), fontSize = 14.sp)
        Text(stringResource(Res.string.ce_save_hint), fontSize = 14.sp, color = CEColor.Border)
        if (compact) { SaveCard(false, Modifier.fillMaxWidth()); SaveCard(true, Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { SaveCard(false, Modifier.weight(1f)); SaveCard(true, Modifier.weight(1f)) }
        CEActionButton(stringResource(Res.string.ce_reopen), ContinuousEditorAction.OpenProject, state, ContinuousCapability.OPEN_PROJECT, onAction, Modifier.fillMaxWidth(), tag = "ce-reopen")
    }
}

@Composable internal fun CESongTransport(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val live by CELive(state.songPlaying, refreshKey, readout)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stretch = maxWidth >= 1000.dp && LocalDensity.current.fontScale <= 1.3f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CEColor.Ink)
        .then(if (stretch) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.ce_song), color = CEColor.Cream, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        CEActionButton(stringResource(Res.string.ce_top), ContinuousEditorAction.SeekSong(0), state, ContinuousCapability.SONG_SEEK, onAction, dark = true)
        CEActionButton(stringResource(if (state.songPlaying) Res.string.ce_pause else Res.string.ce_play),
            if (state.songPlaying) ContinuousEditorAction.PauseSong else ContinuousEditorAction.PlaySong,
            state, ContinuousCapability.SONG_PLAYBACK, onAction, primary = true, tag = "ce-song-play")
        CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopSong, state, ContinuousCapability.SONG_PLAYBACK, onAction, dark = true)
        Text("${ceTime(live.songFrame)} / ${ceTime(state.timelineDurationFrames)}", color = CEColor.Cream, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Slider((live.songFrame.toFloat() / state.timelineDurationFrames).coerceIn(0f, 1f), { onAction(ContinuousEditorAction.SeekSong((it * state.timelineDurationFrames).roundToLong())) },
            (if (stretch) Modifier.weight(1f) else Modifier.width(220.dp)).heightIn(min = 48.dp).testTag("ce-song-seek"), enabled = state.permits(ContinuousCapability.SONG_SEEK),
            colors = SliderDefaults.colors(thumbColor = CEColor.Orange, activeTrackColor = CEColor.Orange, inactiveTrackColor = CEColor.Tan))
        CEValueSlider(stringResource(Res.string.ce_song_gain), state.songMonitorGain, state, ContinuousCapability.SONG_MONITOR_GAIN,
            { onAction(ContinuousEditorAction.SetSongMonitorGain(it)) }, Modifier.width(if (stretch) 220.dp else 260.dp), dark = true, tag = "ce-song-monitor")
        CETempo(state, onAction)
    }
    }
}

@Composable private fun CETempo(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var value by remember(state.bpm) { mutableStateOf(state.bpm.toString()) }
    CEButton("${state.bpm} ${stringResource(Res.string.ce_bpm)}", { value = state.bpm.toString(); open = true },
        enabled = state.permits(ContinuousCapability.TEMPO), dark = true, reason = CEReason(state, ContinuousCapability.TEMPO), tag = "ce-tempo")
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.ce_bpm)) },
        text = { Column { Text(stringResource(Res.string.ce_tempo_hint)); OutlinedTextField(value, { value = it.filter(Char::isDigit).take(3) }, singleLine = true) } },
        confirmButton = { CEButton(stringResource(Res.string.ce_apply), { value.toIntOrNull()?.let { onAction(ContinuousEditorAction.SetTempo(it)); open = false } }, enabled = (value.toIntOrNull() ?: -1) in 40..240) },
        dismissButton = { CEButton(stringResource(Res.string.ce_close), { open = false }) })
}

@Composable private fun CEStatus(status: ContinuousStatus?) {
    val text = status?.let { stringResource(when (it) {
        ContinuousStatus.LOADING -> Res.string.ce_loading; ContinuousStatus.SAVING -> Res.string.ce_saving
        ContinuousStatus.SAVED -> Res.string.ce_saved; ContinuousStatus.EXPORTING -> Res.string.ce_exporting
        ContinuousStatus.EXPORTED -> Res.string.ce_exported; ContinuousStatus.CANCELLED -> Res.string.ce_cancelled
        ContinuousStatus.FAILED -> Res.string.ce_failed; ContinuousStatus.NO_OUTPUT -> Res.string.ce_no_output
    }) }.orEmpty()
    Text(text, Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp, color = CEColor.Border)
}
