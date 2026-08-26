package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SliceRange

fun precisionTrimOverviewVisible(availableHeightDp: Int): Boolean = availableHeightDp >= 500

@Composable
fun PrecisionTrimOverview(
    audio: PcmAudio,
    padRange: SliceRange,
    viewport: WaveformViewport,
    focusFrame: Int,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val envelope = remember(audio.id, audio.channelCount, canvasSize.width) {
        buildWaveformEnvelope(
            samples = audio.samples,
            visibleStart = 0,
            visibleEnd = audio.frameCount,
            pixelWidth = canvasSize.width,
            channelCount = audio.channelCount,
            pixelStep = 2,
        )
    }
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier = modifier
            .background(Color(0xFF17130D), shape)
            .border(1.5.dp, DeckInk, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics {
                contentDescription = precisionTrimOverviewDescription(
                    padRange = padRange,
                    viewport = viewport,
                    sampleRate = audio.sampleRate,
                )
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "全体の中の位置",
                color = DeckGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 7.sp,
            )
            Text(
                text = "表示 ${formatOverviewDuration(viewport.visibleFrames, audio.sampleRate)}",
                color = Color(0xFFE8DDBF),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 7.sp,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it },
        ) {
            drawRect(Color(0xFF0B0906))
            drawLine(
                color = Color(0xFF665C43),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
            )
            val total = audio.frameCount.coerceAtLeast(1)
            val padLeft = frameToX(padRange.startFrame, 0, total, size.width).coerceIn(0f, size.width)
            val padRight = frameToX(padRange.endFrame, 0, total, size.width).coerceIn(0f, size.width)
            if (padRight > padLeft) {
                drawRect(
                    color = DeckGreen.copy(alpha = 0.18f),
                    topLeft = Offset(padLeft, 0f),
                    size = Size(padRight - padLeft, size.height),
                )
            }
            drawWaveformEnvelope(envelope, Color(0xFFC07833))
            val visible = waveformOverviewGeometry(
                visibleStart = viewport.visibleStart,
                visibleFrames = viewport.visibleFrames,
                totalFrames = viewport.totalFrames,
                width = size.width,
            )
            if (visible.right > visible.left) {
                drawRect(
                    color = DeckLamp.copy(alpha = 0.14f),
                    topLeft = Offset(visible.left, 0f),
                    size = Size(visible.right - visible.left, size.height),
                )
                drawRect(
                    color = DeckLamp,
                    topLeft = Offset(visible.left, 0f),
                    size = Size(visible.right - visible.left, size.height),
                    style = Stroke(width = 2f),
                )
            }
            val focusX = frameToX(focusFrame, 0, total, size.width).coerceIn(0f, size.width)
            drawLine(
                color = Color(0xFFFFE8B8),
                start = Offset(focusX, 0f),
                end = Offset(focusX, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

fun precisionTrimOverviewDescription(
    padRange: SliceRange,
    viewport: WaveformViewport,
    sampleRate: Int,
): String = buildString {
    append("全体波形。PAD範囲 ")
    append(formatPrecisionTrimTime(padRange.startFrame, sampleRate))
    append(" から ")
    append(formatPrecisionTrimTime(padRange.endFrame, sampleRate))
    append("。編集表示 ")
    append(formatPrecisionTrimTime(viewport.visibleStart, sampleRate))
    append(" から ")
    append(formatPrecisionTrimTime(viewport.visibleStart + viewport.visibleFrames, sampleRate))
}

private fun formatOverviewDuration(frames: Int, sampleRate: Int): String =
    "%.1f秒".format(frames.coerceAtLeast(0).toFloat() / sampleRate.coerceAtLeast(1))
