package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SliceRange
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun WaveformEditor(
    audio: PcmAudio,
    rangeStartFrame: Int,
    rangeEndFrame: Int,
    sliceMarkers: List<Int>,
    activeSlice: SliceRange?,
    manualChopEnabled: Boolean,
    onRangeStartChange: (Int) -> Unit,
    onRangeEndChange: (Int) -> Unit,
    onSliceMarkerChange: (Int, Int) -> Unit,
    onWaveformTap: (Int) -> Unit,
    playheadFrame: Int? = null,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = 220.dp,
    fillCanvas: Boolean = false,
    showViewportControls: Boolean = true,
    compactViewportControls: Boolean = false,
    showTimeReadout: Boolean = true,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(audio.id) { mutableFloatStateOf(1f) }
    var scroll by remember(audio.id) { mutableFloatStateOf(0f) }

    val totalFrames = audio.frameCount.coerceAtLeast(1)
    val visibleFrames = (totalFrames / zoom).roundToInt().coerceIn(1, totalFrames)
    val maximumVisibleStart = (totalFrames - visibleFrames).coerceAtLeast(0)
    val visibleStart = (maximumVisibleStart * scroll).roundToInt().coerceIn(0, maximumVisibleStart)
    val visibleEnd = (visibleStart + visibleFrames).coerceAtMost(totalFrames)
    val widthPx = canvasSize.width.toFloat().coerceAtLeast(1f)

    val waveformColor = MaterialTheme.colorScheme.primary
    val zeroLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val markerColor = MaterialTheme.colorScheme.tertiary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    val activeSliceColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.23f)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    Column(modifier = modifier) {
        Box(
            modifier = (if (fillCanvas) Modifier.weight(1f) else Modifier.height(canvasHeight))
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it },
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(
                        audio.id,
                        manualChopEnabled,
                        visibleStart,
                        visibleFrames,
                        canvasSize,
                    ) {
                        detectTapGestures { offset ->
                            if (canvasSize.width <= 0) return@detectTapGestures
                            val fraction = (offset.x / canvasSize.width).coerceIn(0f, 1f)
                            val frame = (visibleStart + visibleFrames * fraction)
                                .roundToInt()
                                .coerceIn(0, totalFrames - 1)
                            onWaveformTap(frame)
                        }
                    },
            ) {
                drawRect(backgroundColor)
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1f,
                )

                drawFrameRegion(
                    startFrame = rangeStartFrame,
                    endFrame = rangeEndFrame,
                    visibleStart = visibleStart,
                    visibleFrames = visibleFrames,
                    color = selectionColor,
                )
                activeSlice?.let { range ->
                    drawFrameRegion(
                        startFrame = range.startFrame,
                        endFrame = range.endFrame,
                        visibleStart = visibleStart,
                        visibleFrames = visibleFrames,
                        color = activeSliceColor,
                    )
                }

                drawWaveform(
                    samples = audio.samples,
                    visibleStart = visibleStart,
                    visibleEnd = visibleEnd,
                    color = waveformColor,
                )

                sliceMarkers.forEach { marker ->
                    if (marker in visibleStart..visibleEnd) {
                        val x = frameToX(marker, visibleStart, visibleFrames, size.width)
                        drawLine(
                            color = markerColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2f,
                        )
                    }
                }

                playheadFrame
                    ?.takeIf { it in visibleStart..visibleEnd }
                    ?.let { frame ->
                        val x = frameToX(frame, visibleStart, visibleFrames, size.width)
                        drawLine(
                            color = Color.White.copy(alpha = 0.9f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 3f,
                        )
                    }
            }

            SelectionHandle(
                label = "S",
                frame = rangeStartFrame,
                visibleStart = visibleStart,
                visibleFrames = visibleFrames,
                canvasWidthPx = widthPx,
                color = MaterialTheme.colorScheme.secondary,
                onFrameChange = onRangeStartChange,
            )
            SelectionHandle(
                label = "E",
                frame = rangeEndFrame,
                visibleStart = visibleStart,
                visibleFrames = visibleFrames,
                canvasWidthPx = widthPx,
                color = MaterialTheme.colorScheme.tertiary,
                onFrameChange = onRangeEndChange,
            )

            sliceMarkers.forEachIndexed { index, marker ->
                SliceMarkerHandle(
                    markerNumber = index + 1,
                    frame = marker,
                    visibleStart = visibleStart,
                    visibleFrames = visibleFrames,
                    canvasWidthPx = widthPx,
                    color = markerColor,
                    onFrameChange = { changedFrame -> onSliceMarkerChange(index, changedFrame) },
                )
            }

            Text(
                text = if (manualChopEnabled) "波形をタップ: チョップ追加" else "波形をタップ: スライス選択",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                fontSize = 11.sp,
            )
        }

        if (showTimeReadout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(visibleStart, audio.sampleRate), fontSize = 11.sp)
                Text(
                    "選択 ${formatTime(rangeStartFrame, audio.sampleRate)} – ${formatTime(rangeEndFrame, audio.sampleRate)}",
                    fontSize = 11.sp,
                )
                Text(formatTime(visibleEnd, audio.sampleRate), fontSize = 11.sp)
            }
        }

        if (showViewportControls) {
            Text("ズーム ${"%.1f".format(zoom)}×", fontSize = 12.sp)
            Slider(
                value = zoom,
                onValueChange = {
                    zoom = it.coerceIn(1f, 32f)
                    if (zoom <= 1.01f) scroll = 0f
                },
                valueRange = 1f..32f,
            )
            Text("表示位置", fontSize = 12.sp)
            Slider(
                value = scroll,
                onValueChange = { scroll = it.coerceIn(0f, 1f) },
                valueRange = 0f..1f,
                enabled = zoom > 1.01f,
            )
        } else if (compactViewportControls) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ViewportControlButton(
                    label = "ZOOM -",
                    description = "波形を縮小",
                    enabled = zoom > 1.01f,
                    onClick = {
                        zoom = (zoom / 2f).coerceIn(1f, 32f)
                        if (zoom <= 1.01f) scroll = 0f
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${"%.1f".format(zoom)}x",
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .weight(0.7f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                        .padding(top = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ViewportControlButton(
                    label = "ZOOM +",
                    description = "波形を拡大",
                    enabled = zoom < 31.99f,
                    onClick = { zoom = (zoom * 2f).coerceIn(1f, 32f) },
                    modifier = Modifier.weight(1f),
                )
                ViewportControlButton(
                    label = "PREV",
                    description = "波形の前の位置を表示",
                    enabled = zoom > 1.01f && scroll > 0f,
                    onClick = { scroll = (scroll - 0.1f).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(0.8f),
                )
                ViewportControlButton(
                    label = "NEXT",
                    description = "波形の次の位置を表示",
                    enabled = zoom > 1.01f && scroll < 1f,
                    onClick = { scroll = (scroll + 0.1f).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(0.8f),
                )
            }
        }
    }
}

@Composable
private fun ViewportControlButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
        )
    }
}

