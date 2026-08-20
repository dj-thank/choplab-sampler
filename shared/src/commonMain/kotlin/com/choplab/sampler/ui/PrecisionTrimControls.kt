package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PadTrimPrecision
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.padTrimNudgeFrames
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PrecisionTrimControls(
    pad: PadModel,
    activeBoundary: PadTrimBoundary,
    precision: PadTrimPrecision,
    focusWindow: SliceRange,
    onBoundarySelected: (PadTrimBoundary) -> Unit,
    onPrecisionSelected: (PadTrimPrecision) -> Unit,
    onBoundaryTicks: (PadTrimBoundary, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val audio = pad.audio ?: return
    val stepFrames = padTrimNudgeFrames(audio.sampleRate, precision)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TrimBoundaryWheel(
                boundary = PadTrimBoundary.START,
                frame = pad.startFrame,
                minimumFrame = 0,
                maximumFrame = pad.endFrame - 2,
                sampleRate = audio.sampleRate,
                stepFrames = stepFrames,
                focusWindow = focusWindow,
                active = activeBoundary == PadTrimBoundary.START,
                onSelect = { onBoundarySelected(PadTrimBoundary.START) },
                onTicks = { onBoundaryTicks(PadTrimBoundary.START, it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            TrimBoundaryWheel(
                boundary = PadTrimBoundary.END,
                frame = pad.endFrame,
                minimumFrame = pad.startFrame + 2,
                maximumFrame = audio.frameCount,
                sampleRate = audio.sampleRate,
                stepFrames = stepFrames,
                focusWindow = focusWindow,
                active = activeBoundary == PadTrimBoundary.END,
                onSelect = { onBoundarySelected(PadTrimBoundary.END) },
                onTicks = { onBoundaryTicks(PadTrimBoundary.END, it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PadTrimPrecision.entries.forEach { option ->
                PrecisionChoice(
                    precision = option,
                    selected = precision == option,
                    onClick = { onPrecisionSelected(option) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun TrimBoundaryWheel(
    boundary: PadTrimBoundary,
    frame: Int,
    minimumFrame: Int,
    maximumFrame: Int,
    sampleRate: Int,
    stepFrames: Int,
    focusWindow: SliceRange,
    active: Boolean,
    onSelect: () -> Unit,
    onTicks: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pixelsPerTick = with(density) { 18.dp.toPx() }.coerceAtLeast(1f)
    var pendingPixels by remember(boundary, stepFrames) { mutableFloatStateOf(0f) }
    val scrollState = rememberScrollableState { delta ->
        pendingPixels += delta
        val ticks = (pendingPixels / pixelsPerTick).toInt()
        if (ticks != 0) {
            pendingPixels -= ticks * pixelsPerTick
            onTicks(-ticks)
        }
        delta
    }
    val previous = adjacentTrimFrame(frame, -stepFrames, minimumFrame, maximumFrame)
    val next = adjacentTrimFrame(frame, stepFrames, minimumFrame, maximumFrame)
    val boundaryLabel = trimBoundaryLabel(boundary)
    val shape = RoundedCornerShape(8.dp)
    val foreground = if (active) Color(0xFFFFE8B8) else DeckPanelDark

    Surface(
        color = DeckInk,
        contentColor = foreground,
        shape = shape,
        modifier = modifier
            .border(if (active) 3.dp else 1.5.dp, if (active) DeckLamp else Color.Black, shape)
            .clickable(role = Role.Button, onClick = onSelect)
            .scrollable(scrollState, Orientation.Vertical)
            .semantics {
                role = Role.Button
                contentDescription = "$boundaryLabel 数値ホイール。上下にスクロールして調整"
                stateDescription = "${formatPrecisionTrimTime(frame, sampleRate)}、${frame}フレーム"
                selected = active
                customActions = listOf(
                    CustomAccessibilityAction("少し前へ") {
                        if (frame <= minimumFrame) false else {
                            onSelect()
                            onTicks(-1)
                            true
                        }
                    },
                    CustomAccessibilityAction("少し後へ") {
                        if (frame >= maximumFrame) false else {
                            onSelect()
                            onTicks(1)
                            true
                        }
                    },
                )
            }
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = boundaryLabel,
                color = if (active) DeckLamp else DeckGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 8.sp,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(modifier = Modifier.size(54.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension * 0.43f
                    drawCircle(Color(0xFF11100C), radius, center)
                    drawCircle(
                        color = if (active) DeckLamp else Color(0xFF665B42),
                        radius = radius,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                    val angle = trimDialProgress(frame, focusWindow) * (2.0 * PI) - PI / 2.0
                    drawLine(
                        color = if (active) DeckLamp else DeckGreen,
                        start = center,
                        end = Offset(
                            center.x + cos(angle).toFloat() * radius * 0.78f,
                            center.y + sin(angle).toFloat() * radius * 0.78f,
                        ),
                        strokeWidth = 4f,
                    )
                    drawCircle(if (active) DeckLamp else DeckGreen, radius * 0.12f, center)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WheelNumber(formatPrecisionTrimTime(previous, sampleRate), emphasized = false)
                    WheelNumber(formatPrecisionTrimTime(frame, sampleRate), emphasized = true)
                    WheelNumber(formatPrecisionTrimTime(next, sampleRate), emphasized = false)
                }
            }
        }
    }
}

@Composable
private fun WheelNumber(text: String, emphasized: Boolean) {
    Text(
        text = text,
        color = if (emphasized) Color(0xFFFFE8B8) else Color(0xFF8F8260),
        fontFamily = FontFamily.Monospace,
        fontWeight = if (emphasized) FontWeight.Black else FontWeight.Normal,
        fontSize = if (emphasized) 12.sp else 7.sp,
        lineHeight = if (emphasized) 13.sp else 8.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun PrecisionChoice(
    precision: PadTrimPrecision,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        color = if (selected) DeckLamp else DeckPanelDark,
        contentColor = DeckInk,
        shape = shape,
        modifier = modifier
            .border(1.5.dp, DeckInk, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "トリム精度 ${trimPrecisionLabel(precision)}"
                this.selected = selected
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = trimPrecisionLabel(precision),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun trimBoundaryLabel(boundary: PadTrimBoundary): String = when (boundary) {
    PadTrimBoundary.START -> "ここから / START"
    PadTrimBoundary.END -> "ここまで / END"
}

fun trimPrecisionLabel(precision: PadTrimPrecision): String = when (precision) {
    PadTrimPrecision.FRAME -> "1 FRAME"
    PadTrimPrecision.MILLISECOND -> "1 ms"
    PadTrimPrecision.TEN_MILLISECONDS -> "10 ms"
}

fun formatPrecisionTrimTime(frame: Int, sampleRate: Int): String {
    val totalMillis = frame.coerceAtLeast(0).toLong() * 1_000L / sampleRate.coerceAtLeast(1)
    val minutes = totalMillis / 60_000L
    val seconds = totalMillis / 1_000L % 60L
    val millis = totalMillis % 1_000L
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

fun trimDialProgress(frame: Int, focusWindow: SliceRange): Float {
    if (focusWindow.endFrame <= focusWindow.startFrame) return 0f
    return ((frame - focusWindow.startFrame).toFloat() / focusWindow.length)
        .coerceIn(0f, 1f)
}

private fun adjacentTrimFrame(frame: Int, delta: Int, minimumFrame: Int, maximumFrame: Int): Int =
    (frame.toLong() + delta)
        .coerceIn(minimumFrame.toLong(), maximumFrame.toLong())
        .toInt()
