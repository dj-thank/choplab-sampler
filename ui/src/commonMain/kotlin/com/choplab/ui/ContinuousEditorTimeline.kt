@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.choplab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private data class CEPlacementTarget(val visible: Rect, val origin: Offset, val rowHeight: Float, val pixelsPerSecond: Float) {
    fun hit(position: Offset, tracks: List<ContinuousTrack>): Pair<String?, Long>? {
        if (!visible.contains(position)) return null
        val index = floor((position.y - origin.y) / rowHeight).toInt()
        val frame = ((position.x - origin.x) / pixelsPerSecond * CONTINUOUS_TIMELINE_RATE).roundToLong().coerceAtLeast(0)
        return tracks.getOrNull(index)?.id to frame
    }
}

@Composable internal fun CEBeatWorkspace(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, compact: Boolean) {
    var target by remember { mutableStateOf<CEPlacementTarget?>(null) }
    var draggedPad by remember { mutableStateOf<CEPaddedDrag?>(null) }
    val drop: (Int, Offset) -> Unit = { id, position ->
        if (state.permits(ContinuousCapability.PLACE_PAD)) target?.hit(position, state.tracks)?.let { (track, frame) ->
            onAction(ContinuousEditorAction.PlacePad(id, track, frame))
        }
        draggedPad = null
    }
    if (compact) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEButton(stringResource(Res.string.ce_pads), { onAction(ContinuousEditorAction.SelectCompactPane(ContinuousPane.PADS)) }, Modifier.weight(1f), primary = state.compactPane == ContinuousPane.PADS)
            CEButton(stringResource(Res.string.ce_timeline), { onAction(ContinuousEditorAction.SelectCompactPane(ContinuousPane.TIMELINE)) }, Modifier.weight(1f), primary = state.compactPane == ContinuousPane.TIMELINE)
        }
        if (state.compactPane == ContinuousPane.PADS) CEPadsPanel(state, onAction, readout, Modifier.weight(1f), { draggedPad = it }, drop)
        else CETimelinePanel(state, onAction, readout, refreshKey, Modifier.weight(1f), { target = it })
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val usable = maxWidth - 18.dp
        val minimum = (300.dp / usable).coerceAtLeast(.2f)
        val maximum = (1 - 300.dp / usable).coerceAtMost(.8f)
        var fraction by remember(state.paneFraction) { mutableStateOf(state.paneFraction.coerceIn(minimum, maximum)) }
        Row(Modifier.fillMaxSize()) {
            CEPadsPanel(state, onAction, readout, Modifier.width(usable * fraction).fillMaxHeight(), { draggedPad = it }, drop)
            val dividerLabel = stringResource(Res.string.ce_divider)
            Box(Modifier.width(18.dp).fillMaxHeight().testTag("ce-divider")
                .semantics {
                    contentDescription = dividerLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimum..maximum)
                    setProgress { onAction(ContinuousEditorAction.ResizePanes(it.coerceIn(minimum, maximum))); true }
                }.focusable().onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.DirectionLeft -> { onAction(ContinuousEditorAction.ResizePanes((fraction - .02f).coerceAtLeast(minimum))); true }
                        Key.DirectionRight -> { onAction(ContinuousEditorAction.ResizePanes((fraction + .02f).coerceAtMost(maximum))); true }
                        else -> false
                    }
                }.pointerInput(usable, minimum, maximum) {
                    detectDragGestures(onDragEnd = { onAction(ContinuousEditorAction.ResizePanes(fraction)) }) { change, delta ->
                        change.consume()
                        fraction = (fraction + delta.x / with(density) { usable.toPx() }).coerceIn(minimum, maximum)
                    }
                }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onAction(ContinuousEditorAction.ResetPanes) }) }, contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { Box(Modifier.width(2.dp).height(36.dp).background(CEColor.Border)) }
                }
            }
            CETimelinePanel(state, onAction, readout, refreshKey, Modifier.weight(1f).fillMaxHeight(), { target = it })
        }
    }
}

