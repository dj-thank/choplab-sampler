package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Fixed gain per gesture stream: changing the selected range never changes dial sensitivity mid-roll. */
class LoopDialMotion(selectionFrames: Int) {
    private val framesPerDp = selectionFrames.coerceAtLeast(2) / 1200.0
    private var remainder = 0.0
    fun consume(deltaDp: Float): Int {
        if (!deltaDp.isFinite()) return 0
        val total = remainder + deltaDp * framesPerDp
        val frames = total.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
        remainder = (total - frames).coerceIn(-0.999999, 0.999999)
        return frames
    }
}

@Composable
fun AutomaticLoopWaveform(pad: PadModel, playheadFrame: Int?, modifier: Modifier = Modifier) {
    val audio = pad.audio ?: return
    val envelope = remember(audio.id, pad.startFrame, pad.endFrame) {
        automaticRangeEnvelope(audio, pad.startFrame, pad.endFrame, 1024)
    }
    val start = formatPrecisionTrimTime(pad.startFrame, audio.sampleRate)
    val end = formatPrecisionTrimTime(pad.endFrame, audio.sampleRate)
    Box(modifier.background(DeckInk, RoundedCornerShape(8.dp)).border(1.dp, DeckGreen, RoundedCornerShape(8.dp))) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 30.dp).semantics {
            contentDescription = "選択範囲の波形。S $start、E $end。選択範囲を全体表示"
            stateDescription = "${pad.startFrame}から${pad.endFrame}フレーム。S/Eダイヤルで調整"
        }) {
            var peak = 0.001f
            for (i in envelope.minimums.indices) peak = maxOf(peak, abs(envelope.minimums[i]), abs(envelope.maximums[i]))
            val mid = size.height / 2f
            val gain = mid * 0.88f / peak
            for (i in envelope.minimums.indices) {
                val x = i.toFloat() / envelope.minimums.size * size.width
                drawLine(DeckGreen, Offset(x, mid - envelope.maximums[i] * gain),
                    Offset(x, mid - envelope.minimums[i] * gain), strokeWidth = maxOf(1f, size.width / envelope.minimums.size))
            }
            val frame = playheadFrame
            if (frame != null && frame in pad.startFrame until pad.endFrame) {
                val x = (frame - pad.startFrame).toFloat() / (pad.endFrame - pad.startFrame) * size.width
                drawLine(DeckLamp, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("S  $start", color = DeckGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("$end  E", color = DeckLamp, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
fun LoopBoundaryDials(pad: PadModel, enabled: Boolean, onRoll: (PadTrimBoundary, Int) -> Unit, modifier: Modifier = Modifier) {
    val audio = pad.audio ?: return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PadTrimBoundary.entries.forEach { boundary ->
            val frame = if (boundary == PadTrimBoundary.START) pad.startFrame else pad.endFrame
            val motion = remember(pad.globalIndex, audio.id, boundary) { LoopDialMotion(pad.endFrame - pad.startFrame) }
            val density = LocalDensity.current.density
            val scrollState = rememberScrollableState { delta ->
                val frames = motion.consume(-delta / density)
                if (enabled && frames != 0) onRoll(boundary, frames)
                if (enabled) delta else 0f
            }
            val color = if (boundary == PadTrimBoundary.START) DeckGreen else DeckLamp
            val label = if (boundary == PadTrimBoundary.START) "S 始まり" else "E 終わり"
            Surface(color = DeckInk, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).fillMaxHeight()
                .alpha(if (enabled) 1f else 0.4f)
                .border(2.dp, color, RoundedCornerShape(10.dp))
                .scrollable(scrollState, Orientation.Vertical, enabled = enabled)
                .semantics {
                    role = Role.Button
                    contentDescription = "$label のダイヤル。上下に転がして調整"
                    stateDescription = "${formatPrecisionTrimTime(frame, audio.sampleRate)}、${frame}フレーム"
                    if (!enabled) disabled()
                    customActions = listOf(
                        CustomAccessibilityAction("少し前へ") { if (enabled) { onRoll(boundary, -1); true } else false },
                        CustomAccessibilityAction("少し後へ") { if (enabled) { onRoll(boundary, 1); true } else false },
                    )
                }) {
                Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp)
                    Canvas(Modifier.weight(1f).fillMaxWidth()) {
                        val radius = size.minDimension * 0.43f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Color(0xFF302A20), radius, center)
                        drawCircle(color, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        val angle = (frame.toDouble() / audio.sampleRate.coerceAtLeast(1) * 2 * PI) - PI / 2
                        drawLine(color, center, Offset(center.x + cos(angle).toFloat() * radius * 0.8f,
                            center.y + sin(angle).toFloat() * radius * 0.8f), 3.dp.toPx())
                    }
                    Text(formatPrecisionTrimTime(frame, audio.sampleRate), color = Color(0xFFFFE8B8),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
fun WholeSourceChopMap(audio: PcmAudio, pads: List<PadModel>, selectedPad: Int, enabled: Boolean,
    onRechop: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (audio.frameCount < 1) return
    val cuts = pads.filter { it.isAssigned && it.audio?.id == audio.id }
    val envelope = remember(audio.id) { automaticRangeEnvelope(audio, 0, audio.frameCount, 600) }
    Column(modifier.background(DeckInk, RoundedCornerShape(8.dp)).padding(6.dp)) {
        Text("元曲全体 • 切り直す場所をタップ", color = DeckGreen, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().weight(1f).pointerInput(audio.id, enabled) {
            detectTapGestures { point ->
                if (enabled && size.width > 0) onRechop((point.x / size.width * audio.frameCount).toInt().coerceIn(0, audio.frameCount - 1))
            }
        }.semantics {
            contentDescription = "元曲全体のチョップ地図。${cuts.size}個の切り出し。押した位置からチョップし直す"
            if (!enabled) disabled()
            customActions = cuts.map { cut ->
                CustomAccessibilityAction("PAD ${cut.globalIndex + 1}の位置から切り直す") {
                    if (enabled) { onRechop(cut.startFrame); true } else false
                }
            }
        }) {
            val mid = size.height / 2f
            for (i in envelope.minimums.indices) {
                val x = i.toFloat() / envelope.minimums.size * size.width
                drawLine(Color(0xFF776F55), Offset(x, mid - envelope.maximums[i] * mid),
                    Offset(x, mid - envelope.minimums[i] * mid), 1f)
            }
            cuts.forEach { pad ->
                val x = pad.startFrame.toFloat() / audio.frameCount * size.width
                val end = pad.endFrame.toFloat() / audio.frameCount * size.width
                val color = if (pad.globalIndex == selectedPad) DeckLamp else DeckGreen
                drawRect(color.copy(alpha = 0.22f), Offset(x, 0f), Size((end-x).coerceAtLeast(1f), size.height))
                drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawLine(color, Offset(end, 0f), Offset(end, size.height), 1.dp.toPx())
            }
        }
    }
}

/** Display channel extrema independently: opposite-phase stereo must not look silent. */
fun automaticRangeEnvelope(audio: PcmAudio, start: Int, end: Int, width: Int): WaveformEnvelope {
    require(start >= 0 && end > start && end <= audio.frameCount && width > 0)
    val minimums = FloatArray(width)
    val maximums = FloatArray(width)
    val span = end - start
    for (bucket in 0 until width) {
        val from = start + (span.toLong() * bucket / width).toInt()
        val until = (start + (span.toLong() * (bucket + 1) / width).toInt()).coerceAtLeast(from + 1).coerceAtMost(end)
        val step = maxOf(1, (until - from) / 48)
        var frame = from
        while (frame < until) {
            for (channel in 0 until audio.channelCount) {
                val value = audio.sampleAt(frame, channel) / 32768f
                minimums[bucket] = minOf(minimums[bucket], value)
                maximums[bucket] = maxOf(maximums[bucket], value)
            }
            frame += step
        }
    }
    return WaveformEnvelope(minimums, maximums, 1)
}
