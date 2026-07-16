package com.choplab.sampler.ui

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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode

@Composable
fun PadGrid(
    pads: List<PadModel>,
    selectedPad: Int,
    onTrigger: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(pads.size == 16) { "PadGrid requires exactly 16 pads" }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        for (row in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (column in 0 until 4) {
                    val pad = pads[row * 4 + column]
                    PadCell(
                        pad = pad,
                        selected = pad.globalIndex == selectedPad,
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
    selected: Boolean,
    onTrigger: () -> Unit,
    onRelease: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val assignedColor = MaterialTheme.colorScheme.primaryContainer
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    Surface(
        color = if (pad.isAssigned) assignedColor else emptyColor,
        contentColor = if (pad.isAssigned) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        tonalElevation = if (selected) 6.dp else 1.dp,
        modifier = modifier
            .aspectRatio(1.08f)
            .border(if (selected) 3.dp else 1.dp, borderColor, shape)
            .pointerInput(pad.globalIndex, pad.playMode, pad.isAssigned) {
                detectTapGestures(
                    onLongPress = { onSelect() },
                    onPress = {
                        if (!pad.isAssigned) {
                            onSelect()
                        } else {
                            onTrigger()
                            val released = tryAwaitRelease()
                            if (pad.playMode == PadPlayMode.GATE || !released) onRelease()
                        }
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(7.dp),
        ) {
            Text(
                text = "%02d".format(pad.indexInBank + 1),
                modifier = Modifier.align(Alignment.TopStart),
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
            Text(
                text = if (pad.isAssigned) {
                    pad.audio?.name ?: "SAMPLE"
                } else {
                    "EMPTY\n長押しで選択"
                },
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
            if (pad.isAssigned) {
                Text(
                    text = buildString {
                        append(if (pad.reverse) "REV " else "")
                        append(if (pad.playMode == PadPlayMode.GATE) "GATE" else "ONE")
                        if (pad.pitchSemitones != 0f) append(" ${pad.pitchSemitones.toInt()}st")
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