@Composable private fun CEPadsPanel(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, modifier: Modifier, onDrag: (CEPaddedDrag?) -> Unit, onDrop: (Int, Offset) -> Unit) {
    val pad = state.selectedPad
    val padName = pad?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.ce_empty)
    Column(modifier.clip(RoundedCornerShape(8.dp)).border(2.dp, CEColor.Ink, RoundedCornerShape(8.dp))
        .verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CEColor.Ink).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${cePadName(state.selectedPadId)} / $padName", Modifier.weight(1f), color = CEColor.Cream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.ce_pad_sound), color = CEColor.Green, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("S ${ceTime(pad?.sourceStartFrame ?: 0, pad?.sourceRate ?: 48_000, true)}", color = CEColor.Green, fontSize = 10.sp)
                Text("${ceTime(pad?.sourceEndFrame ?: 0, pad?.sourceRate ?: 48_000, true)} E", color = CEColor.Orange, fontSize = 10.sp)
            }
            CEWaveform(pad?.peaks.orEmpty(), Modifier.fillMaxWidth().height(30.dp), stringResource(Res.string.ce_pad_wave, padName), tag = "ce-selected-pad-wave")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(Res.string.ce_audition), ContinuousEditorAction.TapPad(state.selectedPadId), state, ContinuousCapability.PAD_AUDITION,
                onAction, Modifier.weight(1f), tag = "ce-pad-audition", additionallyEnabled = pad != null && pad.kind != ContinuousPadKind.EMPTY)
            CEActionButton(stringResource(if (pad?.looping == true) Res.string.ce_stop_loop else Res.string.ce_loop_pad), ContinuousEditorAction.TogglePadLoop(state.selectedPadId),
                state, ContinuousCapability.PAD_LOOP, onAction, Modifier.weight(1.6f), primary = pad?.looping == true)
            CEButton(stringResource(Res.string.ce_place), { onAction(ContinuousEditorAction.PlacePad(state.selectedPadId, state.selectedTrackId, readout().songFrame)) },
                Modifier.weight(1.3f), state.permits(ContinuousCapability.PLACE_PAD) && pad != null && pad.kind != ContinuousPadKind.EMPTY,
                primary = true, reason = CEReason(state, ContinuousCapability.PLACE_PAD), tag = "ce-place-pad")
        }
        CEBanks(state, onAction)
        Text(stringResource(Res.string.ce_pad_help), fontSize = 12.sp, color = CEColor.Border)
        CEPads(state, onAction, Modifier.padding(top = 12.dp), onPadDrag = onDrag, onPadDrop = onDrop)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun Adjustments() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEAdjustment(stringResource(Res.string.ce_key), pad?.pitchSemitones ?: 0f, state, ContinuousCapability.PAD_PITCH,
                { onAction(ContinuousEditorAction.SetPadPitch(state.selectedPadId, it)) }, true, Modifier.weight(1f))
            CEAdjustment(stringResource(Res.string.ce_tone), pad?.tone ?: 1f, state, ContinuousCapability.PAD_TONE,
                { onAction(ContinuousEditorAction.SetPadTone(state.selectedPadId, it)) }, modifier = Modifier.weight(1f))
            CEAdjustment(stringResource(Res.string.ce_gain), pad?.gain ?: 1f, state, ContinuousCapability.PAD_GAIN,
                { onAction(ContinuousEditorAction.SetPadGain(state.selectedPadId, it)) }, modifier = Modifier.weight(1f))
        }
        }
        if (maxWidth >= 510.dp && LocalDensity.current.fontScale <= 1.3f) Adjustments()
        else Box(Modifier.horizontalScroll(rememberScrollState())) { Box(Modifier.width(540.dp)) { Adjustments() } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(Res.string.ce_add_drums), ContinuousEditorAction.AddDrum, state, ContinuousCapability.ADD_DRUM, onAction, Modifier.weight(1f), tag = "ce-add-drums")
            CEActionButton(stringResource(Res.string.ce_record_voice), ContinuousEditorAction.RecordVoice, state, ContinuousCapability.RECORD_VOICE, onAction, Modifier.weight(1f), tag = "ce-record-voice")
            CEActionButton(stringResource(Res.string.ce_scratch), ContinuousEditorAction.OpenScratch, state, ContinuousCapability.SCRATCH, onAction, Modifier.weight(1f), tag = "ce-scratch")
        }
    }
}

