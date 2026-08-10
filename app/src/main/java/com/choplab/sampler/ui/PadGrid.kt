package com.choplab.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel

private const val PAD_KEYS = "1234QWERASDFZXCV"

@Composable
fun PadGrid(
    pads: List<PadModel>,
    selectedPad: Int,
    onTrigger: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    captureMode: Boolean = false,
    gap: Dp = 6.dp,
    columns: Int = 4,
) {
    require(pads.size == 16) { "PadGrid requires exactly 16 pads" }
    require(columns in 1..pads.size) { "PadGrid columns must be between 1 and 16" }
    require(pads.size % columns == 0) { "PadGrid columns must divide 16 pads" }
    val rows = pads.size / columns
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(columns) { column ->
                    val indexInGrid = row * columns + column
                    val pad = pads[indexInGrid]
                    PerformancePad(
                        pad = pad,
                        keyLabel = PAD_KEYS[indexInGrid].toString(),
                        selected = pad.globalIndex == selectedPad,
                        captureMode = captureMode,
                        onTrigger = { onTrigger(pad.globalIndex) },
                        onRelease = { onRelease(pad.globalIndex) },
                        onSelect = { onSelect(pad.globalIndex) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
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
    onTrigger: () -> Unit,
    onRelease: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember(pad.globalIndex) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(9.dp)
    val background = when {
        pressed -> DeckPadLit
        pad.isAssigned -> DeckPadAssigned
        else -> DeckPad
    }
    val foreground = when {
        pressed -> Color(0xFF2A1500)
        pad.isAssigned -> Color(0xFFF1DFAD)
        else -> Color(0xFF91825C)
    }
    val description = buildString {
        append("PAD %02d".format(pad.indexInBank + 1))
        append(if (pad.isAssigned) " 割り当て済み" else " 空")
        if (captureMode) append("。現在位置をチョップ")
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = if (selected) 3.dp else 1.5.dp,
                color = if (selected) DeckLamp else Color.Black,
                shape = shape,
            )
            .pointerInput(pad.globalIndex, pad.isAssigned, captureMode) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect()
                            if (captureMode || pad.isAssigned) onTrigger()
                            tryAwaitRelease()
                        } finally {
                            if (!captureMode && pad.isAssigned) onRelease()
                            pressed = false
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = "PADを実行") {
                    onSelect()
                    if (captureMode || pad.isAssigned) onTrigger()
                    true
                }
            }
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        val compact = maxHeight < 54.dp || maxWidth < 54.dp
        Text(
            text = "%02d".format(pad.indexInBank + 1),
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = if (compact) 11.sp else 15.sp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (!compact) {
            Text(
                text = padTime(pad),
                color = if (pressed) Color(0xFF4A2600) else DeckGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            text = keyLabel,
            color = if (pressed) Color(0xFF5A3210) else Color(0xFF756743),
            fontFamily = FontFamily.Monospace,
            fontSize = if (compact) 7.sp else 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

private fun padTime(pad: PadModel): String {
    val audio = pad.audio ?: return ""
    if (!pad.isAssigned || audio.sampleRate <= 0) return ""
    val seconds = pad.startFrame.toDouble() / audio.sampleRate
    val minutes = (seconds / 60.0).toInt()
    val remainder = seconds - minutes * 60.0
    return "%d:%04.1f".format(minutes, remainder)
}