@Composable
private fun SliceMarkerHandle(
    markerNumber: Int,
    frame: Int,
    visibleStart: Int,
    visibleFrames: Int,
    canvasWidthPx: Float,
    color: Color,
    onFrameChange: (Int) -> Unit,
) {
    if (canvasWidthPx <= 1f) return
    val x = frameToX(frame, visibleStart, visibleFrames, canvasWidthPx)
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 22.dp.toPx() }
    if (x < -handleWidthPx || x > canvasWidthPx + handleWidthPx) return

    var dragOriginFrame by remember { mutableIntStateOf(frame) }
    var accumulatedDragPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        accumulatedDragPx += delta
        val frameDelta = (accumulatedDragPx / canvasWidthPx * visibleFrames).roundToInt()
        onFrameChange(dragOriginFrame + frameDelta)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - handleWidthPx / 2f).roundToInt(), 0) }
            .width(22.dp)
            .fillMaxHeight()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    dragOriginFrame = frame
                    accumulatedDragPx = 0f
                },
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = 0.82f)),
        ) {}
        Text(
            text = markerNumber.toString(),
            color = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier
                .background(color, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun SelectionHandle(
    label: String,
    frame: Int,
    visibleStart: Int,
    visibleFrames: Int,
    canvasWidthPx: Float,
    color: Color,
    onFrameChange: (Int) -> Unit,
) {
    if (canvasWidthPx <= 1f) return
    val x = frameToX(frame, visibleStart, visibleFrames, canvasWidthPx)
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 26.dp.toPx() }
    if (x < -handleWidthPx || x > canvasWidthPx + handleWidthPx) return

    var dragOriginFrame by remember { mutableIntStateOf(frame) }
    var accumulatedDragPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        accumulatedDragPx += delta
        val frameDelta = (accumulatedDragPx / canvasWidthPx * visibleFrames).roundToInt()
        onFrameChange(dragOriginFrame + frameDelta)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - handleWidthPx / 2f).roundToInt(), 0) }
            .width(26.dp)
            .fillMaxHeight()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    dragOriginFrame = frame
                    accumulatedDragPx = 0f
                },
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = 0.92f)),
        ) {}
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .background(color, RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
        )
    }
}

