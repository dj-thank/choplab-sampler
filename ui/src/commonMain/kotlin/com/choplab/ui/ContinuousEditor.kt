@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.choplab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.ui.resources.*
import com.choplab.ui.ai.VocalGuideController
import com.choplab.ui.ai.LyricProposalController
import com.choplab.ui.pattern.StepPatternController
import com.choplab.ui.source.OnlineSourceController
import com.choplab.ui.source.OnlineSourceDialog
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** User-selected four-stage workspace shared by the NEXT hosts. */
@Composable fun ContinuousEditor(
    state: ContinuousEditorState,
    onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout = { ContinuousEditorReadout() },
    refreshKey: Long = 0,
    modifier: Modifier = Modifier,
    /** Output health for the SAVE stage's diagnostics card; without it the card is not shown. */
    diagnostics: (() -> ContinuousDiagnostics?)? = null,
    mixerReadout: ((com.choplab.engine.MixerSnapshot) -> Boolean)? = null,
    lyricProposal: LyricProposalController? = null,
    stepPatterns: StepPatternController? = null,
    vocalGuide: VocalGuideController? = null,
    fourStems: com.choplab.ui.separation.FourStemController? = null,
    onlineSource: OnlineSourceController? = null,
    vocalTakes: com.choplab.ui.vocal.VocalTakeController? = null,
    vocalPunch: com.choplab.ui.vocal.VocalPunchController? = null,
) {
    CETheme {
        BoxWithConstraints(modifier.fillMaxSize().background(CEColor.Ink).padding(8.dp).clip(RoundedCornerShape(16.dp)).background(CEColor.Cream)) {
            val compact = maxWidth < 900.dp
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CEHeader(state, onAction, compact, readout, refreshKey)
                if (state.vocalPreview) Text(stringResource(Res.string.vocal_preview_source_owned), Modifier.testTag("ce-vocal-preview-owner"))
                if (compact && state.stage == ContinuousStage.BEAT) {
                    // A short window scrolls the source and instrument together. Transport and all-stop stay outside.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("ce-beat-viewport")) {
                        val density = LocalDensity.current
                        var sourceHeight by remember { mutableStateOf(128.dp) }
                        val stageHeight = (maxHeight - sourceHeight - 64.dp).coerceAtLeast(480.dp)
                        val maximumPadSide = (maxHeight - 16.dp).coerceAtLeast(64.dp)
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("ce-beat-scroll"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CEOriginalDock(state, onAction, readout, refreshKey, compact = true,
                                modifier = Modifier.onSizeChanged { sourceHeight = with(density) { it.height.toDp() } })
                            Box(Modifier.fillMaxWidth().then(if (state.compactPane == ContinuousPane.TIMELINE) Modifier.height(stageHeight) else Modifier)
                                .testTag("ce-stage-BEAT")) {
                                CEBeatWorkspace(state, onAction, readout, refreshKey, compact = true,
                                    maximumPadSide = maximumPadSide)
                            }
                            CEStatus(state.status)
                        }
                    }
                } else {
                    if (state.stage == ContinuousStage.BEAT) CEOriginalDock(state, onAction, readout, refreshKey, compact = false)
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("ce-stage-${state.stage.name}")) {
                        when (state.stage) {
                            ContinuousStage.CAPTURE -> CECapture(state, onAction, readout, refreshKey)
                            ContinuousStage.CHOP -> CEChop(state, onAction, readout, refreshKey, compact)
                            ContinuousStage.BEAT -> CEBeatWorkspace(state, onAction, readout, refreshKey, compact)
                            ContinuousStage.SAVE -> CESave(state, onAction, compact, diagnostics)
                        }
                    }
                }
                if (state.stage == ContinuousStage.BEAT || state.stage == ContinuousStage.SAVE) CESongTransport(state, onAction, readout, refreshKey)
                if (!compact || state.stage != ContinuousStage.BEAT) CEStatus(state.status)
            }
        }
        CEDrumKitDialogs(state, onAction)
        CEPadPlayDialog(state, onAction)
        CEScratchPanel(state, onAction, readout, refreshKey)
        com.choplab.ui.mixer.CEMixerPanel(state.mixer, { onAction(ContinuousEditorAction.Mixer(it)) },
            when (state.bankPadBlocked) {
                BankPadEditProblem.RECORDING -> com.choplab.ui.mixer.MixerProblem.RECORDING
                null -> null
                else -> com.choplab.ui.mixer.MixerProblem.BUSY
            }, { onAction(ContinuousEditorAction.StopAll) }, mixerReadout)
        CELyricsPanel(state, onAction, readout, refreshKey)
        lyricProposal?.let { CELyricProposalDialog(it, onAction) }
        stepPatterns?.let { CEStepPatternsDialog(it, onAction) }
        vocalTakes?.let { CEVocalTakeDialog(it, onAction) }
        vocalPunch?.let { CEVocalPunchDialog(it, onAction) }
        vocalGuide?.let { CEVocalGuideDialog(it, onAction) }
        fourStems?.let { controller -> com.choplab.ui.separation.FourStemDialog(controller,
            onStop = { onAction(ContinuousEditorAction.StopAll) }, onClose = { onAction(ContinuousEditorAction.CloseFourStems) }) }
        onlineSource?.let { OnlineSourceDialog(it) { onAction(ContinuousEditorAction.CloseOnline) } }
        CEBankPadEditor(state.bankPadEditor, { onAction(ContinuousEditorAction.BankPadEdit(it)) }, state.bankPadBlocked) {
            onAction(ContinuousEditorAction.StopAll)
        }
    }
}

/**
 * The selected PAD's settings, as the earlier app's TRIM and PLAY pages: where it starts and ends, reverse, once or
 * while held, choke group, and clearing it. Clearing asks for a second press, and that press counts only for the PAD
 * and sound it was armed for.
 */
