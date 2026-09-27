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
import com.choplab.core.ProgramCompiler
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
            // While a take records, the PAD pane (where its stop button is) says so from the timeline pane too.
            CEButton(stringResource(if (state.recordingVoice) Res.string.ce_pads_recording else Res.string.ce_pads),
                { onAction(ContinuousEditorAction.SelectCompactPane(ContinuousPane.PADS)) }, Modifier.weight(1f),
                primary = state.compactPane == ContinuousPane.PADS, tag = "ce-pane-pads")
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            @Composable fun Audition(modifier: Modifier) = CEActionButton(stringResource(Res.string.ce_audition), ContinuousEditorAction.TapPad(state.selectedPadId),
                state, ContinuousCapability.PAD_AUDITION, onAction, modifier, tag = "ce-pad-audition", additionallyEnabled = pad != null && pad.kind != ContinuousPadKind.EMPTY)
            @Composable fun Loop(modifier: Modifier) = CEActionButton(stringResource(if (pad?.looping == true) Res.string.ce_stop_loop else Res.string.ce_loop_pad),
                ContinuousEditorAction.TogglePadLoop(state.selectedPadId), state, ContinuousCapability.PAD_LOOP, onAction, modifier, primary = pad?.looping == true)
            @Composable fun Place(modifier: Modifier) = CEButton(stringResource(Res.string.ce_place),
                { onAction(ContinuousEditorAction.PlacePad(state.selectedPadId, state.selectedTrackId, readout().songFrame)) },
                modifier, state.permits(ContinuousCapability.PLACE_PAD) && pad != null && pad.kind != ContinuousPadKind.EMPTY,
                primary = true, reason = CEReason(state, ContinuousCapability.PLACE_PAD), tag = "ce-place-pad")
            // With the PAD's own actions, so the reference layout keeps its bottom actions in view.
            @Composable fun Play(modifier: Modifier) = CEActionButton(stringResource(Res.string.ce_pad_play), ContinuousEditorAction.OpenPadPlay,
                state, ContinuousCapability.PAD_PLAY, onAction, modifier, tag = "ce-pad-play")
            if (maxWidth >= 440.dp && LocalDensity.current.fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Audition(Modifier.weight(1f)); Loop(Modifier.weight(1.6f)); Place(Modifier.weight(1.3f)); Play(Modifier.weight(1.1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Audition(Modifier.weight(1f)); Loop(Modifier.weight(1.6f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Place(Modifier.weight(1.3f)); Play(Modifier.weight(1.1f)) }
            }
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
                { onAction(ContinuousEditorAction.SetPadTone(state.selectedPadId, it)) }, modifier = Modifier.weight(1f), maximum = 1f)
            CEAdjustment(stringResource(Res.string.ce_gain), pad?.gain ?: 1f, state, ContinuousCapability.PAD_GAIN,
                { onAction(ContinuousEditorAction.SetPadGain(state.selectedPadId, it)) }, modifier = Modifier.weight(1f))
        }
        }
        if (maxWidth >= 510.dp && LocalDensity.current.fontScale <= 1.3f) Adjustments()
        else Box(Modifier.horizontalScroll(rememberScrollState())) { Box(Modifier.width(540.dp)) { Adjustments() } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEActionButton(stringResource(if (state.installedDrumKit == null) Res.string.ce_add_drums else Res.string.ce_change_drums), ContinuousEditorAction.AddDrum,
                state, ContinuousCapability.ADD_DRUM, onAction, Modifier.weight(1f), tag = "ce-add-drums")
            if (state.recordingVoice) CEActionButton(stringResource(Res.string.ce_stop_voice), ContinuousEditorAction.StopVoice, state,
                ContinuousCapability.STOP_ALL, onAction, Modifier.weight(1f), primary = true, tag = "ce-record-voice")
            else CEActionButton(stringResource(Res.string.ce_record_voice), ContinuousEditorAction.RecordVoice, state, ContinuousCapability.RECORD_VOICE, onAction, Modifier.weight(1f), tag = "ce-record-voice")
            CEActionButton(stringResource(Res.string.ce_scratch), ContinuousEditorAction.OpenScratch, state, ContinuousCapability.SCRATCH, onAction, Modifier.weight(1f), tag = "ce-scratch")
        }
        // Below the button that started the take, so nothing it pressed moves; screen readers hear it appear.
        if (state.recordingVoice) Text(stringResource(Res.string.ce_voice_hint), Modifier.fillMaxWidth().testTag("ce-voice-hint")
            .semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = CEColor.Ink)
    }
}