private fun DrawScope.drawFrameRegion(
    startFrame: Int,
    endFrame: Int,
    visibleStart: Int,
    visibleFrames: Int,
    color: Color,
) {
    val left = frameToX(startFrame, visibleStart, visibleFrames, size.width).coerceIn(0f, size.width)
    val right = frameToX(endFrame, visibleStart, visibleFrames, size.width).coerceIn(0f, size.width)
    if (right > left) drawRect(color, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(right - left, size.height))
}

private fun DrawScope.drawWaveform(
    samples: ShortArray,
    visibleStart: Int,
    visibleEnd: Int,
    color: Color,
) {
    if (samples.isEmpty() || visibleEnd <= visibleStart) return
    val pixelWidth = size.width.roundToInt().coerceAtLeast(1)
    val frameSpan = visibleEnd - visibleStart
    val centerY = size.height / 2f
    val amplitude = size.height * 0.46f

    var x = 0
    while (x < pixelWidth) {
        val frameFrom = visibleStart + (frameSpan.toLong() * x / pixelWidth).toInt()
        val nextX = (x + 2).coerceAtMost(pixelWidth)
        val frameTo = visibleStart + (frameSpan.toLong() * nextX / pixelWidth).toInt()
        val safeFrom = frameFrom.coerceIn(0, samples.lastIndex)
        val safeTo = frameTo.coerceIn(safeFrom + 1, samples.size)
        val sampleStep = max(1, (safeTo - safeFrom) / 48)

        var minimum = 0f
        var maximum = 0f
        var frame = safeFrom
        while (frame < safeTo) {
            val value = samples[frame] / 32_768f
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            frame += sampleStep
        }

        drawLine(
            color = color,
            start = Offset(x.toFloat(), centerY - maximum * amplitude),
            end = Offset(x.toFloat(), centerY - minimum * amplitude),
            strokeWidth = 1.5f,
        )
        x += 2
    }
}

private fun frameToX(frame: Int, visibleStart: Int, visibleFrames: Int, width: Float): Float =
    ((frame - visibleStart).toFloat() / visibleFrames.coerceAtLeast(1) * width)

private fun formatTime(frame: Int, sampleRate: Int): String {
    val seconds = frame.toDouble() / sampleRate.coerceAtLeast(1)
    val minutes = (seconds / 60).toInt()
    val remainder = seconds - minutes * 60
    return "%d:%06.3f".format(minutes, remainder)
}