/**
 * Repeats the clips of a few bars from the bar holding [from], the song position when it was opened, into the empty bars
 * right after them; applying is one Undo.
 */
@Composable internal fun CERepeatDialog(state: ContinuousEditorState, from: Long, onAction: (ContinuousEditorAction) -> Unit, close: () -> Unit) {
    var bars by remember { mutableIntStateOf(4) }
    var times by remember { mutableIntStateOf(1) }
    val first = ContinuousClipEdits.barAt(from, state.tempo) + 1
    val section = ContinuousClipEdits.barsFrames(from, state.tempo, bars)
    // The same checks as the edit, so the panel says what applying does or why it cannot.
    val copied = state.clips.filter { it.timelineStartFrame in section && ContinuousClipEdits.sounds(it) }
    val count = copied.size
    val outlasting = copied.any { it.timelineDurationFrames > section.last + 1 - section.first }
    val after = ContinuousClipEdits.barsFrames(from, state.tempo, bars, 1).first..ContinuousClipEdits.barsFrames(from, state.tempo, bars, times).last
    val occupied = state.clips.any { it.timelineStartFrame in after }
    val (fromBar, toBar) = first + bars to first + bars.toLong() * (times + 1) - 1
    val refused = count == 0 || outlasting || occupied
    val plan = when {
        count == 0 -> stringResource(Res.string.ce_repeat_empty)
        occupied && fromBar == toBar -> stringResource(Res.string.ce_repeat_occupied_one, fromBar)
        occupied -> stringResource(Res.string.ce_repeat_occupied, fromBar, toBar)
        outlasting -> stringResource(Res.string.ce_repeat_outlasting)
        fromBar == toBar -> stringResource(Res.string.ce_repeat_plan_one, count, fromBar)
        else -> stringResource(Res.string.ce_repeat_plan, count, fromBar, toBar)
    }
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-repeat-panel"),
        title = { Text(stringResource(Res.string.ce_repeat_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun Choice(label: String, chosen: Boolean, tag: String, modifier: Modifier, choose: () -> Unit) =
                    CEButton(label, choose, modifier.semantics { selected = chosen }, primary = chosen, tag = tag)
                @Composable fun Choices(values: List<Int>, chosen: Int, tag: String, label: @Composable (Int) -> String, choose: (Int) -> Unit) =
                    // All four in one row where they fit, otherwise two rows (phones, large text).
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            values.forEach { value -> Choice(label(value), chosen == value, "$tag-$value", Modifier.weight(1f)) { choose(value) } }
                        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            values.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pair.forEach { value -> Choice(label(value), chosen == value, "$tag-$value", Modifier.weight(1f)) { choose(value) } }
                            } }
                        }
                    }
                Text(stringResource(Res.string.ce_pad_fill_from, first), Modifier.testTag("ce-repeat-from"), fontSize = 14.sp)
                Text(stringResource(Res.string.ce_pad_fill_length), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Choices(listOf(1, 2, 4, 8), bars, "ce-repeat-bars", { if (it == 1) stringResource(Res.string.ce_fill_one_bar) else stringResource(Res.string.ce_fill_bars, it) }) { bars = it }
                Text(stringResource(Res.string.ce_repeat_times), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Choices(listOf(1, 2, 4, 8), times, "ce-repeat-times", { stringResource(Res.string.ce_repeat_count, it) }) { times = it }
                // What applying would do, or why it cannot, said before it is pressed.
                Text(plan, Modifier.testTag("ce-repeat-plan").semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (refused) FontWeight.Bold else FontWeight.Normal)
                Text(stringResource(Res.string.ce_repeat_help), fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_repeat), {
            onAction(ContinuousEditorAction.RepeatBars(from, bars, times)); close()
        }, enabled = !refused && state.permits(ContinuousCapability.DUPLICATE_CLIP), primary = true,
            // Refused for the bars, the button says why as the panel does.
            reason = if (state.permits(ContinuousCapability.DUPLICATE_CLIP)) plan else CEReason(state, ContinuousCapability.DUPLICATE_CLIP),
            tag = "ce-repeat-apply") },
        dismissButton = { CEButton(stringResource(Res.string.ce_close), close) })
}

/**
 * Fills the song with the selected PAD every beat, half or quarter beat through a few bars from the bar holding [from],
 * the song position when it was opened; applying is one Undo.
 */
@Composable internal fun CEPadFillDialog(state: ContinuousEditorState, pad: ContinuousPad, from: Long,
                                         onAction: (ContinuousEditorAction) -> Unit, close: () -> Unit) {
    var spacing by remember { mutableStateOf(ContinuousGrid.BEAT) }
    var bars by remember { mutableIntStateOf(4) }
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-pad-fill-panel"),
        title = { Text(stringResource(Res.string.ce_pad_fill_title, cePadName(pad.id))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun Choice(label: String, chosen: Boolean, tag: String, modifier: Modifier, choose: () -> Unit) =
                    CEButton(label, choose, modifier.semantics { selected = chosen }, primary = chosen, tag = tag)
                Text(stringResource(Res.string.ce_pad_fill_from, ContinuousClipEdits.barAt(from, state.tempo) + 1),
                    Modifier.testTag("ce-pad-fill-from"), fontSize = 14.sp)
                Text(stringResource(Res.string.ce_pad_fill_spacing), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((grid, label) in listOf(ContinuousGrid.BEAT to Res.string.ce_fill_quarters, ContinuousGrid.HALF to Res.string.ce_fill_eighths,
                            ContinuousGrid.QUARTER to Res.string.ce_fill_sixteenths)) {
                        Choice(stringResource(label), spacing == grid, "ce-fill-${grid.name.lowercase()}", Modifier.weight(1f)) { spacing = grid }
                    }
                }
                Text(stringResource(Res.string.ce_pad_fill_length), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                @Composable fun Length(count: Int, modifier: Modifier) = Choice(if (count == 1) stringResource(Res.string.ce_fill_one_bar)
                    else stringResource(Res.string.ce_fill_bars, count), bars == count, "ce-fill-bars-$count", modifier) { bars = count }
                // All four in one row where they fit, otherwise two rows (phones, large text).
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (count in listOf(1, 2, 4, 8)) Length(count, Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Length(1, Modifier.weight(1f)); Length(2, Modifier.weight(1f)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Length(4, Modifier.weight(1f)); Length(8, Modifier.weight(1f)) }
                    }
                }
                Text(stringResource(Res.string.ce_pad_fill_help), fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_pad_fill), {
            onAction(ContinuousEditorAction.FillPad(pad.id, state.selectedTrackId, from, spacing, bars)); close()
        }, enabled = state.permits(ContinuousCapability.PLACE_PAD), primary = true, reason = CEReason(state, ContinuousCapability.PLACE_PAD),
            tag = "ce-pad-fill-apply") },
        dismissButton = { CEButton(stringResource(Res.string.ce_close), close) })
}