@Composable private fun CETimelinePanel(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, modifier: Modifier, onTarget: (CEPlacementTarget) -> Unit) {
    val clip = state.selectedClip
    // A short pane (a phone, large text) scrolls and gives the tracks a fixed height, so every control stays in reach.
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp)).background(CEColor.Ink)) {
    val roomy = maxHeight >= 520.dp
    Column(Modifier.fillMaxSize().then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(12.dp)
        .testTag("ce-arrangement-pane"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        CETimelineTools(state, onAction)
        CETimelineGrid(state, onAction, readout, refreshKey, if (roomy) Modifier.weight(1f).fillMaxWidth() else Modifier.height(300.dp).fillMaxWidth(), onTarget)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, CEColor.Border, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (clip == null) stringResource(Res.string.ce_no_selected_clip) else stringResource(Res.string.ce_selected_clip, clip.title), color = CEColor.Green, fontSize = 14.sp, lineHeight = 20.sp)
            if (clip != null) {
                Text(stringResource(Res.string.ce_clip_position, ceTime(clip.timelineStartFrame, precise = true), ceTime(clip.timelineDurationFrames, precise = true)), color = CEColor.Cream, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                // Keyed by clip, so a gain dragged on one clip is never shown on the next selection.
                key(clip.id) {
                    CEValueSlider(stringResource(Res.string.ce_clip_gain), clip.gain, state, ContinuousCapability.CLIP_GAIN,
                        { onAction(ContinuousEditorAction.SetClipGain(clip.id, it)) }, Modifier.fillMaxWidth(), dark = true, tag = "ce-clip-gain", range = 0f..2f,
                        commitOnRelease = true)
                }
            }
        }
    }
    }
}

/** Zoom, and what placing and moving snap to: one row where both fit, otherwise one each (phones, large text). */
@Composable private fun CETimelineTools(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val zoomOut = stringResource(Res.string.ce_zoom_out)
        val zoomIn = stringResource(Res.string.ce_zoom_in)
        @Composable fun Zoom() = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CEButton("−", { onAction(ContinuousEditorAction.SetPixelsPerSecond((state.pixelsPerSecond / 1.25f).coerceAtLeast(4f))) }, dark = true,
                modifier = Modifier.semantics { contentDescription = zoomOut }, tag = "ce-zoom-out")
            CEButton("+", { onAction(ContinuousEditorAction.SetPixelsPerSecond((state.pixelsPerSecond * 1.25f).coerceAtMost(240f))) }, dark = true,
                modifier = Modifier.semantics { contentDescription = zoomIn }, tag = "ce-zoom-in")
            CEButton(stringResource(Res.string.ce_fit), { onAction(ContinuousEditorAction.FitTimeline) }, dark = true)
        }
        val snap = stringResource(Res.string.ce_grid)
        @Composable fun Grid() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(snap, color = CEColor.Cream, fontSize = 14.sp)
            for ((grid, label) in listOf(ContinuousGrid.BEAT to Res.string.ce_grid_beat, ContinuousGrid.HALF to Res.string.ce_grid_half,
                    ContinuousGrid.QUARTER to Res.string.ce_grid_quarter, ContinuousGrid.FREE to Res.string.ce_free)) {
                val text = stringResource(label)
                CEButton(text, { onAction(ContinuousEditorAction.SetGrid(grid)) },
                    Modifier.semantics { contentDescription = "$snap $text"; selected = state.grid == grid },
                    dark = true, primary = state.grid == grid, tag = "ce-grid-${grid.name.lowercase()}")
            }
        }
        if (maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.3f) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) { Zoom(); Grid() }
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Zoom(); Box(Modifier.horizontalScroll(rememberScrollState())) { Grid() } }
    }
}

/**
 * The song timeline's lines between [from] and [to] pixels, each an x and how strong it is: 2 a bar, 1 a beat, 0 a
 * finer [grid] line. Lines closer than [minimumGap] are left out: finer lines first, then beats, then all but every
 * few bars.
 */
