package com.choplab.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
) {
    require(pads.size == 16) { "PadGrid requires exactly 16 pads" }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) { column ->
                    val indexInGrid = row * 4 + column
                    val pad = pads[indexInGrid]
                    PadCell(
                        pad = pad,
                        keyLabel = PAD_KEYS[indexInGrid].toString(),
                        selected = pad.globalIndex == selectedPad,
                        captureMode = captureMode,
                        onTrigger = { onTrigger(pad.globalIndex) },
                        onRelease = { onRelease(pad.globalIndex) },
                        onSelect = { onSelect(pad.globalIndex) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PadCell(
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
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        pressed -> DeckPadLit
        pad.isAssigned -> DeckPadAssigned
        else -> DeckPad
    }
    val foreground = if (pressed) Color(0xFF2A1500) else if (pad.isAssigned) Color(0xFFE8D8A8) else Color(0xFF8A7C58)
    val description = buildString {
        append("PAD %02d".format(pad.indexInBank + 1))
        append(if (pad.isAssigned) " 割り当て済み" else " 空")
        if (captureMode) append("。押すと現在の再生位置を刻む")
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(background, shape)
            .border(if (selected) 3.dp else 2.dp, if (selected) DeckLamp else Color.Black, shape)
            .pointerInput(pad.globalIndex, pad.isAssigned, captureMode) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onSelect()
                        if (captureMode || pad.isAssigned) onTrigger()
                        tryAwaitRelease()
                        if (!captureMode && pad.isAssigned) onRelease()
                        pressed = false
                    },
                )
            }
            .semantics { contentDescription = description }
            .padding(6.dp),
    ) {
        Text(
            text = "%02d".format(pad.indexInBank + 1),
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Text(
            text = padTime(pad),
            color = if (pressed) Color(0xFF4A2600) else DeckGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Text(
            text = keyLabel,
            color = if (pressed) Color(0xFF5A3210) else Color(0xFF655838),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomCenter),
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