@Composable private fun CEPadPlayDialog(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    val pad = state.selectedPad?.takeIf { state.padPlayOpen } ?: return
    val id = pad.id
    val close = { onAction(ContinuousEditorAction.ClosePadPlay) }
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("ce-pad-play-panel"),
        title = { Text(stringResource(Res.string.ce_pad_play_title, cePadName(id))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun Choice(label: String, chosen: Boolean, tag: String, modifier: Modifier, choose: () -> Unit) =
                    CEButton(label, choose, modifier.semantics { selected = chosen }, primary = chosen, tag = tag)
                Text(stringResource(Res.string.ce_pad_trim), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                for (end in listOf(false, true)) {
                    val boundary = stringResource(if (end) Res.string.ce_pad_trim_end else Res.string.ce_pad_trim_start)
                    val frame = if (end) pad.sourceEndFrame else pad.sourceStartFrame
                    Text("$boundary  ${ceTimeMillis(frame, pad.sourceRate)}", Modifier.testTag("ce-trim-${if (end) "end" else "start"}-time"), fontSize = 14.sp)
                    @Composable fun Nudge(milliseconds: Int, modifier: Modifier) {
                        val description = stringResource(if (milliseconds < 0) Res.string.ce_pad_trim_earlier else Res.string.ce_pad_trim_later,
                            boundary, kotlin.math.abs(milliseconds))
                        CEButton(if (milliseconds < 0) "−${-milliseconds}ms" else "+${milliseconds}ms",
                            { onAction(ContinuousEditorAction.NudgePadBoundary(id, end, milliseconds)) },
                            modifier.semantics { contentDescription = description },
                            tag = "ce-trim-${if (end) "end" else "start"}-${if (milliseconds < 0) "earlier" else "later"}-${kotlin.math.abs(milliseconds)}")
                    }
                    // Earlier and later in one row where they fit, otherwise one row each (phones, large text).
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (milliseconds in listOf(-10, -1, 1, 10)) Nudge(milliseconds, Modifier.weight(1f))
                        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Nudge(-10, Modifier.weight(1f)); Nudge(-1, Modifier.weight(1f)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Nudge(1, Modifier.weight(1f)); Nudge(10, Modifier.weight(1f)) }
                        }
                    }
                }
                Text(stringResource(Res.string.ce_pad_trim_help), fontSize = 12.sp, lineHeight = 18.sp, color = CEColor.Border)
                CEActionButton(stringResource(Res.string.ce_audition), ContinuousEditorAction.TapPad(id), state, ContinuousCapability.PAD_AUDITION,
                    onAction, Modifier.fillMaxWidth(), tag = "ce-trim-audition")
                Text(stringResource(Res.string.ce_reverse), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice(stringResource(Res.string.ce_off), !pad.reverse, "ce-reverse-off", Modifier.weight(1f)) {
                        onAction(ContinuousEditorAction.SetPadReverse(id, false)) }
                    Choice(stringResource(Res.string.ce_on), pad.reverse, "ce-reverse-on", Modifier.weight(1f)) {
                        onAction(ContinuousEditorAction.SetPadReverse(id, true)) }
                }
                Text(stringResource(Res.string.ce_play_mode), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice(stringResource(Res.string.ce_play_once), pad.mode == ContinuousPadMode.ONE_SHOT, "ce-mode-once", Modifier.weight(1f)) {
                        onAction(ContinuousEditorAction.SetPadMode(id, ContinuousPadMode.ONE_SHOT)) }
                    Choice(stringResource(Res.string.ce_play_held), pad.mode == ContinuousPadMode.GATE, "ce-mode-held", Modifier.weight(1f)) {
                        onAction(ContinuousEditorAction.SetPadMode(id, ContinuousPadMode.GATE)) }
                }
                Text(stringResource(Res.string.ce_play_mode_help), fontSize = 12.sp, lineHeight = 18.sp, color = CEColor.Border)
                Text(stringResource(Res.string.ce_choke), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (group in 0..4) Choice(if (group == 0) stringResource(Res.string.ce_choke_none) else "$group",
                        pad.chokeGroup == group, "ce-choke-$group", Modifier.weight(if (group == 0) 1.6f else 1f)) {
                        onAction(ContinuousEditorAction.SetPadChoke(id, group)) }
                }
                Text(stringResource(Res.string.ce_choke_help), fontSize = 12.sp, lineHeight = 18.sp, color = CEColor.Border)
                var armed by remember(pad) { mutableStateOf(false) }
                CEButton(stringResource(if (armed) Res.string.ce_clear_pad_confirm else Res.string.ce_clear_pad),
                    { if (armed) onAction(ContinuousEditorAction.ClearPad(id)) else armed = true }, Modifier.fillMaxWidth(),
                    primary = armed, tag = "ce-clear-pad")
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_close), close, tag = "ce-pad-play-close") })
}