internal fun ceGridLines(milliBpm: Int, grid: ContinuousGrid, pxPerSecond: Float, minimumGap: Float, from: Float, to: Float): List<Pair<Float, Int>> {
    val beat = 60_000f / milliBpm * pxPerSecond
    val finer = if (grid == ContinuousGrid.FREE) 960 else grid.ticks
    val unitTicks = when {
        beat * finer / 960 >= minimumGap -> finer
        beat >= minimumGap -> 960
        else -> 3_840 * kotlin.math.ceil(minimumGap / (beat * 4)).toInt()
    }
    val unit = beat * unitTicks / 960
    return (floor(from / unit).toLong().coerceAtLeast(0)..kotlin.math.ceil(to / unit).toLong()).map { index ->
        val ticks = index * unitTicks
        index * unit to when { ticks % 3_840 == 0L -> 2; ticks % 960 == 0L -> 1; else -> 0 }
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
            Box(Modifier.height(38.dp), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(Res.string.ce_bars), color = CEColor.Tan, fontSize = 12.sp)
            }
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
                    Box(Modifier.width(width).fillMaxHeight().testTag("ce-ruler")) {
                        // Bar numbers, a few bars apart where bars are narrow.
                        val bar = 4 * 60_000f / state.milliBpm * state.pixelsPerSecond
                        val every = kotlin.math.ceil(44f / bar).toInt().coerceAtLeast(1)
                        (0..(width.value / bar).toInt() step every).forEach { index ->
                            Text("${index + 1}", Modifier.offset(x = (index * bar).dp + 6.dp).padding(top = 8.dp),
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
                            val from = horizontal.value.toFloat()
                            ceGridLines(state.milliBpm, state.grid, pps, 6.dp.toPx(), from, from + horizontal.viewportSize).forEach { (x, strength) ->
                                drawLine(CEColor.Border.copy(alpha = when (strength) { 2 -> .6f; 1 -> .3f; else -> .15f }),
                                    Offset(x, 0f), Offset(x, size.height), if (strength == 2) 2.dp.toPx() else 1.dp.toPx())
                            }
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
                val start = (clip.sourceStartFrame + sourceDelta).coerceIn(ContinuousClipEdits.trimStartRange(clip))
                val shift = ((start - clip.sourceStartFrame).toDouble() * clip.timelineDurationFrames / sourceLength).roundToLong()
                latestAction(ContinuousEditorAction.TrimClip(clip.id, start, clip.sourceEndFrame, (clip.timelineStartFrame + shift).coerceAtLeast(0)))
            } else latestAction(ContinuousEditorAction.TrimClip(clip.id, clip.sourceStartFrame,
                (clip.sourceEndFrame + sourceDelta).coerceIn(ContinuousClipEdits.trimEndRange(clip)), clip.timelineStartFrame))
        }
        drag = Offset.Zero
    }
    val label = stringResource(Res.string.ce_clip_select, clip.title)
    val earlier = stringResource(Res.string.ce_clip_move_left)
    val later = stringResource(Res.string.ce_clip_move_right)
    val trimStart = stringResource(Res.string.ce_trim_start)
    val trimEnd = stringResource(Res.string.ce_trim_end)
    val positionLabel = stringResource(Res.string.ce_clip_position, ceTime(clip.timelineStartFrame, precise = true), ceTime(clip.timelineDurationFrames, precise = true))
    // A move shows where the clip will land: on a grid, the line nearest the finger, as the edit will put it.
    fun landing(dx: Float): Int {
        if (dx == 0f) return 0
        val pps = with(density) { pixelsPerSecond.dp.toPx() }
        val raw = (clip.timelineStartFrame + (dx / pps * CONTINUOUS_TIMELINE_RATE).roundToLong()).coerceAtLeast(0)
        val frame = ContinuousClipEdits.snapTick(raw, state.milliBpm, state.grid)?.let { ProgramCompiler.tickToFrame(it, state.milliBpm) } ?: raw
        return ((frame - clip.timelineStartFrame).toDouble() / CONTINUOUS_TIMELINE_RATE * pps).roundToInt()
    }
    Box(Modifier.offset { IntOffset(with(density) { left.roundToPx() } + (if (trimEdge == 0) landing(drag.x) else 0),
            with(density) { (rowHeight * trackIndex + 8.dp).roundToPx() } + (if (trimEdge == 0) drag.y.roundToInt() else 0)) }
        .width(hitWidth).height(rowHeight - 16.dp).testTag("ce-clip-${clip.id}")
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.Enter, Key.Spacebar -> { latestAction(ContinuousEditorAction.SelectClip(clip.id)); true }
                Key.DirectionLeft, Key.DirectionRight -> if (state.permits(ContinuousCapability.MOVE_CLIP)) {
                    latestAction(ContinuousEditorAction.NudgeClip(clip.id, forward = event.key == Key.DirectionRight)); true
                } else false
                Key.Delete, Key.Backspace -> if (state.permits(ContinuousCapability.DELETE_CLIP)) { latestAction(ContinuousEditorAction.DeleteClip(clip.id)); true } else false
                else -> false
            }
        }.focusable().semantics(mergeDescendants = true) {
            contentDescription = label; stateDescription = positionLabel; this.selected = selected; role = Role.Button
            onClick { latestAction(ContinuousEditorAction.SelectClip(clip.id)); true }
            customActions = buildList {
                if (state.permits(ContinuousCapability.MOVE_CLIP)) {
                    add(CustomAccessibilityAction(earlier) { latestAction(ContinuousEditorAction.NudgeClip(clip.id, forward = false)); true })
                    add(CustomAccessibilityAction(later) { latestAction(ContinuousEditorAction.NudgeClip(clip.id, forward = true)); true })
                }
                if (state.permits(ContinuousCapability.TRIM_CLIP)) {
                    // At the one-frame minimum these become no-ops instead of creating a silent clip.
                    val latestStart = maxOf(clip.sourceStartFrame, ContinuousClipEdits.trimStartRange(clip).last)
                    val earliestEnd = minOf(clip.sourceEndFrame, ContinuousClipEdits.trimEndRange(clip).first)
                    add(CustomAccessibilityAction(trimStart) { latestAction(ContinuousEditorAction.TrimClip(clip.id, minOf(clip.sourceStartFrame + 1, latestStart), clip.sourceEndFrame, clip.timelineStartFrame)); true })
                    add(CustomAccessibilityAction(trimEnd) { latestAction(ContinuousEditorAction.TrimClip(clip.id, clip.sourceStartFrame, maxOf(clip.sourceEndFrame - 1, earliestEnd), clip.timelineStartFrame)); true })
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
