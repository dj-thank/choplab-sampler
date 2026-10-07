@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.choplab.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.ui.resources.*
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Exact dominant pixels of the user-selected linked-workspace reference. */
internal object CEColor {
    val Cream = Color(0xFFEDE2C8)
    val Tan = Color(0xFFD3C5A5)
    val Ink = Color(0xFF211D13)
    val Deep = Color(0xFF0F0D08)
    val Empty = Color(0xFF262116)
    val Orange = Color(0xFFFF7417)
    val Green = Color(0xFFA4C867)
    val FilledPad = Color(0xFF45623C)
    val Border = Color(0xFF6B6147)
}

@Composable internal fun CETheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = CEColor.Orange, onPrimary = CEColor.Ink,
        secondary = CEColor.Green, onSecondary = CEColor.Ink, background = CEColor.Cream,
        surface = CEColor.Tan, onSurface = CEColor.Ink, onBackground = CEColor.Ink,
        outline = CEColor.Ink, surfaceVariant = CEColor.Tan, onSurfaceVariant = CEColor.Ink), content = content)
}

@Composable internal fun CEReason(state: ContinuousEditorState, capability: ContinuousCapability): String = stringResource(
    when (state.unavailable[capability] ?: ContinuousUnavailable.NOT_CONNECTED) {
        ContinuousUnavailable.NOT_CONNECTED -> Res.string.ce_unavailable
        ContinuousUnavailable.BUSY -> Res.string.ce_busy
        ContinuousUnavailable.NO_SOURCE -> Res.string.ce_missing_source
        ContinuousUnavailable.EMPTY_PAD -> Res.string.ce_missing_pad
        ContinuousUnavailable.NO_CLIP -> Res.string.ce_missing_clip
        ContinuousUnavailable.NO_OUTPUT -> Res.string.ce_no_output
        ContinuousUnavailable.NO_SONG -> Res.string.ce_missing_song
        ContinuousUnavailable.RECORDING -> Res.string.ce_recording_busy
    })

@Composable internal fun CEButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                                  enabled: Boolean = true, primary: Boolean = false, dark: Boolean = false,
                                  reason: String = "", tag: String? = null) {
    val shape = RoundedCornerShape(8.dp)
    Button(onClick, modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .then(if (tag == null) Modifier else Modifier.testTag(tag))
        .semantics { if (!enabled && reason.isNotBlank()) stateDescription = reason }, enabled = enabled,
        shape = shape, border = BorderStroke(2.dp, if (dark) CEColor.Border else CEColor.Ink),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) CEColor.Orange else if (dark) CEColor.Empty else CEColor.Tan,
            contentColor = if (dark && !primary) CEColor.Cream else CEColor.Ink,
            disabledContainerColor = if (dark) CEColor.Empty else CEColor.Tan,
            disabledContentColor = if (dark) CEColor.Tan.copy(alpha = .65f) else CEColor.Ink.copy(alpha = .55f))) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable internal fun CEActionButton(label: String, action: ContinuousEditorAction, state: ContinuousEditorState,
    capability: ContinuousCapability, onAction: (ContinuousEditorAction) -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, dark: Boolean = false, tag: String? = null, additionallyEnabled: Boolean = true) {
    CEButton(label, { onAction(action) }, modifier, state.permits(capability) && additionallyEnabled, primary, dark,
        CEReason(state, capability), tag)
}