@Composable private fun CEDrumKitDialogs(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    if (state.drumKitChooserOpen) AlertDialog(onDismissRequest = { onAction(ContinuousEditorAction.DismissDrumKit) },
        modifier = Modifier.testTag("ce-kit-chooser"), title = { Text(stringResource(Res.string.ce_kit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.ce_kit_hint), fontSize = 14.sp, lineHeight = 20.sp)
                val inUse = stringResource(Res.string.ce_kit_in_use)
                state.drumKits.forEach { kit ->
                    val installed = kit.id == state.installedDrumKit
                    val character = when (kit.id) {
                        "dusty-jazz" -> Res.string.ce_kit_dusty_jazz; "boom-bap" -> Res.string.ce_kit_boom_bap
                        "vinyl-soul" -> Res.string.ce_kit_vinyl_soul; "lofi-tape" -> Res.string.ce_kit_lofi_tape
                        "clean-studio" -> Res.string.ce_kit_clean_studio; else -> null
                    }?.let { "\n" + stringResource(it) }.orEmpty()
                    CEButton("${kit.name}$character${if (installed) "\n$inUse" else ""}", { onAction(ContinuousEditorAction.ChooseDrumKit(kit.id)) },
                        Modifier.fillMaxWidth().semantics { selected = installed }, primary = installed, tag = "ce-kit-${kit.id}")
                }
            }
        },
        confirmButton = { CEButton(stringResource(Res.string.ce_close), { onAction(ContinuousEditorAction.DismissDrumKit) }, tag = "ce-kit-close") })
    state.drumKitQuestion?.let { question ->
        AlertDialog(onDismissRequest = { onAction(ContinuousEditorAction.DismissDrumKit) },
            modifier = Modifier.testTag("ce-kit-question"), title = { Text(stringResource(Res.string.ce_kit_replace_title)) },
            text = { Text(stringResource(Res.string.ce_kit_replace_body, question.replacedSounds), fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { CEButton(stringResource(Res.string.ce_kit_replace), { onAction(ContinuousEditorAction.ConfirmDrumKit) }, primary = true, tag = "ce-kit-replace") },
            dismissButton = { CEButton(stringResource(Res.string.ce_kit_keep), { onAction(ContinuousEditorAction.DismissDrumKit) }, tag = "ce-kit-keep") })
    }
}

@Composable private fun CEHeader(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, compact: Boolean,
    readout: () -> ContinuousEditorReadout, refreshKey: Long) {
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
                Box(Modifier.weight(1f)) {
                    CEPcmHealth(state, onAction, readout, refreshKey) {
                        Text(stringResource(Res.string.ce_brand), color = CEColor.Cream, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                    }
                }
                CEActionButton(stringResource(Res.string.ce_stop_all), ContinuousEditorAction.StopAll, state, ContinuousCapability.STOP_ALL, onAction, primary = true, tag = "ce-stop-all")
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val width = maxWidth.coerceAtLeast(420.dp * density.fontScale.coerceAtLeast(1f))
                val viewWidth = maxWidth
                val scroll = rememberScrollState()
                // Keep the selected stage visible when text grows, without narrowing or shrinking its label.
                LaunchedEffect(state.stage, width, viewWidth) {
                    val side = (width - 24.dp) / 4
                    val start = (side + 8.dp) * state.stage.ordinal
                    scroll.scrollTo(with(density) { (start - (viewWidth - side) / 2).toPx() }.roundToInt().coerceAtLeast(0))
                }
                Box(Modifier.horizontalScroll(scroll)) { Stages(Modifier.width(width)) }
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(160.dp)) { CEPcmHealth(state, onAction, readout, refreshKey) { Brand() } }
            Stages(Modifier.weight(1f))
            Text("${stringResource(Res.string.ce_bpm)} ${state.bpm}", color = CEColor.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            CEActionButton(stringResource(Res.string.ce_stop_all), ContinuousEditorAction.StopAll, state, ContinuousCapability.STOP_ALL, onAction, Modifier.widthIn(min = 100.dp), primary = true, tag = "ce-stop-all")
        }
    }
}

