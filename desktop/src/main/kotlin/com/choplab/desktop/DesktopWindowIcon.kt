package com.choplab.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/** Code-native counterpart of the Android waveform launcher mark. */
object DesktopWindowIcon : Painter() {
    override val intrinsicSize: Size = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val scaleX = size.width / 108f
        val scaleY = size.height / 108f
        drawRect(Color(0xFF111315))
        val bars = listOf(
            floatArrayOf(12f, 43f, 8f, 22f),
            floatArrayOf(24f, 32f, 8f, 44f),
            floatArrayOf(36f, 20f, 8f, 68f),
            floatArrayOf(48f, 37f, 8f, 34f),
            floatArrayOf(60f, 26f, 8f, 56f),
            floatArrayOf(72f, 39f, 8f, 30f),
            floatArrayOf(84f, 47f, 12f, 14f),
        )
        bars.forEach { bar ->
            drawRect(
                color = Color(0xFFFFB15C),
                topLeft = Offset(bar[0] * scaleX, bar[1] * scaleY),
                size = Size(bar[2] * scaleX, bar[3] * scaleY),
            )
        }
        drawRect(
            color = Color(0xFF8FD4C7),
            topLeft = Offset(12f * scaleX, 83f * scaleY),
            size = Size(84f * scaleX, 7f * scaleY),
        )
    }
}
