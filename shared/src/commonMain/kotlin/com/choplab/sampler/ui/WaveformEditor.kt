package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SliceRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.PI

/**
 * Keeps a single vertical drag unconsumed so an enclosing CHOP scroller can own it. Waveform
 * transforms begin only after horizontal single-pointer intent or multi-pointer transform slop.
 */
private suspend fun PointerInputScope.detectWaveformTransformGestures(
    allowVerticalDragPassThrough: Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var cumulativeZoom = 1f
        var cumulativePan = Offset.Zero
        var cumulativeRotation = 0f
        var transformClaimed = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val rotationChange = event.calculateRotation()

                if (!transformClaimed) {
                    cumulativeZoom *= zoomChange
                    cumulativePan += panChange
                    cumulativeRotation += rotationChange
                    val multiPointer = event.changes.count { it.pressed } >= 2
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion =
                        abs(1f - cumulativeZoom) * centroidSize
                    val rotationMotion =
                        abs(cumulativeRotation * PI.toFloat() * centroidSize / 180f)
                    val horizontalMotion = abs(cumulativePan.x)
                    val verticalMotion = abs(cumulativePan.y)

                    transformClaimed = if (multiPointer) {
                        zoomMotion > touchSlop ||
                            rotationMotion > touchSlop ||
                            cumulativePan.getDistance() > touchSlop
                    } else {
                        horizontalMotion > touchSlop && horizontalMotion > verticalMotion
                    }

                    if (
                        !multiPointer &&
                        verticalMotion > touchSlop &&
                        verticalMotion >= horizontalMotion
                    ) {
                        if (allowVerticalDragPassThrough) {
                            // Stop observing this gesture without consuming its movement. The
                            // large-text CHOP verticalScroll sees this Main-pass change and claims it.
                            return@awaitEachGesture
                        }
                        // With no scroll ancestor, claim the vertical drag as a no-op transform.
                        // Consuming its movement cancels the sibling tap detector, preventing a
                        // drag in normal CHOP or PAD trim from seeking or moving a boundary on up.
                        transformClaimed = true
                    }
                }

                if (transformClaimed) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (
                        centroid != Offset.Unspecified &&
                        (zoomChange != 1f || panChange != Offset.Zero)
                    ) {
                        onGesture(centroid, panChange, zoomChange)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

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
    onWaveformLongPress: ((Int) -> Unit)? = null,
    longPressFocusFrames: Int? = null,
    initialFocusFrame: Int? = null,
    initialVisibleFrames: Int? = null,
    playheadFrame: Int? = null,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = 220.dp,
    fillCanvas: Boolean = false,
    showViewportControls: Boolean = true,
    compactViewportControls: Boolean = false,
    showTimeReadout: Boolean = true,
    showInteractionHint: Boolean = true,
    maximumZoom: Float = 32f,
    zoomFocusFrame: Int? = null,
    viewportResetKey: Any? = null,
    allowVerticalDragPassThrough: Boolean = false,
    onViewportChanged: ((WaveformViewport) -> Unit)? = null,
    readoutColor: Color? = null,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val haptics = LocalHapticFeedback.current
    val initialViewport = remember(
        audio.id,
        viewportResetKey,
        initialFocusFrame,
        initialVisibleFrames,
        maximumZoom,
    ) {
        initialWaveformViewport(
            totalFrames = audio.frameCount,
            focusFrame = initialFocusFrame,
            targetVisibleFrames = initialVisibleFrames,
            maximumZoom = maximumZoom,
        )
    }
    var zoom by remember(
        audio.id,
        viewportResetKey,
        initialFocusFrame,
        initialVisibleFrames,
        maximumZoom,
    ) { mutableFloatStateOf(initialViewport.zoom) }
    var scroll by remember(
        audio.id,
        viewportResetKey,
        initialFocusFrame,
        initialVisibleFrames,
        maximumZoom,
    ) { mutableFloatStateOf(initialViewport.scroll) }

    val viewport = resolveWaveformViewport(audio.frameCount, zoom, scroll)
    LaunchedEffect(viewport) {
        onViewportChanged?.invoke(viewport)
    }
    val totalFrames = viewport.totalFrames
    val visibleFrames = viewport.visibleFrames
    val visibleStart = viewport.visibleStart
    val visibleEnd = (visibleStart + visibleFrames).coerceAtMost(totalFrames)
    val widthPx = canvasSize.width.toFloat().coerceAtLeast(1f)
    val waveformEnvelope = remember(audio.id, audio.channelCount, visibleStart, visibleEnd, canvasSize.width) {
        buildWaveformEnvelope(
            samples = audio.samples,
            visibleStart = visibleStart,
            visibleEnd = visibleEnd,
            pixelWidth = canvasSize.width,
            channelCount = audio.channelCount,
        )
    }

    val waveformColor = MaterialTheme.colorScheme.primary
    val zeroLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val markerColor = MaterialTheme.colorScheme.tertiary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    val activeSliceColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.23f)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val resolvedReadoutColor = readoutColor ?: MaterialTheme.colorScheme.onSurface
    val accessibilityTapActionLabel = if (manualChopEnabled) {
        "表示範囲の中央にチョップを追加"
    } else {
        "表示範囲の中央へ移動"
    }

    Column(modifier = modifier) {
        Box(
            modifier = (if (fillCanvas) Modifier.weight(1f) else Modifier.height(canvasHeight))
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it }
                .semantics { isTraversalGroup = true },
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
                        longPressFocusFrames,
                    ) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (canvasSize.width <= 0) return@detectTapGestures
                                val frame = waveformFrameAtX(
                                    offset.x,
                                    canvasSize.width.toFloat(),
                                    visibleStart,
                                    visibleFrames,
                                    totalFrames,
                                )
                                val next = zoomViewportAtFocus(frame, totalFrames, zoom, 2f, maximumZoom)
                                scroll = next.scroll
                                zoom = next.zoom
                            },
                            onTap = { offset ->
                                if (canvasSize.width <= 0) return@detectTapGestures
                                val fraction = (offset.x / canvasSize.width).coerceIn(0f, 1f)
                                val frame = (visibleStart + visibleFrames * fraction)
                                    .roundToInt()
                                    .coerceIn(0, totalFrames - 1)
                                onWaveformTap(frame)
                            },
                            onLongPress = if (onWaveformLongPress != null || longPressFocusFrames != null) {
                                { offset ->
                                    if (canvasSize.width > 0) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val frame = waveformFrameAtX(
                                            offset.x,
                                            canvasSize.width.toFloat(),
                                            visibleStart,
                                            visibleFrames,
                                            totalFrames,
                                        )
                                        longPressFocusFrames?.let { requestedFrames ->
                                            val next = focusWaveformViewport(
                                                frame = frame,
                                                totalFrames = totalFrames,
                                                targetVisibleFrames = requestedFrames,
                                            )
                                            zoom = next.zoom
                                            scroll = next.scroll
                                        }
                                        onWaveformLongPress?.invoke(frame)
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    .pointerInput(audio.id, visibleStart, visibleFrames, canvasSize, maximumZoom) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll || canvasSize.width <= 0) continue
                                val change = event.changes.firstOrNull() ?: continue
                                val wheel = resolveWaveformWheelGesture(change.scrollDelta.x, change.scrollDelta.y)
                                val focusFrame = waveformFrameAtX(
                                    change.position.x,
                                    canvasSize.width.toFloat(),
                                    visibleStart,
                                    visibleFrames,
                                    totalFrames,
                                )
                                val next = zoomViewportAtFocus(focusFrame, totalFrames, zoom, wheel.zoomChange, maximumZoom)
                                val panned = panWaveformViewport(
                                    totalFrames = totalFrames,
                                    zoom = next.zoom,
                                    scroll = next.scroll,
                                    fraction = wheel.panFraction,
                                )
                                scroll = panned.scroll
                                zoom = panned.zoom
                                change.consume()
                            }
                        }
                    }
                    .pointerInput(
                        audio.id,
                        visibleStart,
                        visibleFrames,
                        allowVerticalDragPassThrough,
                    ) {
                        detectWaveformTransformGestures(
                            allowVerticalDragPassThrough = allowVerticalDragPassThrough,
                        ) { centroid, pan, zoomChange ->
                            if (canvasSize.width <= 0) return@detectWaveformTransformGestures
                            val focusFrame = waveformFrameAtX(
                                centroid.x,
                                canvasSize.width.toFloat(),
                                visibleStart,
                                visibleFrames,
                                totalFrames,
                            )
                            val next = zoomViewportAtFocus(focusFrame, totalFrames, zoom, zoomChange, maximumZoom)
                            val panned = panWaveformViewport(
                                totalFrames = totalFrames,
                                zoom = next.zoom,
                                scroll = next.scroll,
                                fraction = -pan.x / canvasSize.width,
                            )
                            scroll = panned.scroll
                            zoom = panned.zoom
                        }
                    }
                    .semantics {
                        contentDescription = if (manualChopEnabled) {
                            "音声波形。タッチ操作はタップした位置にチョップを追加。アクセシビリティ操作は$accessibilityTapActionLabel"
                        } else if (onWaveformLongPress != null) {
                            "音声波形。タップで近い境界を移動。長押しでその位置へ移動して1秒以内に拡大。アクセシビリティ操作は$accessibilityTapActionLabel"
                        } else {
                            "音声波形。タッチ操作はタップした位置へ移動。アクセシビリティ操作は$accessibilityTapActionLabel"
                        }
                        stateDescription = waveformViewportStateDescription(viewport)
                        role = Role.Button
                        onClick(
                            label = accessibilityTapActionLabel,
                        ) {
                            val centerFrame = (visibleStart + visibleFrames / 2f)
                                .roundToInt()
                                .coerceIn(0, totalFrames - 1)
                            onWaveformTap(centerFrame)
                            true
                        }
                        customActions = waveformViewportAccessibilityActions(
                            onPrevious = {
                                val previousScroll = scroll
                                scroll = panWaveformViewport(totalFrames, zoom, scroll, -0.5f).scroll
                                scroll != previousScroll
                            },
                            onNext = {
                                val previousScroll = scroll
                                scroll = panWaveformViewport(totalFrames, zoom, scroll, 0.5f).scroll
                                scroll != previousScroll
                            },
                            onReset = {
                                val changed = zoom != 1f || scroll != 0f
                                zoom = 1f
                                scroll = 0f
                                changed
                            },
                        )
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

                drawWaveformEnvelope(
                    envelope = waveformEnvelope,
                    color = waveformColor,
                )

                drawViewportOverview(
                    visibleStart = visibleStart,
                    visibleFrames = visibleFrames,
                    totalFrames = totalFrames,
                    trackColor = zeroLineColor,
                    viewportColor = markerColor,
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
                minimumFrame = 0,
                maximumFrame = (rangeEndFrame - 1).coerceAtLeast(0),
                color = MaterialTheme.colorScheme.secondary,
                onFrameChange = onRangeStartChange,
            )
            SelectionHandle(
                label = "E",
                frame = rangeEndFrame,
                visibleStart = visibleStart,
                visibleFrames = visibleFrames,
                canvasWidthPx = widthPx,
                minimumFrame = (rangeStartFrame + 1).coerceAtMost(totalFrames),
                maximumFrame = totalFrames,
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
                    minimumFrame = 1,
                    maximumFrame = (totalFrames - 1).coerceAtLeast(1),
                    color = markerColor,
                    onFrameChange = { changedFrame -> onSliceMarkerChange(index, changedFrame) },
                )
            }

            if (showInteractionHint) {
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
        }

        if (showTimeReadout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(visibleStart, audio.sampleRate), color = resolvedReadoutColor, fontSize = 11.sp)
                Text(
                    "選択 ${formatTime(rangeStartFrame, audio.sampleRate)} – ${formatTime(rangeEndFrame, audio.sampleRate)}",
                    color = resolvedReadoutColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                Text(formatTime(visibleEnd, audio.sampleRate), color = resolvedReadoutColor, fontSize = 11.sp)
            }
        }

        if (showViewportControls) {
            Text("ズーム ${"%.1f".format(zoom)}×", color = resolvedReadoutColor, fontSize = 12.sp)
            Slider(
                value = zoom,
                onValueChange = {
                    val nextZoom = it.coerceIn(1f, maximumZoom.coerceAtLeast(1f))
                    val focusFrame = zoomFocusFrame ?: visibleStart + visibleFrames / 2
                    scroll = centeredViewportScroll(focusFrame, totalFrames, nextZoom)
                    zoom = nextZoom
                },
                valueRange = 1f..maximumZoom.coerceAtLeast(1f),
            )
            Text("表示位置", color = resolvedReadoutColor, fontSize = 12.sp)
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
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ViewportControlButton(
                    label = "ZOOM -",
                    description = "波形を縮小",
                    enabled = zoom > 1.01f,
                    onClick = {
                        val nextZoom = (zoom / 2f).coerceIn(1f, maximumZoom.coerceAtLeast(1f))
                        val focusFrame = zoomFocusFrame ?: visibleStart + visibleFrames / 2
                        scroll = centeredViewportScroll(focusFrame, totalFrames, nextZoom)
                        zoom = nextZoom
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
                    enabled = zoom < maximumZoom.coerceAtLeast(1f) - 0.01f,
                    onClick = {
                        val nextZoom = (zoom * 2f).coerceIn(1f, maximumZoom.coerceAtLeast(1f))
                        val focusFrame = zoomFocusFrame ?: visibleStart + visibleFrames / 2
                        scroll = centeredViewportScroll(focusFrame, totalFrames, nextZoom)
                        zoom = nextZoom
                    },
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

/** One mouse-wheel notch resolved into a zoom multiplier around the cursor and a viewport pan fraction. */
data class WaveformWheelGesture(
    val zoomChange: Float,
    val panFraction: Float,
)

/** Zoom applied per vertical wheel notch; horizontal notches (or shift-wheel) pan by a fifth of the view. */
const val WAVEFORM_WHEEL_ZOOM_STEP = 1.25f
const val WAVEFORM_WHEEL_PAN_FRACTION = 0.2f

fun resolveWaveformWheelGesture(deltaX: Float, deltaY: Float): WaveformWheelGesture {
    val horizontal = deltaX.takeIf(Float::isFinite) ?: 0f
    val vertical = deltaY.takeIf(Float::isFinite) ?: 0f
    if (horizontal != 0f && abs(horizontal) >= abs(vertical)) {
        return WaveformWheelGesture(
            zoomChange = 1f,
            panFraction = if (horizontal > 0f) WAVEFORM_WHEEL_PAN_FRACTION else -WAVEFORM_WHEEL_PAN_FRACTION,
        )
    }
    return WaveformWheelGesture(
        zoomChange = when {
            vertical < 0f -> WAVEFORM_WHEEL_ZOOM_STEP
            vertical > 0f -> 1f / WAVEFORM_WHEEL_ZOOM_STEP
            else -> 1f
        },
        panFraction = 0f,
    )
}

fun initialWaveformViewport(
    totalFrames: Int,
    focusFrame: Int?,
    targetVisibleFrames: Int?,
    maximumZoom: Float,
): WaveformViewport {
    if (focusFrame == null || targetVisibleFrames == null || targetVisibleFrames <= 0) {
        return resolveWaveformViewport(totalFrames, zoom = 1f, scroll = 0f)
    }
    val requested = focusWaveformViewport(focusFrame, totalFrames, targetVisibleFrames)
    val safeMaximum = maximumZoom.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 1f
    val cappedZoom = requested.zoom.coerceAtMost(safeMaximum)
    return zoomViewportAtFocus(
        frame = focusFrame,
        totalFrames = totalFrames,
        zoom = 1f,
        zoomChange = cappedZoom,
        maximumZoom = safeMaximum,
    )
}

fun centeredViewportScroll(frame: Int, totalFrames: Int, zoom: Float): Float {
    val safeTotalFrames = totalFrames.coerceAtLeast(1)
    val safeZoom = zoom.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 1f
    val visibleFrames = (safeTotalFrames / safeZoom).roundToInt().coerceIn(1, safeTotalFrames)
    val maximumVisibleStart = safeTotalFrames - visibleFrames
    if (maximumVisibleStart <= 0) return 0f
    val centeredStart = (frame.coerceIn(0, safeTotalFrames - 1) - visibleFrames / 2)
        .coerceIn(0, maximumVisibleStart)
    return centeredStart.toFloat() / maximumVisibleStart
}

fun waveformViewportAccessibilityActions(
    onPrevious: () -> Boolean,
    onNext: () -> Boolean,
    onReset: () -> Boolean,
): List<CustomAccessibilityAction> = listOf(
    CustomAccessibilityAction("前の範囲を表示", onPrevious),
    CustomAccessibilityAction("次の範囲を表示", onNext),
    CustomAccessibilityAction("全体表示に戻す", onReset),
)

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
private fun BoxScope.SliceMarkerHandle(
    markerNumber: Int,
    frame: Int,
    visibleStart: Int,
    visibleFrames: Int,
    canvasWidthPx: Float,
    minimumFrame: Int,
    maximumFrame: Int,
    color: Color,
    onFrameChange: (Int) -> Unit,
) {
    if (canvasWidthPx <= 1f) return
    val x = frameToX(frame, visibleStart, visibleFrames, canvasWidthPx)
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 48.dp.toPx() }
    if (x < -handleWidthPx || x > canvasWidthPx + handleWidthPx) return
    val handleLeftPx = (x - handleWidthPx / 2f)
        .coerceIn(0f, (canvasWidthPx - handleWidthPx).coerceAtLeast(0f))
    val laneRepeat = (markerNumber - 1) / 3
    val laneNudgePx = with(density) { laneRepeat.dp.toPx() }
    val touchNudgePx = if (handleLeftPx <= canvasWidthPx / 2f) laneNudgePx else -laneNudgePx

    var dragOriginFrame by remember { mutableIntStateOf(frame) }
    var accumulatedDragPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        accumulatedDragPx += delta
        val frameDelta = (accumulatedDragPx / canvasWidthPx * visibleFrames).roundToInt()
        onFrameChange(dragOriginFrame + frameDelta)
    }
    val accessibilityNudgeFrames = (visibleFrames / 100).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .offset { IntOffset(handleLeftPx.roundToInt(), 0) }
            .width(48.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((x - handleLeftPx - handleWidthPx / 2f).roundToInt(), 0)
                }
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
        Box(
            modifier = Modifier
                .align(sliceMarkerTouchAlignment(markerNumber))
                .offset { IntOffset(touchNudgePx.roundToInt(), 0) }
                .width(48.dp)
                .height(48.dp)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragOriginFrame = frame
                        accumulatedDragPx = 0f
                    },
                )
                .semantics {
                    contentDescription = "チョップ$markerNumber の位置"
                    stateDescription = "${frame.coerceAtLeast(0)}フレーム"
                    traversalIndex = 2f + markerNumber
                    customActions = waveformNudgeActions(
                        frame = frame,
                        nudgeFrames = accessibilityNudgeFrames,
                        minimumFrame = minimumFrame,
                        maximumFrame = maximumFrame,
                        onFrameChange = onFrameChange,
                    )
                },
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
    minimumFrame: Int,
    maximumFrame: Int,
    color: Color,
    onFrameChange: (Int) -> Unit,
) {
    if (canvasWidthPx <= 1f) return
    val x = frameToX(frame, visibleStart, visibleFrames, canvasWidthPx)
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 48.dp.toPx() }
    if (x < -handleWidthPx || x > canvasWidthPx + handleWidthPx) return
    val handleLeftPx = (x - handleWidthPx / 2f)
        .coerceIn(0f, (canvasWidthPx - handleWidthPx).coerceAtLeast(0f))

    var dragOriginFrame by remember { mutableIntStateOf(frame) }
    var accumulatedDragPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        accumulatedDragPx += delta
        val frameDelta = (accumulatedDragPx / canvasWidthPx * visibleFrames).roundToInt()
        onFrameChange(dragOriginFrame + frameDelta)
    }
    val accessibilityNudgeFrames = (visibleFrames / 100).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .offset { IntOffset(handleLeftPx.roundToInt(), 0) }
            .width(48.dp)
            .fillMaxHeight()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    dragOriginFrame = frame
                    accumulatedDragPx = 0f
                },
            )
            .semantics {
                contentDescription = if (label == "S") "選択開始ハンドル" else "選択終了ハンドル"
                stateDescription = "${frame.coerceAtLeast(0)}フレーム"
                traversalIndex = if (label == "S") 0f else 1f
                customActions = waveformNudgeActions(
                    frame = frame,
                    nudgeFrames = accessibilityNudgeFrames,
                    minimumFrame = minimumFrame,
                    maximumFrame = maximumFrame,
                    onFrameChange = onFrameChange,
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((x - handleLeftPx - handleWidthPx / 2f).roundToInt(), 0)
                }
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

private fun sliceMarkerTouchAlignment(markerNumber: Int): Alignment = when (markerNumber % 3) {
    1 -> Alignment.BottomCenter
    2 -> Alignment.Center
    else -> Alignment.TopCenter
}

private fun waveformNudgeActions(
    frame: Int,
    nudgeFrames: Int,
    minimumFrame: Int,
    maximumFrame: Int,
    onFrameChange: (Int) -> Unit,
): List<CustomAccessibilityAction> = listOf(
    CustomAccessibilityAction("少し前へ") {
        val target = (frame - nudgeFrames).coerceIn(minimumFrame, maximumFrame)
        if (target == frame) false else {
            onFrameChange(target)
            true
        }
    },
    CustomAccessibilityAction("少し後へ") {
        val target = (frame + nudgeFrames).coerceIn(minimumFrame, maximumFrame)
        if (target == frame) false else {
            onFrameChange(target)
            true
        }
    },
)

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

data class WaveformEnvelope(
    val minimums: FloatArray,
    val maximums: FloatArray,
    val pixelStep: Int,
)

fun buildWaveformEnvelope(
    samples: ShortArray,
    visibleStart: Int,
    visibleEnd: Int,
    pixelWidth: Int,
    pixelStep: Int = 2,
    channelCount: Int = 1,
): WaveformEnvelope {
    require(channelCount in 1..2 && samples.size % channelCount == 0) {
        "Waveform PCM must contain complete mono or stereo frames"
    }
    val frameCount = samples.size / channelCount
    if (frameCount == 0 || visibleEnd <= visibleStart || pixelWidth <= 0) {
        return WaveformEnvelope(FloatArray(0), FloatArray(0), pixelStep.coerceAtLeast(1))
    }
    val safePixelStep = pixelStep.coerceAtLeast(1)
    val bucketCount = (pixelWidth + safePixelStep - 1) / safePixelStep
    val minimums = FloatArray(bucketCount)
    val maximums = FloatArray(bucketCount)
    val frameSpan = visibleEnd - visibleStart
    var bucket = 0
    while (bucket < bucketCount) {
        val x = bucket * safePixelStep
        val frameFrom = visibleStart + (frameSpan.toLong() * x / pixelWidth).toInt()
        val nextX = (x + safePixelStep).coerceAtMost(pixelWidth)
        val frameTo = visibleStart + (frameSpan.toLong() * nextX / pixelWidth).toInt()
        val safeFrom = frameFrom.coerceIn(0, frameCount - 1)
        val safeTo = frameTo.coerceIn(safeFrom + 1, frameCount)
        val sampleStep = max(1, (safeTo - safeFrom) / 48)

        var minimum = 0f
        var maximum = 0f
        var frame = safeFrom
        while (frame < safeTo) {
            var sum = 0
            repeat(channelCount) { channel -> sum += samples[frame * channelCount + channel].toInt() }
            val value = (sum / channelCount) / 32_768f
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            frame += sampleStep
        }
        minimums[bucket] = minimum
        maximums[bucket] = maximum
        bucket++
    }
    return WaveformEnvelope(minimums, maximums, safePixelStep)
}

internal fun DrawScope.drawWaveformEnvelope(
    envelope: WaveformEnvelope,
    color: Color,
) {
    if (envelope.minimums.isEmpty()) return
    val centerY = size.height / 2f
    val amplitude = size.height * 0.46f
    var bucket = 0
    while (bucket < envelope.minimums.size) {
        val x = bucket * envelope.pixelStep
        drawLine(
            color = color,
            start = Offset(x.toFloat(), centerY - envelope.maximums[bucket] * amplitude),
            end = Offset(x.toFloat(), centerY - envelope.minimums[bucket] * amplitude),
            strokeWidth = 1.5f,
        )
        bucket++
    }
}

fun DrawScope.drawViewportOverview(
    visibleStart: Int,
    visibleFrames: Int,
    totalFrames: Int,
    trackColor: Color,
    viewportColor: Color,
) {
    val height = 4.dp.toPx().coerceAtMost(size.height)
    val top = size.height - height
    drawRect(
        color = trackColor,
        topLeft = Offset(0f, top),
        size = androidx.compose.ui.geometry.Size(size.width, height),
    )
    val geometry = waveformOverviewGeometry(visibleStart, visibleFrames, totalFrames, size.width)
    drawRect(
        color = viewportColor,
        topLeft = Offset(geometry.left, top),
        size = androidx.compose.ui.geometry.Size((geometry.right - geometry.left).coerceAtLeast(1f), height),
    )
}

data class WaveformOverviewGeometry(
    val left: Float,
    val right: Float,
)

fun waveformOverviewGeometry(
    visibleStart: Int,
    visibleFrames: Int,
    totalFrames: Int,
    width: Float,
): WaveformOverviewGeometry {
    val safeWidth = width.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    if (safeWidth == 0f || totalFrames <= 0) return WaveformOverviewGeometry(0f, 0f)
    val safeTotal = totalFrames.coerceAtLeast(1)
    val left = visibleStart.coerceIn(0, safeTotal).toFloat() / safeTotal * safeWidth
    val right = (visibleStart.toLong() + visibleFrames.coerceAtLeast(0))
        .coerceIn(0L, safeTotal.toLong())
        .toFloat() / safeTotal * safeWidth
    return WaveformOverviewGeometry(left = left, right = right.coerceAtLeast(left))
}

internal fun frameToX(frame: Int, visibleStart: Int, visibleFrames: Int, width: Float): Float =
    ((frame - visibleStart).toFloat() / visibleFrames.coerceAtLeast(1) * width)

private fun formatTime(frame: Int, sampleRate: Int): String {
    val seconds = frame.toDouble() / sampleRate.coerceAtLeast(1)
    val minutes = (seconds / 60).toInt()
    val remainder = seconds - minutes * 60
    return "%d:%06.3f".format(minutes, remainder)
}