@Composable private fun CEOriginalDock(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, compact: Boolean, modifier: Modifier = Modifier) {
    val original = state.original
    val live = CELive(state.originalPlaying, refreshKey, readout)
    val title = original?.title ?: stringResource(Res.string.ce_no_source)
    val waveLabel = stringResource(Res.string.ce_original_wave, title)
    Column(modifier.fillMaxWidth().heightIn(min = 74.dp).clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(if (compact) 108.dp else 168.dp)) {
                Text(stringResource(Res.string.ce_source_prefix, title), color = CEColor.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
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
                    { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.width(190.dp), tag = "ce-source-monitor",
                    stacked = LocalDensity.current.fontScale > 1.3f)
                CEButton(stringResource(Res.string.ce_standard_width), { onAction(ContinuousEditorAction.ResetPanes) }, Modifier.widthIn(min = 78.dp), tag = "ce-reset-panes")
            }
        }
        if (compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("ce-original-controls"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CEActionButton(stringResource(if (state.originalPlaying) Res.string.ce_stop else Res.string.ce_play_original),
                if (state.originalPlaying) ContinuousEditorAction.StopOriginal else ContinuousEditorAction.PlayOriginal,
                state, ContinuousCapability.ORIGINAL_PLAYBACK, onAction, tag = "ce-original-play")
            CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
                { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.width(260.dp), tag = "ce-source-monitor")
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
                if (state.recordingSource && !state.recordingSystemAudio) CEButton(stringResource(Res.string.ce_stop_voice),
                    { onAction(ContinuousEditorAction.StopSourceRecording) }, Modifier.weight(1f), primary = true, tag = "ce-source-record-stop")
                else CEActionButton(stringResource(Res.string.ce_mic_record), ContinuousEditorAction.RecordSource,
                    state, ContinuousCapability.RECORD_SOURCE, onAction, Modifier.weight(1f), tag = "ce-source-record")
                if (state.recordingSystemAudio) CEButton(stringResource(Res.string.ce_stop_voice),
                    { onAction(ContinuousEditorAction.StopSourceRecording) }, Modifier.weight(1f), primary = true, tag = "ce-system-record-stop")
                else CEActionButton(stringResource(Res.string.ce_device_record), ContinuousEditorAction.RecordSystemSource,
                    state, ContinuousCapability.RECORD_SYSTEM_SOURCE, onAction, Modifier.weight(1f), tag = "ce-system-record")
            }
            CEActionButton(stringResource(Res.string.four_stem_title), ContinuousEditorAction.OpenFourStems,
                state, ContinuousCapability.FOUR_STEMS, onAction, Modifier.fillMaxWidth(), tag = "ce-four-stems-open")
            if (ContinuousCapability.SEPARATE_SOURCE in state.capabilities) {
                CEActionButton(stringResource(Res.string.ce_separate), ContinuousEditorAction.SeparateSource,
                    state, ContinuousCapability.SEPARATE_SOURCE, onAction, Modifier.fillMaxWidth(), tag = "ce-separate")
            }
            if (ContinuousCapability.IMPORT_ONLINE in state.capabilities) {
                CEActionButton(stringResource(Res.string.ce_online), ContinuousEditorAction.ImportOnline,
                    state, ContinuousCapability.IMPORT_ONLINE, onAction, Modifier.fillMaxWidth(), tag = "ce-online")
            }
            if (ContinuousCapability.IMPORT_LIBRARY in state.capabilities) {
                CEActionButton(stringResource(Res.string.ce_library), ContinuousEditorAction.ImportLibrary,
                    state, ContinuousCapability.IMPORT_LIBRARY, onAction, Modifier.fillMaxWidth(), tag = "ce-library")
            }
            CEActionButton(stringResource(Res.string.ce_spotify_metadata), ContinuousEditorAction.OpenSpotifyMetadata,
                state, ContinuousCapability.SPOTIFY_METADATA, onAction, Modifier.fillMaxWidth(), tag = "ce-spotify-metadata")
            if (state.recordingSource) {
                val capture by CELive(true, refreshKey, readout)
                Text(if (state.startingSourceRecording) stringResource(Res.string.ce_source_recording_starting)
                    else stringResource(Res.string.ce_source_recording, (capture.recordingMillis / 1_000).toString()),
                    fontSize = 14.sp, modifier = Modifier.testTag("ce-source-recording"))
                CEButton(stringResource(Res.string.ce_source_record_discard),
                    { onAction(ContinuousEditorAction.DiscardSourceRecording) }, Modifier.fillMaxWidth(), tag = "ce-source-record-discard")
            }
        }
    }
}

@Composable private fun CEChop(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, compact: Boolean) {
    // During a live chop pass a PAD cuts the original where it was playing when the PAD went down.
    val capture: (() -> Long)? = if (state.liveChopping) ({ readout().originalFrame }) else null
    if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CEChopSource(state, onAction, readout, refreshKey, Modifier.fillMaxWidth(), 240.dp)
        CEBanks(state, onAction)
        CEPads(state, onAction, maximumSide = 140.dp, capture = capture)
    } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(.58f).fillMaxHeight()) {
            CEChopSource(state, onAction, readout, refreshKey, Modifier.fillMaxSize(), null)
        }
        Column(Modifier.weight(.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CEBanks(state, onAction)
            CEChopHint(state)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CEPads(state, onAction, maximumSide = 160.dp, capture = capture) }
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
            // Ending a pass is always possible; starting one needs the original on an output.
            if (state.liveChopping) CEActionButton(stringResource(Res.string.ce_chop_stop), ContinuousEditorAction.EndLiveChop, state,
                ContinuousCapability.STOP_ALL, onAction, Modifier.weight(1f), primary = true, tag = "ce-live-chop")
            else CEActionButton(stringResource(Res.string.ce_chop_start), ContinuousEditorAction.BeginLiveChop, state,
                ContinuousCapability.LIVE_CHOP, onAction, Modifier.weight(1f), tag = "ce-live-chop")
            CEActionButton(stringResource(Res.string.ce_play_from_start), ContinuousEditorAction.SeekOriginal(0), state, ContinuousCapability.ORIGINAL_SEEK, onAction, Modifier.weight(1f))
            CEActionButton(stringResource(Res.string.ce_add_audio), ContinuousEditorAction.ImportAudio, state, ContinuousCapability.IMPORT_AUDIO, onAction, Modifier.weight(1f))
        }
        CEAdjustment(stringResource(Res.string.ce_original_key), original?.pitchSemitones ?: 0f, state, ContinuousCapability.ORIGINAL_PITCH,
            { onAction(ContinuousEditorAction.SetOriginalPitch(it)) }, true, Modifier.fillMaxWidth())
        CEValueSlider(stringResource(Res.string.ce_source_gain), state.originalMonitorGain, state, ContinuousCapability.ORIGINAL_MONITOR_GAIN,
            { onAction(ContinuousEditorAction.SetOriginalMonitorGain(it)) }, Modifier.fillMaxWidth(), tag = "ce-source-monitor")
        CEChopHint(state)
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

@Composable private fun CEChopHint(state: ContinuousEditorState) {
    if (state.liveChopping) Text(stringResource(Res.string.ce_chop_live_hint), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CEColor.Ink)
    else Text(stringResource(Res.string.ce_chop_hint), fontSize = 12.sp, color = CEColor.Border)
}