@Composable internal fun CEValueSlider(label: String, value: Float, state: ContinuousEditorState,
    capability: ContinuousCapability, onValue: (Float) -> Unit, modifier: Modifier = Modifier,
    dark: Boolean = false, tag: String = "", range: ClosedFloatingPointRange<Float> = 0f..1f,
    /** Document edits commit once when the gesture ends (one Undo step); monitoring follows every move. */
    commitOnRelease: Boolean = false,
    /** The label above the slider, which then has the whole width: for narrow places and large text. */
    stacked: Boolean = false) {
    val foreground = if (dark) CEColor.Cream else CEColor.Ink
    // The dragged value stays shown until the committed value arrives, or is dropped if the edit fails.
    var pending by remember(value) { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.status) { if (state.status == ContinuousStatus.FAILED) pending = null }
    val shown = pending ?: value
    @Composable fun RowScope.SliderAndValue() {
        val enabled = state.permits(capability)
        val interaction = remember { MutableInteractionSource() }
        val colors = SliderDefaults.colors(thumbColor = CEColor.Orange, activeTrackColor = CEColor.Orange,
            inactiveTrackColor = CEColor.Tan, activeTickColor = CEColor.Tan, inactiveTickColor = CEColor.Tan)
        Slider(shown.coerceIn(range.start, range.endInclusive), { next -> if (commitOnRelease) pending = next else onValue(next) },
            Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = label },
            enabled = enabled, valueRange = range, interactionSource = interaction,
            onValueChangeFinished = { if (commitOnRelease) pending?.let(onValue) },
            colors = colors,
            // Material's default thumb is 44 dp tall; the actual input and semantics rectangle must reach 48 dp.
            thumb = { SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled,
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 48.dp)) })
        Text(stringResource(Res.string.ce_percent, (shown * 100).roundToInt()), color = foreground,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, softWrap = false)
    }
    if (stacked) Column(modifier) {
        Text(label, color = foreground, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { SliderAndValue() }
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = foreground, fontSize = 12.sp)
        SliderAndValue()
    }
}

@Composable internal fun CELive(playing: Boolean, refreshKey: Long, read: () -> ContinuousEditorReadout): State<ContinuousEditorReadout> {
    val latestRead by rememberUpdatedState(read)
    val current = remember { mutableStateOf(read()) }
    LaunchedEffect(playing, refreshKey) {
        current.value = latestRead()
        if (playing) while (true) withFrameNanos { current.value = latestRead() }
    }
    return current
}

