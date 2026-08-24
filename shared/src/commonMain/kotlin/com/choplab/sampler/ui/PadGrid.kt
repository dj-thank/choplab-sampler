package com.choplab.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.bankRoleFor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val PAD_KEYS = "1234QWERASDFZXCV"
private const val SCROLL_GATE_ACTIVATION_DELAY_MILLIS = 120L
private const val SCROLL_GATE_MINIMUM_PREVIEW_MILLIS = 80L

@Composable
fun PadGrid(
    pads: List<PadModel>,
    selectedPad: Int,
    onTrigger: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onLongPress: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    captureMode: Boolean = false,
    sourcePhase: SourceUiPhase = SourceUiPhase.STOPPED,
    deferPadActionUntilTap: Boolean = false,
    gap: Dp = 6.dp,
    columns: Int = 4,
) {
    require(pads.size == SamplerConfig.PAD_PAGE_SIZE) {
        "PadGrid requires exactly ${SamplerConfig.PAD_PAGE_SIZE} pads"
    }
    require(columns in 1..pads.size) {
        "PadGrid columns must be between 1 and ${SamplerConfig.PAD_PAGE_SIZE}"
    }
    require(pads.size % columns == 0) {
        "PadGrid columns must divide ${SamplerConfig.PAD_PAGE_SIZE} pads"
    }
    val rows = pads.size / columns
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val geometry = resolveSquarePadGrid(
            width = maxWidth.value,
            height = maxHeight.value,
            gap = gap.value,
            columns = columns,
        )
        Column(
            modifier = Modifier.size(geometry.contentWidth.dp, geometry.contentHeight.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(columns) { column ->
                        val indexInGrid = row * columns + column
                        val pad = pads[indexInGrid]
                        PerformancePad(
                            pad = pad,
                            keyLabel = PAD_KEYS[indexInGrid].toString(),
                            selected = pad.globalIndex == selectedPad,
                            captureMode = captureMode,
                            sourcePhase = sourcePhase,
                            deferPadActionUntilTap = deferPadActionUntilTap,
                            onTrigger = { onTrigger(pad.globalIndex) },
                            onRelease = { onRelease(pad.globalIndex) },
                            onSelect = { onSelect(pad.globalIndex) },
                            onLongPress = { onLongPress(pad.globalIndex) },
                            modifier = Modifier.size(geometry.cellSize.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerformancePad(
    pad: PadModel,
    keyLabel: String,
    selected: Boolean,
    captureMode: Boolean,
    sourcePhase: SourceUiPhase,
    deferPadActionUntilTap: Boolean,
    onTrigger: () -> Unit,
    onRelease: () -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember(pad.globalIndex) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(9.dp)
    val bankRole = bankRoleFor(pad.bankIndex)
    val accent = bankRoleAccent(pad.bankIndex)
    val background = when {
        pressed -> accent
        pad.isAssigned -> accent.copy(alpha = 0.72f)
        else -> DeckPad
    }
    val foreground = when {
        pressed -> Color(0xFF2A1500)
        pad.isAssigned -> Color(0xFFF1DFAD)
        else -> Color(0xFF91825C)
    }
    val description = padAccessibilityDescription(pad, captureMode, sourcePhase)
    val deferDestructiveCapture = shouldDeferDestructiveCaptureUntilTap(
        assigned = pad.isAssigned,
        captureMode = captureMode,
        sourcePhase = sourcePhase,
    )
    val deferGatePerformance = deferPadActionUntilTap &&
        pad.isAssigned &&
        pad.playMode == PadPlayMode.GATE &&
        !captureMode

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = if (selected) 3.dp else 1.5.dp,
                color = if (selected) DeckLamp else Color.Black,
                shape = shape,
            )
            .pointerInput(
                pad.globalIndex,
                pad.isAssigned,
                pad.playMode,
                captureMode,
                sourcePhase,
                deferPadActionUntilTap,
            ) {
                detectTapGestures(
                    onTap = {
                        if (deferPadActionUntilTap && !deferGatePerformance) {
                            // A parent-consumed drag cancels before onTap, so scrolling has no PAD side effects.
                            onSelect()
                            if (captureMode || pad.isAssigned) {
                                onTrigger()
                                if (pad.isAssigned) onRelease()
                            }
                        } else if (deferDestructiveCapture) {
                            onTrigger()
                        }
                    },
                    onLongPress = if (pad.isAssigned) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect()
                            onLongPress()
                        }
                    } else {
                        // Empty CHOP pads have no trim target. Leaving this callback null lets a
                        // stationary hold complete through onTap instead of being swallowed.
                        null
                    },
                    onPress = {
                        pressed = true
                        try {
                            if (deferGatePerformance) {
                                var gateTriggered = false
                                try {
                                    coroutineScope {
                                        val delayedStart = launch {
                                            // Let the scroll parent consume a drag before committing audio.
                                            delay(SCROLL_GATE_ACTIVATION_DELAY_MILLIS)
                                            onSelect()
                                            onTrigger()
                                            gateTriggered = true
                                        }
                                        val released = tryAwaitRelease()
                                        delayedStart.cancelAndJoin()
                                        if (!gateTriggered && released) {
                                            // A completed short tap gets an audible preview instead of
                                            // same-frame trigger/release on both audio backends.
                                            onSelect()
                                            onTrigger()
                                            gateTriggered = true
                                            delay(SCROLL_GATE_MINIMUM_PREVIEW_MILLIS)
                                        }
                                    }
                                } finally {
                                    // Opening trim replaces this pointerInput node. Once trigger ownership
                                    // was acquired, cancellation must still release the GATE exactly once.
                                    if (gateTriggered) onRelease()
                                }
                            } else {
                                if (!deferPadActionUntilTap) {
                                    onSelect()
                                    if (!deferDestructiveCapture && (captureMode || pad.isAssigned)) {
                                        onTrigger()
                                    }
                                }
                                tryAwaitRelease()
                            }
                        } finally {
                            if (!deferPadActionUntilTap && pad.isAssigned) onRelease()
                            pressed = false
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                this.selected = selected
                onClick(label = "PADを実行") {
                    onSelect()
                    if (captureMode || pad.isAssigned) onTrigger()
                    true
                }
                if (pad.isAssigned) {
                    onLongClick(label = "切り位置を微調整") {
                        onSelect()
                        onLongPress()
                        true
                    }
                }
            }
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        val compact = maxHeight < 54.dp || maxWidth < 54.dp
        Text(
            text = "${bankRole.letter}%02d".format(pad.indexInBank + 1),
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = if (compact) 11.sp else 15.sp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (!compact) {
            Text(
                text = padCenterLabel(pad).ifEmpty { "EMPTY" },
                color = if (pressed) Color(0xFF241400) else Color(0xFFFFE8B8),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 6.dp),
            )
            if (pad.isAssigned) {
                val peaks = remember(pad.audio?.id, pad.startFrame, pad.endFrame) {
                    buildPadMiniPeaks(pad)
                }
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.72f)
                        .height(10.dp),
                ) {
                    if (peaks.isNotEmpty()) {
                        val barWidth = size.width / (peaks.size * 1.65f)
                        val spacing = (size.width - peaks.size * barWidth) /
                            (peaks.size - 1).coerceAtLeast(1)
                        peaks.forEachIndexed { index, amount ->
                            val barHeight = (size.height * amount).coerceAtLeast(1f)
                            drawRoundRect(
                                color = Color(0xFFFFE8B8).copy(alpha = 0.72f),
                                topLeft = Offset(index * (barWidth + spacing), (size.height - barHeight) / 2f),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = when {
                pad.playMode == PadPlayMode.LOOP -> "LOOP"
                pad.contentKind == PadContentKind.DRUM -> "DRM"
                pad.contentKind == PadContentKind.VOCAL -> "VOX"
                else -> keyLabel
            },
            color = if (pressed) Color(0xFF5A3210) else Color(0xFF756743),
            fontFamily = FontFamily.Monospace,
            fontSize = if (compact) 7.sp else 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

fun padAccessibilityDescription(
    pad: PadModel,
    captureMode: Boolean,
    sourcePhase: SourceUiPhase = SourceUiPhase.STOPPED,
): String = buildString {
    append("PAD %02d".format(pad.indexInBank + 1))
    append(if (pad.isAssigned) " 割り当て済み" else " 空")
    if (pad.isAssigned && captureMode && sourcePhase == SourceUiPhase.PLAYING) {
        append("。タップで現在位置を上書き。長押しで微調整")
    } else if (pad.isAssigned) {
        append("。タップで試聴。長押しで微調整")
    } else if (sourcePhase == SourceUiPhase.STARTING) {
        append("。再生準備中。音が鳴るまで選択のみ")
    } else if (sourcePhase == SourceUiPhase.STOPPING) {
        append("。停止処理中。選択のみ")
    } else if (captureMode) {
        append("。現在位置をチョップ")
    }
}

fun shouldDeferDestructiveCaptureUntilTap(
    assigned: Boolean,
    captureMode: Boolean,
    sourcePhase: SourceUiPhase,
): Boolean = assigned && captureMode && sourcePhase == SourceUiPhase.PLAYING

private fun buildPadMiniPeaks(pad: PadModel): FloatArray {
    val audio = pad.audio ?: return FloatArray(0)
    if (!pad.isAssigned || audio.samples.isEmpty()) return FloatArray(0)
    val start = pad.startFrame.coerceIn(0, audio.samples.lastIndex)
    val end = pad.endFrame.coerceIn(start + 1, audio.samples.size)
    val bucketCount = 9
    val bucketSize = max(1, (end - start) / bucketCount)
    val peaks = FloatArray(bucketCount) { bucket ->
        val from = (start + bucket * bucketSize).coerceAtMost(end - 1)
        val to = (from + bucketSize).coerceAtMost(end)
        var peak = 0f
        var frame = from
        val stride = max(1, (to - from) / 24)
        while (frame < to) {
            peak = max(peak, abs(audio.samples[frame] / 32_768f))
            frame += stride
        }
        peak
    }
    val strongest = peaks.maxOrNull()?.coerceAtLeast(0.08f) ?: 1f
    return FloatArray(bucketCount) { index -> (peaks[index] / strongest).coerceIn(0.12f, 1f) }
}

private fun padCenterLabel(pad: PadModel): String {
    val audio = pad.audio ?: return ""
    if (!pad.isAssigned || audio.sampleRate <= 0) return ""
    if (pad.contentKind == PadContentKind.VOCAL) return "VOX"
    if (pad.contentKind == PadContentKind.DRUM) {
        val name = audio.name.uppercase()
        return when {
            "KICK" in name -> "KICK"
            "SNARE" in name -> "SNARE"
            "CLOSED HAT" in name -> "C.HAT"
            "OPEN HAT" in name -> "O.HAT"
            "CLAP" in name -> "CLAP"
            "SHAKER" in name -> "SHAKE"
            "RIM" in name -> "RIM"
            else -> "PERC"
        }
    }
    val seconds = pad.startFrame.toDouble() / audio.sampleRate
    val minutes = (seconds / 60.0).toInt()
    val remainder = seconds - minutes * 60.0
    return "%d:%04.1f".format(minutes, remainder)
}