@Composable internal fun CEAdjustment(label: String, value: Float, state: ContinuousEditorState, capability: ContinuousCapability,
    onValue: (Float) -> Unit, semitones: Boolean = false, modifier: Modifier = Modifier, maximum: Float = 2f) {
    val decrease = stringResource(Res.string.ce_decrease, label)
    val increase = stringResource(Res.string.ce_increase, label)
    Column(modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (semitones) stringResource(Res.string.ce_semitones, (if (value >= 0) "+" else "") + value.roundToInt()) else stringResource(Res.string.ce_percent, (value * 100).roundToInt()),
                Modifier.weight(1f, fill = false).widthIn(min = 56.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            CEButton("−", { onValue(if (semitones) (value - 1).coerceAtLeast(-24f) else (value - .05f).coerceAtLeast(0f)) },
                enabled = state.permits(capability), reason = CEReason(state, capability), modifier = Modifier.semantics { contentDescription = decrease })
            CEButton("+", { onValue(if (semitones) (value + 1).coerceAtMost(24f) else (value + .05f).coerceAtMost(maximum)) },
                enabled = state.permits(capability), reason = CEReason(state, capability), modifier = Modifier.semantics { contentDescription = increase })
        }
    }
}

@Composable private fun CESave(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, compact: Boolean,
    diagnostics: (() -> ContinuousDiagnostics?)?) {
    @Composable fun SaveCard(audio: Boolean, modifier: Modifier) {
        val foreground = if (audio) CEColor.Cream else CEColor.Ink
        Column(modifier.clip(RoundedCornerShape(8.dp)).background(if (audio) CEColor.Ink else CEColor.Tan)
            .border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(if (audio) Res.string.ce_save_audio else Res.string.ce_save_edit), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = foreground)
            Text(stringResource(if (audio) Res.string.ce_save_audio_hint else Res.string.ce_save_edit_hint), fontSize = 14.sp, color = if (audio) CEColor.Tan else CEColor.Border)
            if (audio) com.choplab.ui.mixer.CEExportOptions(state, onAction)
            CEActionButton(stringResource(if (audio) Res.string.ce_export_wav else Res.string.ce_save_project),
                if (audio) ContinuousEditorAction.ExportWav else ContinuousEditorAction.SaveProject,
                state, if (audio) ContinuousCapability.EXPORT_WAV else ContinuousCapability.SAVE_PROJECT,
                onAction, Modifier.fillMaxWidth(), primary = true, tag = if (audio) "ce-export" else "ce-save")
            Text(stringResource(if (audio) Res.string.ce_wav_format else Res.string.ce_project_format), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (audio) CEColor.Tan else CEColor.Border)
            if (audio) {
                Text(stringResource(Res.string.ce_export_monitor_hint), fontSize = 13.sp, color = CEColor.Tan)
                Text(stringResource(Res.string.mixer_stem_point), fontSize = 14.sp, color = CEColor.Tan)
                CEActionButton(stringResource(Res.string.mixer_export_stems), ContinuousEditorAction.ExportStems,
                    state, ContinuousCapability.EXPORT_STEMS, onAction, Modifier.fillMaxWidth(), tag = "ce-export-stems")
                state.stemProgress?.let { progress ->
                    Text(stringResource(Res.string.mixer_export_progress, progress.completedStems, progress.totalStems,
                        if (progress.framesPerStem == 0L) 0 else (progress.renderedFrames * 100 / progress.framesPerStem).toInt()),
                        fontSize = 14.sp, color = CEColor.Cream, modifier = Modifier.testTag("ce-stem-progress"))
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(Res.string.ce_save_heading), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.ce_save_summary, state.clips.size, state.tracks.size, ceTime(state.timelineDurationFrames)), fontSize = 14.sp)
        Text(stringResource(Res.string.ce_save_hint), fontSize = 14.sp, color = CEColor.Border)
        CEActionButton(stringResource(Res.string.mixer_open), ContinuousEditorAction.Mixer(com.choplab.ui.mixer.MixerAction.Open(com.choplab.ui.mixer.MixerTarget.Master)),
            state, ContinuousCapability.MIXER, onAction, Modifier.fillMaxWidth(), tag = "ce-mixer-open")
        if (compact) { SaveCard(false, Modifier.fillMaxWidth()); SaveCard(true, Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { SaveCard(false, Modifier.weight(1f)); SaveCard(true, Modifier.weight(1f)) }
        CEActionButton(stringResource(Res.string.ce_reopen), ContinuousEditorAction.OpenProject, state, ContinuousCapability.OPEN_PROJECT, onAction, Modifier.fillMaxWidth(), tag = "ce-reopen")
        diagnostics?.let { CEDiagnostics(it, onAction, compact) }
    }
}

/**
 * Output health, read again every second while shown: formats, times and counts only, never a device identifier. The
 * read waits for a frame, so it pauses while the app is in the background and its frame clock is stopped.
 */
@Composable private fun CEDiagnostics(read: () -> ContinuousDiagnostics?, onAction: (ContinuousEditorAction) -> Unit, compact: Boolean) {
    val latestRead by rememberUpdatedState(read)
    var current by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); withFrameNanos { }; current = latestRead() } }
    val d = current ?: return
    fun milliseconds(frames: Long) = ((frames * 10_000.0 / d.sampleRate).roundToLong() / 10.0).toString()
    fun percent(share: Double) = (share * 100).roundToInt().toString()
    // A device report is missing either because there is no output or because this output does not tell.
    val unreported = stringResource(if (d.outputAttached) Res.string.ce_diag_unknown else Res.string.ce_diag_no_output)
    val kilohertz = (d.sampleRate / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
    val rows = listOf(
        stringResource(Res.string.ce_diag_output) to stringResource(if (d.outputAttached) Res.string.ce_diag_attached else Res.string.ce_diag_detached),
        stringResource(Res.string.ce_diag_format) to (d.floatOutput?.let { stringResource(if (it) Res.string.ce_diag_float else Res.string.ce_diag_pcm16, kilohertz) } ?: unreported),
        stringResource(Res.string.ce_diag_block) to stringResource(Res.string.ce_diag_frames_ms, d.blockFrames.toString(), milliseconds(d.blockFrames.toLong())),
        stringResource(Res.string.ce_diag_buffer) to (d.bufferFrames?.let { stringResource(Res.string.ce_diag_frames_ms, it.toString(), milliseconds(it.toLong())) } ?: unreported),
        stringResource(Res.string.ce_diag_delay) to (d.pendingFrames?.let { stringResource(Res.string.ce_diag_ms, milliseconds(it)) } ?: unreported),
        stringResource(Res.string.ce_diag_render) to (if (d.renderP99 == null || d.renderMax == null) stringResource(Res.string.ce_diag_not_measured)
            else stringResource(Res.string.ce_diag_render_value, percent(d.renderP99), percent(d.renderMax), d.measuredBlocks.toString())),
        stringResource(Res.string.ce_diag_underruns) to (d.underruns?.let { stringResource(Res.string.ce_diag_times, it.toString()) } ?: unreported),
        stringResource(Res.string.ce_diag_losses) to stringResource(Res.string.ce_diag_times, d.outputLosses.toString()),
        stringResource(Res.string.ce_pcm_title) to cePcmStatus(d.pcm.status),
        stringResource(Res.string.ce_pcm_missing_label) to d.pcm.underrunFrames.toString(),
        stringResource(Res.string.ce_pcm_queue_label) to d.pcm.droppedRequests.toString(),
        stringResource(Res.string.ce_diag_frames) to (if (d.drawnFrames == null || d.slowFrames == null) stringResource(Res.string.ce_diag_frames_unknown)
            else stringResource(Res.string.ce_diag_frames_value, d.drawnFrames.toString(), d.slowFrames.toString())),
    )
    val title = stringResource(Res.string.ce_diag_title)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CEColor.Tan).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp))
        .padding(16.dp).testTag("ce-diagnostics"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.ce_diag_hint), fontSize = 13.sp)
        rows.forEach { (label, value) ->
            // One screen reader stop per row, label and value together.
            val row = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }
            // A narrow screen puts each value under its label instead of squeezing two columns.
            if (compact) Column(row) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            } else Row(row, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(.45f), fontSize = 13.sp)
                Text(value, Modifier.weight(.55f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        val text = (listOf(title) + rows.map { (label, value) -> "$label: $value" }).joinToString("\n")
        CEButton(stringResource(Res.string.ce_diag_copy), { onAction(ContinuousEditorAction.CopyDiagnostics(text)) }, Modifier.fillMaxWidth(), tag = "ce-diag-copy")
    }
}