@Composable internal fun CEWaveform(peaks: List<Float>, modifier: Modifier, label: String,
    position: () -> Float = { 0f }, range: ClosedFloatingPointRange<Float>? = null,
    color: Color = CEColor.Green, onSeek: ((Float) -> Unit)? = null, tag: String = "", markers: List<Float> = emptyList(),
    positionText: (() -> String)? = null) {
    val latestSeek by rememberUpdatedState(onSeek)
    var focused by remember { mutableStateOf(false) }
    fun current() = position().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    fun seek(value: Float): Boolean {
        if (!value.isFinite()) return false
        val action = latestSeek ?: return false
        action(value.coerceIn(0f, 1f)); return true
    }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(CEColor.Deep)
        .border(if (focused) 3.dp else 2.dp, if (focused) CEColor.Cream else CEColor.Ink, RoundedCornerShape(8.dp))
        .testTag(tag).semantics {
            contentDescription = label
            positionText?.let { stateDescription = it() }
            if (onSeek != null) {
                progressBarRangeInfo = ProgressBarRangeInfo(current(), 0f..1f)
                setProgress { seek(it) }
            }
        }.onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (onSeek == null || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) false
            else when (event.key) {
                Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd -> {
                    if (event.type == KeyEventType.KeyDown) seek(when (event.key) {
                        Key.MoveHome -> 0f; Key.MoveEnd -> 1f
                        Key.DirectionLeft -> current() - .01f
                        else -> current() + .01f
                    })
                    true
                }
                else -> false
            }
        }.focusable(enabled = onSeek != null)
        .pointerInput(onSeek != null) {
            if (latestSeek != null) detectTapGestures { offset -> latestSeek?.invoke((offset.x / size.width).coerceIn(0f, 1f)) }
        }) {
        val mid = size.height / 2
        val path = Path()
        if (peaks.isNotEmpty()) {
            path.moveTo(0f, mid)
            peaks.forEachIndexed { index, value ->
                val magnitude = if (value.isFinite()) abs(value).coerceIn(0f, 1f) else 0f
                path.lineTo(index.toFloat() / peaks.lastIndex.coerceAtLeast(1) * size.width, mid - magnitude * size.height * .43f)
            }
            for (index in peaks.indices.reversed()) {
                val value = peaks[index]
                val magnitude = if (value.isFinite()) abs(value).coerceIn(0f, 1f) else 0f
                path.lineTo(index.toFloat() / peaks.lastIndex.coerceAtLeast(1) * size.width, mid + magnitude * size.height * .43f)
            }
            path.close(); drawPath(path, color)
        }
        range?.let {
            drawLine(CEColor.Orange, Offset(it.start * size.width, 0f), Offset(it.start * size.width, size.height), 2.dp.toPx())
            drawLine(CEColor.Orange, Offset(it.endInclusive * size.width, 0f), Offset(it.endInclusive * size.width, size.height), 2.dp.toPx())
        }
        markers.forEach { marker ->
            val at = marker.coerceIn(0f, 1f) * size.width
            drawLine(CEColor.Cream, Offset(at, 0f), Offset(at, size.height), 1.dp.toPx())
        }
        val x = position().coerceIn(0f, 1f) * size.width
        drawLine(CEColor.Orange, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

@Composable internal fun CEBanks(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = ((maxWidth - 18.dp) / 4).coerceAtLeast(96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.banks.forEach { bank ->
                val fallback = when (bank.id) {
                    0 -> stringResource(Res.string.ce_bank_melody); 1 -> stringResource(Res.string.ce_bank_drums)
                    2 -> stringResource(Res.string.ce_bank_oneshot); 3 -> stringResource(Res.string.ce_bank_voice); else -> ""
                }
                CEBankMetadataButton(bank.id, bank.name.ifBlank { fallback }, bank.color, bank.role, bank.id == state.selectedBank,
                    { onAction(ContinuousEditorAction.SelectBank(bank.id)) }, Modifier.width(width))
            }
        }
    }
}

internal data class CEPaddedDrag(val padId: Int, val rootPosition: Offset)

/**
 * Square 4x4 grid. Long-hold is explicitly owned; tap and cancelled scroll stay distinct. With [capture] (a live chop
 * pass), a PAD reads the original's position as it goes down and cuts there when released; a cancelled touch cuts nothing.
 * With [hit] (recording what the PADs play), a PAD sounds and reads the song's position as it goes down, and is recorded
 * there when released; a touch that turns into a scroll records nothing.
 */
@Composable internal fun CEPads(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    modifier: Modifier = Modifier, maximumSide: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity,
    onPadDrag: ((CEPaddedDrag?) -> Unit)? = null, onPadDrop: ((Int, Offset) -> Unit)? = null,
    capture: (() -> ContinuousChopGesture?)? = null, hit: (() -> Long?)? = null) {
    val font = LocalDensity.current.fontScale
    val latestCapture by rememberUpdatedState(capture)
    val latestHit by rememberUpdatedState(hit)
    val cutLabel = stringResource(Res.string.ce_chop_pad_action)
    val playLabel = stringResource(Res.string.ce_hits_pad_action)
    val repeatLabel = stringResource(Res.string.ce_note_repeat_click)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("ce-pad-grid")) {
        // Fill the instrument's width. Narrow windows scroll instead of making a PAD too small to play or read.
        val minimumSide = if (font > 1.5f) 80.dp else 64.dp
        val side = ((maxWidth - 18.dp) / 4).coerceIn(minimumSide, maximumSide.coerceAtLeast(minimumSide))
        Column(Modifier.align(Alignment.Center).horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..3).forEach { col ->
                    val id = state.selectedBank * 16 + row * 4 + col
                    val pad = state.pads.firstOrNull { it.id == id } ?: ContinuousPad(id)
                    val interaction = remember(id) { MutableInteractionSource() }
                    var held by remember(id) { mutableStateOf(false) }
                    val latestAction by rememberUpdatedState(onAction)
                    val latestDrag by rememberUpdatedState(onPadDrag)
                    val latestDrop by rememberUpdatedState(onPadDrop)
                    var rootOrigin by remember(id) { mutableStateOf(Offset.Zero) }
                    fun release() { if (held) { held = false; latestAction(ContinuousEditorAction.ReleasePad(id)) } }
                    LaunchedEffect(interaction) { interaction.interactions.collect { if (it is PressInteraction.Release || it is PressInteraction.Cancel) release() } }
                    DisposableEffect(id) { onDispose { release() } }
                    val mode = stringResource(when (pad.mode) { ContinuousPadMode.ONE_SHOT -> Res.string.ce_mode_one; ContinuousPadMode.GATE -> Res.string.ce_mode_gate; ContinuousPadMode.LOOP -> Res.string.ce_mode_loop })
                    val name = pad.name.ifBlank { stringResource(Res.string.ce_empty) }
                    val bank = state.banks.firstOrNull { it.id == state.selectedBank }
                    val bankDescription = bank?.let { bankMetadataDescription(it.id, it.name, it.role) }.orEmpty()
                    val description = stringResource(Res.string.ce_pad_semantics, ('A' + state.selectedBank).toString(), id % 16 + 1, name, mode) + " · " + bankDescription
                    val selected = id == state.selectedPadId
                    val filled = pad.kind != ContinuousPadKind.EMPTY
                    val keyboardMode = capture != null || hit != null || state.noteRepeat != ContinuousNoteRepeat.OFF
                    val keyboardAllowed = capture != null || (filled && state.permits(ContinuousCapability.PAD_AUDITION))
                    val keyboard = remember(id, capture != null, hit != null, filled, pad.mode, state.noteRepeat, keyboardAllowed) {
                        CEPadKeyGesture(id, capture != null, pad.mode == ContinuousPadMode.GATE,
                            state.noteRepeat != ContinuousNoteRepeat.OFF,
                            { latestCapture?.invoke() }, { latestHit?.invoke() }, { latestAction(it) })
                    }
                    DisposableEffect(keyboard) { onDispose { keyboard.cancel() } }
                    var focused by remember(id) { mutableStateOf(false) }
                    Box(Modifier.size(side).clip(RoundedCornerShape(8.dp)).background(if (filled) CEColor.FilledPad else CEColor.Empty)
                        .border(if (selected) 3.dp else 2.dp, if (selected) CEColor.Orange else CEColor.Ink, RoundedCornerShape(8.dp))
                        .then(if (focused) Modifier.border(2.dp, CEColor.Cream, RoundedCornerShape(6.dp)) else Modifier)
                        .onFocusChanged { focused = it.isFocused; if (!it.isFocused) { keyboard.cancel(); release() } }
                        .onGloballyPositioned { rootOrigin = it.positionInRoot() }
                        .testTag("ce-pad-$id")
                        .then(if (capture != null) Modifier.pointerInput(id) {
                            detectTapGestures(onPress = {
                                val frame = latestCapture?.invoke()
                                if (tryAwaitRelease() && frame != null) latestAction(ContinuousEditorAction.CapturePad(id, frame))
                            })
                        } else if (hit != null || state.noteRepeat != ContinuousNoteRepeat.OFF) Modifier.pointerInput(id, filled, pad.mode, state.noteRepeat) {
                            // As a tap plays it: a one-shot plays out and a loop keeps looping; a PAD that sounds while
                            // held stops when let go.
                            val whileHeld = pad.mode == ContinuousPadMode.GATE || state.noteRepeat != ContinuousNoteRepeat.OFF
                            if (filled && state.permits(ContinuousCapability.PAD_AUDITION)) detectTapGestures(onPress = {
                                val gesture = latestHit?.invoke()?.let { ContinuousHitGesture(id, it) }
                                if (gesture != null) latestAction(ContinuousEditorAction.BeginHit(gesture))
                                latestAction(if (whileHeld) ContinuousEditorAction.HoldPad(id) else ContinuousEditorAction.TapPad(id))
                                var released = false
                                try { released = tryAwaitRelease() } finally {
                                    if (gesture != null) latestAction(ContinuousEditorAction.EndHit(gesture, cancelled = !released, songFrame = latestHit?.invoke()))
                                    if (whileHeld) latestAction(ContinuousEditorAction.ReleasePad(id))
                                }
                            })
                        } else Modifier.combinedClickable(interactionSource = interaction, indication = null,
                            onClick = { onAction(ContinuousEditorAction.SelectPad(id)); if (filled && state.permits(ContinuousCapability.PAD_AUDITION)) onAction(ContinuousEditorAction.TapPad(id)) },
                            onLongClick = if (filled && state.permits(ContinuousCapability.PAD_AUDITION)) ({ if (!held) { held = true; onAction(ContinuousEditorAction.HoldPad(id)) } }) else null))
                        .then(if (keyboardMode) Modifier.onKeyEvent { event ->
                            keyboardAllowed && keyboard.key(event)
                        }.focusable(enabled = keyboardAllowed) else Modifier)
                        .pointerInput(id, filled, state.permits(ContinuousCapability.PLACE_PAD), capture != null, hit != null, onPadDrag != null, state.noteRepeat) {
                            // A compact pane has no visible drop target: its vertical gestures belong to scrolling.
                            if (onPadDrag != null && onPadDrop != null && capture == null && hit == null && state.noteRepeat == ContinuousNoteRepeat.OFF && filled && state.permits(ContinuousCapability.PLACE_PAD)) {
                                var position = Offset.Zero
                                detectDragGestures(onDragStart = { at ->
                                    position = rootOrigin + at
                                    latestAction(ContinuousEditorAction.SelectPad(id))
                                    latestDrag?.invoke(CEPaddedDrag(id, position))
                                }, onDragEnd = { latestDrop?.invoke(id, position); latestDrag?.invoke(null) },
                                    onDragCancel = { latestDrag?.invoke(null) }) { change, _ ->
                                    change.consume()
                                    position = rootOrigin + change.position
                                    latestDrag?.invoke(CEPaddedDrag(id, position))
                                }
                            }
                        }
                        .semantics {
                            contentDescription = description; this.selected = selected
                            // A screen reader user cuts at the moment the PAD is activated.
                            if (capture != null) onClick(cutLabel) {
                                latestCapture?.invoke()?.let { latestAction(ContinuousEditorAction.CapturePad(id, it)) }; true
                            }
                            // A screen reader user plays and records the PAD at the moment it is activated.
                            if (hit != null && filled) onClick(if (state.noteRepeat == ContinuousNoteRepeat.OFF) playLabel else repeatLabel) {
                                latestHit?.invoke()?.let { frame ->
                                    latestAction(ContinuousEditorAction.BeginHit(ContinuousHitGesture(id, frame)))
                                    latestAction(ContinuousEditorAction.TapPad(id))
                                }; true
                            }
                            else if (capture == null && filled && state.noteRepeat != ContinuousNoteRepeat.OFF && state.permits(ContinuousCapability.PAD_AUDITION)) onClick(repeatLabel) {
                                latestAction(ContinuousEditorAction.TapPad(id)); true
                            }
                        }
                        .padding(if (font > 1.5f && side < 96.dp) 6.dp else 8.dp)) {
                        Text(cePadName(id), Modifier.align(Alignment.TopStart), color = if (filled) CEColor.Cream else CEColor.Tan,
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(name, Modifier.align(Alignment.BottomStart), color = if (filled) CEColor.Cream else CEColor.Tan,
                            fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }
        }
    }
}

internal fun cePadName(id: Int) = "${'A' + id / 16}${(id % 16 + 1).toString().padStart(2, '0')}"
/** Taps kept for a tap tempo: the last few, since a pause of two seconds or more starts over. */
internal fun ceTap(taps: List<Long>, nowMillis: Long): List<Long> =
    if (taps.isEmpty() || nowMillis - taps.last() >= 2_000) listOf(nowMillis) else (taps + nowMillis).takeLast(8)

/** The tempo of [taps] (monotonic milliseconds, oldest first) as whole BPM within 40–240; null before the second tap. */
internal fun ceTapBpm(taps: List<Long>): Int? {
    if (taps.size < 2) return null
    val interval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
    return if (interval <= 0) 240 else kotlin.math.round(60_000 / interval).toInt().coerceIn(40, 240)
}

/** Minutes, seconds and milliseconds, for trims a millisecond at a time. */
internal fun ceOriginalPosition(source: ContinuousSource?, frame: Long): String = if (source == null) "–" else
    "${ceTime(frame.coerceIn(0, source.frames), source.sampleRate, true)} / ${ceTime(source.frames, source.sampleRate, true)}"

internal fun ceTimeMillis(frame: Long, rate: Int): String {
    val millis = frame.coerceAtLeast(0) * 1000 / rate.coerceAtLeast(1)
    return "${millis / 60_000}:${(millis / 1000 % 60).toString().padStart(2, '0')}.${(millis % 1000).toString().padStart(3, '0')}"
}
internal fun ceTime(frame: Long, rate: Int = CONTINUOUS_TIMELINE_RATE, precise: Boolean = false): String {
    val centiseconds = frame.coerceAtLeast(0) * 100 / rate.coerceAtLeast(1)
    val seconds = centiseconds / 100
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" + if (precise) ".${(centiseconds % 100).toString().padStart(2, '0')}" else ""
}