@Composable private fun CETimelinePanel(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, modifier: Modifier, onTarget: (CEPlacementTarget) -> Unit) {
    val clip = state.selectedClip
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(CEColor.Ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = maxWidth >= 650.dp && LocalDensity.current.fontScale <= 1.3f
        Row(Modifier.fillMaxWidth().then(if (inline) Modifier else Modifier.horizontalScroll(rememberScrollState())), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.ce_arrangement), if (inline) Modifier.weight(1f) else Modifier.widthIn(min = 220.dp), color = CEColor.Cream, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            CEActionButton(stringResource(Res.string.ce_undo), ContinuousEditorAction.Undo, state, ContinuousCapability.HISTORY, onAction, dark = true, additionallyEnabled = state.canUndo)
            CEButton(stringResource(Res.string.ce_split), { clip?.let { onAction(ContinuousEditorAction.SplitClip(it.id, readout().songFrame)) } },
                enabled = clip != null && state.permits(ContinuousCapability.SPLIT_CLIP), reason = CEReason(state, ContinuousCapability.SPLIT_CLIP), tag = "ce-split")
            CEActionButton(stringResource(Res.string.ce_duplicate), ContinuousEditorAction.DuplicateClip(clip?.id.orEmpty()), state, ContinuousCapability.DUPLICATE_CLIP, onAction, additionallyEnabled = clip != null, tag = "ce-duplicate")
            CEActionButton(stringResource(Res.string.ce_delete), ContinuousEditorAction.DeleteClip(clip?.id.orEmpty()), state, ContinuousCapability.DELETE_CLIP, onAction, additionallyEnabled = clip != null, tag = "ce-delete")
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEButton("−", { onAction(ContinuousEditorAction.SetPixelsPerSecond((state.pixelsPerSecond / 1.25f).coerceAtLeast(4f))) }, dark = true,
                modifier = Modifier.semantics { contentDescription = "−" })
            CEButton("+", { onAction(ContinuousEditorAction.SetPixelsPerSecond((state.pixelsPerSecond * 1.25f).coerceAtMost(240f))) }, dark = true,
                modifier = Modifier.semantics { contentDescription = "+" })
            CEButton(stringResource(Res.string.ce_fit), { onAction(ContinuousEditorAction.FitTimeline) }, dark = true)
        }
        CETimelineGrid(state, onAction, readout, refreshKey, Modifier.weight(1f).fillMaxWidth(), onTarget)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (clip == null) stringResource(Res.string.ce_no_selected_clip) else stringResource(Res.string.ce_selected_clip, clip.title), color = CEColor.Green, fontSize = 14.sp, lineHeight = 20.sp)
            if (clip != null) {
                Text(stringResource(Res.string.ce_clip_position, ceTime(clip.timelineStartFrame, precise = true), ceTime(clip.timelineDurationFrames, precise = true)), color = CEColor.Cream, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                CEValueSlider(stringResource(Res.string.ce_clip_gain), clip.gain, state, ContinuousCapability.CLIP_GAIN,
                    { onAction(ContinuousEditorAction.SetClipGain(clip.id, it)) }, Modifier.fillMaxWidth(), dark = true, tag = "ce-clip-gain", range = 0f..2f)
            }
        }
    }
}