@Composable internal fun CESongTransport(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val live by CELive(state.songPlaying, refreshKey, readout)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stretch = maxWidth >= 1000.dp && LocalDensity.current.fontScale <= 1.3f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CEColor.Ink).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Stop remains visible even when large text pushes the other transport controls off screen.
        if (!stretch) CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopSong, state,
            ContinuousCapability.SONG_PLAYBACK, onAction, dark = true, tag = "ce-song-stop")
        Row(if (stretch) Modifier.fillMaxWidth() else Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.ce_song), color = CEColor.Cream, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        CEActionButton(stringResource(Res.string.ce_top), ContinuousEditorAction.SeekSong(0), state, ContinuousCapability.SONG_SEEK, onAction, dark = true)
        CEActionButton(stringResource(if (state.songPlaying) Res.string.ce_pause else Res.string.ce_play),
            if (state.songPlaying) ContinuousEditorAction.PauseSong else ContinuousEditorAction.PlaySong,
            state, ContinuousCapability.SONG_PLAYBACK, onAction, primary = true, tag = "ce-song-play")
        if (stretch) CEActionButton(stringResource(Res.string.ce_stop), ContinuousEditorAction.StopSong, state, ContinuousCapability.SONG_PLAYBACK, onAction, dark = true, tag = "ce-song-stop")
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
}

/** Swing choices in permille: straight, then the drum machines' classic steps; 660 is close to triplets. */
private val CE_SWINGS = listOf(500, 540, 580, 620, 660, 710)

@Composable private fun CETempo(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var value by remember(state.bpm) { mutableStateOf(state.bpm.toString()) }
    var swing by remember(state.swingPermille) { mutableStateOf(state.swingPermille) }
    // Tapped along with the song, the taps' tempo fills in the value; applying it is still the user's choice.
    var taps by remember { mutableStateOf(emptyList<Long>()) }
    val clock = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    // A swung song says so beside its tempo, as it changes how every beat placed on the grid sounds.
    CEButton(if (state.swingPermille == 500) "${state.bpm} ${stringResource(Res.string.ce_bpm)}"
        else stringResource(Res.string.ce_tempo_swing, state.bpm, state.swingPermille / 10),
        { value = state.bpm.toString(); swing = state.swingPermille; taps = emptyList(); open = true },
        enabled = state.permits(ContinuousCapability.TEMPO), dark = true, reason = CEReason(state, ContinuousCapability.TEMPO), tag = "ce-tempo")
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.ce_bpm)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.ce_tempo_hint))
            OutlinedTextField(value, { value = it.filter(Char::isDigit).take(3) }, Modifier.testTag("ce-tempo-value"), singleLine = true)
            Text(stringResource(Res.string.ce_tap_tempo_hint), fontSize = 14.sp)
            CEButton(stringResource(Res.string.ce_tap_tempo), {
                taps = ceTap(taps, clock.elapsedNow().inWholeMilliseconds)
                ceTapBpm(taps)?.let { value = it.toString() }
            }, Modifier.fillMaxWidth().heightIn(min = 64.dp), tag = "ce-tap-tempo")
            // The swing is chosen here and applied with the tempo: straight, or the drum machines' classic steps.
            Text(stringResource(Res.string.ce_swing_value, swing / 10), Modifier.padding(top = 8.dp).testTag("ce-swing-value"),
                fontWeight = FontWeight.SemiBold)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth >= 420.dp && LocalDensity.current.fontScale <= 1.3f) CE_SWINGS.size else 3
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CE_SWINGS.chunked(perRow).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { choice ->
                            val spoken = stringResource(Res.string.ce_swing_value, choice / 10)
                            CEButton(stringResource(Res.string.ce_percent, choice / 10), { swing = choice },
                                Modifier.weight(1f).semantics { selected = swing == choice; contentDescription = spoken },
                                primary = swing == choice, tag = "ce-swing-$choice")
                        }
                    } }
                }
            }
            Text(stringResource(Res.string.ce_swing_hint), fontSize = 14.sp)
        } },
        confirmButton = { CEButton(stringResource(Res.string.ce_apply), { value.toIntOrNull()?.let { onAction(ContinuousEditorAction.SetTempo(it, swing)); open = false } }, enabled = (value.toIntOrNull() ?: -1) in 40..240, tag = "ce-tempo-apply") },
        dismissButton = { CEButton(stringResource(Res.string.ce_close), { open = false }) })
}

@Composable private fun CEStatus(status: ContinuousStatus?) {
    if (status == null) return
    val text = stringResource(when (status) {
        ContinuousStatus.LOADING -> Res.string.ce_loading; ContinuousStatus.SAVING -> Res.string.ce_saving
        ContinuousStatus.SAVED -> Res.string.ce_saved; ContinuousStatus.EXPORTING -> Res.string.ce_exporting
        ContinuousStatus.EXPORTED -> Res.string.ce_exported; ContinuousStatus.CANCELLED -> Res.string.ce_cancelled
        ContinuousStatus.FAILED -> Res.string.ce_failed; ContinuousStatus.NO_OUTPUT -> Res.string.ce_no_output
        ContinuousStatus.COPIED -> Res.string.ce_copied
        ContinuousStatus.SOURCE_RECORDED -> Res.string.ce_source_recorded
        ContinuousStatus.SOURCE_RECORDING_LIMIT -> Res.string.ce_source_recording_limit
        ContinuousStatus.SOURCE_RECORDING_INTERRUPTED -> Res.string.ce_source_recording_interrupted
        ContinuousStatus.SYSTEM_DENIED -> Res.string.ce_system_denied
        ContinuousStatus.SYSTEM_NO_DISPLAY -> Res.string.ce_system_no_display
        ContinuousStatus.SYSTEM_UNAVAILABLE -> Res.string.ce_system_unavailable
        ContinuousStatus.SYSTEM_TIMEOUT -> Res.string.ce_system_timeout
        ContinuousStatus.SYSTEM_EMPTY -> Res.string.ce_system_empty
        ContinuousStatus.VOICE_SAVED -> Res.string.ce_voice_saved; ContinuousStatus.VOICE_SAVED_SONG_ONLY -> Res.string.ce_voice_saved_song_only
        ContinuousStatus.VOICE_SAVED_TAKE_ONLY -> Res.string.vocal_take_saved_only
        ContinuousStatus.VOICE_SAVED_PAD_ONLY -> Res.string.ce_voice_saved_pad_only; ContinuousStatus.VOICE_INTERRUPTED -> Res.string.ce_voice_interrupted
        ContinuousStatus.VOICE_TOO_SHORT -> Res.string.ce_voice_too_short; ContinuousStatus.VOICE_NOT_SAVED -> Res.string.ce_voice_not_saved
        ContinuousStatus.VOICE_LIMIT -> Res.string.ce_voice_limit; ContinuousStatus.VOICE_EMPTY -> Res.string.ce_voice_empty
        ContinuousStatus.VOICE_NO_ROOM -> Res.string.ce_voice_no_room; ContinuousStatus.MIC_DENIED -> Res.string.ce_mic_denied
        ContinuousStatus.PLACE_NO_ROOM -> Res.string.ce_place_no_room; ContinuousStatus.PLACE_FAILED -> Res.string.ce_place_failed
        ContinuousStatus.SONG_FULL -> Res.string.ce_song_full
        ContinuousStatus.HITS_PLACED -> Res.string.ce_hits_placed; ContinuousStatus.HITS_PARTLY -> Res.string.ce_hits_partly
        ContinuousStatus.HITS_EMPTY -> Res.string.ce_hits_empty; ContinuousStatus.HITS_UNCHANGED -> Res.string.ce_hits_unchanged
        ContinuousStatus.MIC_UNAVAILABLE -> Res.string.ce_mic_unavailable; ContinuousStatus.RECORDING_BUSY -> Res.string.ce_recording_busy
        ContinuousStatus.RECORDING_ARM_TIMEOUT -> Res.string.ce_recording_arm_timeout
        ContinuousStatus.RECORDING_CUE_CANCELLED -> Res.string.ce_recording_cue_cancelled
        ContinuousStatus.RESCUED -> Res.string.ce_rescued; ContinuousStatus.RESCUED_PARTLY -> Res.string.ce_rescued_partly
        ContinuousStatus.RESCUED_TOO_LONG -> Res.string.ce_rescued_too_long; ContinuousStatus.RESCUED_NOTHING -> Res.string.ce_rescued_nothing
    })
    Text(text, Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp, color = CEColor.Border)
}