@Composable private fun CETimelineGrid(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, modifier: Modifier, onTarget: (CEPlacementTarget) -> Unit) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val rowHeight = 110.dp
    val live = CELive(state.songPlaying, refreshKey, readout)
    Row(modifier.testTag("ce-timeline")) {
        Column(Modifier.width(104.dp)) {
            Spacer(Modifier.height(38.dp))
            Column(Modifier.weight(1f).verticalScroll(vertical)) {
                state.tracks.forEach { track -> Column(Modifier.height(rowHeight).fillMaxWidth().padding(end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(track.name, color = CEColor.Cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    val muteLabel = stringResource(if (track.muted) Res.string.ce_track_unmute else Res.string.ce_track_mute, track.name)
                    CEButton("M", { onAction(ContinuousEditorAction.SetTrackMuted(track.id, !track.muted)) }, dark = true, primary = track.muted,
                        enabled = state.permits(ContinuousCapability.TRACK_MUTE), reason = CEReason(state, ContinuousCapability.TRACK_MUTE),
                        modifier = Modifier.semantics { contentDescription = muteLabel; selected = track.muted }, tag = "ce-mute-${track.id}")
                } }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val width = (state.timelineDurationFrames.toDouble() / CONTINUOUS_TIMELINE_RATE * state.pixelsPerSecond + 80).toFloat().dp.coerceAtLeast(maxWidth)
            val rows = state.tracks.size.coerceAtLeast(1)
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(horizontal).height(38.dp).background(CEColor.Deep)) {
                    Box(Modifier.width(width).fillMaxHeight()) {
                        val seconds = (width.value / state.pixelsPerSecond).toInt()
                        val interval = if (state.pixelsPerSecond >= 48) 2 else if (state.pixelsPerSecond >= 16) 4 else 8
                        (0..seconds step interval).forEach { second ->
                            Text(ceTime(second.toLong() * CONTINUOUS_TIMELINE_RATE), Modifier.offset(x = (second * state.pixelsPerSecond).dp + 8.dp).padding(top = 8.dp),
                                color = CEColor.Cream, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal).verticalScroll(vertical)) {
                    Box(Modifier.width(width).height(rowHeight * rows).background(CEColor.Deep)
                        .onGloballyPositioned { coordinates -> onTarget(CEPlacementTarget(coordinates.boundsInRoot(), coordinates.positionInRoot(),
                            with(density) { rowHeight.toPx() }, with(density) { state.pixelsPerSecond.dp.toPx() })) }) {
                        Canvas(Modifier.matchParentSize().pointerInput(state.pixelsPerSecond, state.permits(ContinuousCapability.SONG_SEEK)) {
                            if (state.permits(ContinuousCapability.SONG_SEEK)) detectTapGestures { at ->
                                val frame = (at.x / with(density) { state.pixelsPerSecond.dp.toPx() } * CONTINUOUS_TIMELINE_RATE).roundToLong()
                                onAction(ContinuousEditorAction.SeekSong(frame.coerceAtLeast(0)))
                            }
                        }) {
                            val pps = state.pixelsPerSecond.dp.toPx()
                            for (index in 0..rows) drawLine(CEColor.Border.copy(alpha = .5f), Offset(0f, index * rowHeight.toPx()), Offset(size.width, index * rowHeight.toPx()))
                            var x = 0f
                            while (x < size.width) { drawLine(CEColor.Border.copy(alpha = .4f), Offset(x, 0f), Offset(x, size.height)); x += pps * 4 }
                        }
                        state.clips.forEach { clip ->
                            val trackIndex = state.tracks.indexOfFirst { it.id == clip.trackId }
                            if (trackIndex >= 0) CEClip(clip, state.tracks[trackIndex], trackIndex, state, onAction, state.pixelsPerSecond, rowHeight)
                        }
                        if (state.clips.isEmpty()) Text(stringResource(Res.string.ce_no_clips), Modifier.padding(16.dp), color = CEColor.Tan, fontSize = 14.sp)
                        Canvas(Modifier.matchParentSize()) {
                            val x = (live.value.songFrame.toDouble() / CONTINUOUS_TIMELINE_RATE * state.pixelsPerSecond).toFloat().dp.toPx()
                            drawLine(CEColor.Cream, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CEClip(clip: ContinuousClip, track: ContinuousTrack, trackIndex: Int, state: ContinuousEditorState,
    onAction: (ContinuousEditorAction) -> Unit, pixelsPerSecond: Float, rowHeight: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val selected = state.selectedClipId == clip.id
    val visualWidth = (clip.timelineDurationFrames.toDouble() / CONTINUOUS_TIMELINE_RATE * pixelsPerSecond).toFloat().dp
    val hitWidth = visualWidth.coerceAtLeast(48.dp)
    val left = (clip.timelineStartFrame.toDouble() / CONTINUOUS_TIMELINE_RATE * pixelsPerSecond).toFloat().dp
    var drag by remember(clip.id) { mutableStateOf(Offset.Zero) }
    var trimEdge by remember(clip.id) { mutableIntStateOf(0) }
    var pressX by remember(clip.id) { mutableFloatStateOf(0f) }
    val latestAction by rememberUpdatedState(onAction)
    fun finishDrag() {
        val deltaFrames = (drag.x / with(density) { pixelsPerSecond.dp.toPx() } * CONTINUOUS_TIMELINE_RATE).roundToLong()
        if (trimEdge == 0 && state.permits(ContinuousCapability.MOVE_CLIP)) {
            val rowDelta = (drag.y / with(density) { rowHeight.toPx() }).roundToInt()
            val destination = state.tracks[(trackIndex + rowDelta).coerceIn(0, state.tracks.lastIndex)]
            latestAction(ContinuousEditorAction.MoveClip(clip.id, destination.id, (clip.timelineStartFrame + deltaFrames).coerceAtLeast(0)))
        } else if (state.permits(ContinuousCapability.TRIM_CLIP)) {
            val sourceLength = clip.sourceEndFrame - clip.sourceStartFrame
            val boundedTimelineDelta = deltaFrames.coerceAtLeast(-clip.timelineStartFrame)
            val sourceDelta = (boundedTimelineDelta.toDouble() * sourceLength / clip.timelineDurationFrames).roundToLong()
            if (trimEdge < 0) {
                val start = (clip.sourceStartFrame + sourceDelta).coerceIn(0, clip.sourceEndFrame - 1)
                val shift = ((start - clip.sourceStartFrame).toDouble() * clip.timelineDurationFrames / sourceLength).roundToLong()
                latestAction(ContinuousEditorAction.TrimClip(clip.id, start, clip.sourceEndFrame, (clip.timelineStartFrame + shift).coerceAtLeast(0)))
            } else latestAction(ContinuousEditorAction.TrimClip(clip.id, clip.sourceStartFrame,
                (clip.sourceEndFrame + sourceDelta).coerceIn(clip.sourceStartFrame + 1, clip.sourceTotalFrames), clip.timelineStartFrame))
        }
        drag = Offset.Zero
    }
    val label = stringResource(Res.string.ce_clip_select, clip.title)
    val earlier = stringResource(Res.string.ce_clip_move_left)
    val later = stringResource(Res.string.ce_clip_move_right)
    val trimStart = stringResource(Res.string.ce_trim_start)
    val trimEnd = stringResource(Res.string.ce_trim_end)
    val positionLabel = stringResource(Res.string.ce_clip_position, ceTime(clip.timelineStartFrame, precise = true), ceTime(clip.timelineDurationFrames, precise = true))
    Box(Modifier.offset { IntOffset(with(density) { left.roundToPx() } + (if (trimEdge == 0) drag.x.roundToInt() else 0),
            with(density) { (rowHeight * trackIndex + 8.dp).roundToPx() } + (if (trimEdge == 0) drag.y.roundToInt() else 0)) }
        .width(hitWidth).height(rowHeight - 16.dp).testTag("ce-clip-${clip.id}")
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Enter, Key.Spacebar -> { latestAction(ContinuousEditorAction.SelectClip(clip.id)); true }
                Key.DirectionLeft, Key.DirectionRight -> if (state.permits(ContinuousCapability.MOVE_CLIP)) {
                    val direction = if (event.key == Key.DirectionLeft) -1 else 1
                    latestAction(ContinuousEditorAction.MoveClip(clip.id, clip.trackId, (clip.timelineStartFrame + direction * CONTINUOUS_TIMELINE_RATE).coerceAtLeast(0))); true
                } else false
                Key.Delete, Key.Backspace -> if (state.permits(ContinuousCapability.DELETE_CLIP)) { latestAction(ContinuousEditorAction.DeleteClip(clip.id)); true } else false
                else -> false
            }
        }.focusable().semantics(mergeDescendants = true) {
            contentDescription = label; stateDescription = positionLabel; this.selected = selected; role = Role.Button
            onClick { latestAction(ContinuousEditorAction.SelectClip(clip.id)); true }
            customActions = buildList {
                if (state.permits(ContinuousCapability.MOVE_CLIP)) {
                    add(CustomAccessibilityAction(earlier) { latestAction(ContinuousEditorAction.MoveClip(clip.id, clip.trackId, (clip.timelineStartFrame - CONTINUOUS_TIMELINE_RATE).coerceAtLeast(0))); true })
                    add(CustomAccessibilityAction(later) { latestAction(ContinuousEditorAction.MoveClip(clip.id, clip.trackId, clip.timelineStartFrame + CONTINUOUS_TIMELINE_RATE)); true })
                }
                if (state.permits(ContinuousCapability.TRIM_CLIP)) {
                    add(CustomAccessibilityAction(trimStart) { latestAction(ContinuousEditorAction.TrimClip(clip.id, (clip.sourceStartFrame + 1).coerceAtMost(clip.sourceEndFrame - 1), clip.sourceEndFrame, clip.timelineStartFrame)); true })
                    add(CustomAccessibilityAction(trimEnd) { latestAction(ContinuousEditorAction.TrimClip(clip.id, clip.sourceStartFrame, (clip.sourceEndFrame - 1).coerceAtLeast(clip.sourceStartFrame + 1), clip.timelineStartFrame)); true })
                }
            }
        }.pointerInput(clip.id) { detectTapGestures(onPress = { pressX = it.x }, onTap = { latestAction(ContinuousEditorAction.SelectClip(clip.id)) }) }
        .pointerInput(clip, state.permits(ContinuousCapability.MOVE_CLIP), state.permits(ContinuousCapability.TRIM_CLIP)) {
            detectDragGestures(onDragStart = { at ->
                latestAction(ContinuousEditorAction.SelectClip(clip.id))
                trimEdge = if (!state.permits(ContinuousCapability.TRIM_CLIP)) 0 else when {
                    pressX < 12.dp.toPx() -> -1
                    pressX > with(density) { visualWidth.toPx() } - 12.dp.toPx() -> 1
                    else -> 0
                }
            }, onDragEnd = ::finishDrag, onDragCancel = { drag = Offset.Zero }) { change, amount -> change.consume(); drag += amount }
        }) {
        Column(Modifier.width(visualWidth.coerceAtLeast(6.dp)).fillMaxHeight().clip(RoundedCornerShape(6.dp))
            .background(Color(track.color).copy(alpha = if (track.muted) .15f else .28f))
            .border(if (selected) 3.dp else 1.dp, if (selected) CEColor.Orange else Color(track.color).copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(6.dp)) {
            if (visualWidth >= 48.dp) Text(clip.title, color = CEColor.Cream, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val color = Color(track.color)
                val mid = size.height / 2
                clip.peaks.forEachIndexed { index, peak ->
                    val x = (index + .5f) / clip.peaks.size * size.width
                    val magnitude = if (peak.isFinite()) abs(peak).coerceIn(0f, 1f) else 0f
                    drawLine(color, Offset(x, mid - magnitude * mid), Offset(x, mid + magnitude * mid), 1.5.dp.toPx())
                }
            }
        }
    }
}
