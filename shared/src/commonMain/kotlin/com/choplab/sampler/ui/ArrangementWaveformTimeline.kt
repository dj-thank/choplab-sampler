package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.activeBanksAtStep
import com.choplab.sampler.model.stepKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val TimelineShape = RoundedCornerShape(8.dp)
private val TimelineFont = FontFamily.Monospace

@Composable
fun ArrangementWaveformTimeline(
    pad: PadModel,
    activeSteps: Set<Int>,
    currentStep: Int,
    transportPlaying: Boolean,
    modifier: Modifier = Modifier,
    loopPlayheadFrame: Int = -1,
    loopPlaying: Boolean = false,
) {
    val peaks = remember(pad.audio?.id, pad.startFrame, pad.endFrame) {
        buildSlicePeaks(pad)
    }
    val bankActivityByStep = remember(activeSteps) {
        List(SamplerConfig.STEP_COUNT) { step -> activeSteps.activeBanksAtStep(step) }
    }
    val currentBanks = if (currentStep in 0 until SamplerConfig.STEP_COUNT) {
        bankActivityByStep[currentStep]
    } else {
        emptySet()
    }
    val bankText = currentBanks.joinToString(separator = "+") { bank ->
        ('A'.code + bank).toChar().toString()
    }.ifEmpty { "—" }
    val padLabel = "${('A'.code + pad.bankIndex).toChar()}-%02d".format(pad.indexInBank + 1)
    val stepLabel = if (currentStep in 0 until SamplerConfig.STEP_COUNT) {
        "%02d / 16".format(currentStep + 1)
    } else {
        "— / 16"
    }
    val loopProgress = if (
        loopPlaying && pad.isAssigned && loopPlayheadFrame in pad.startFrame until pad.endFrame
    ) {
        (loopPlayheadFrame - pad.startFrame).toFloat() /
            (pad.endFrame - pad.startFrame).coerceAtLeast(1)
    } else {
        null
    }
    val arrangementDescription = bankActivityByStep.mapIndexedNotNull { step, banks ->
        if (banks.isEmpty()) null else {
            val names = banks.joinToString("+") { bank -> ('A'.code + bank).toChar().toString() }
            "${step + 1}=$names"
        }
    }.joinToString("、").ifEmpty { "配置なし" }
    val loopDescription = loopProgress?.let { "ビートループ中 ${(it * 100).toInt()}パーセント。" }.orEmpty()
    val description = "ビート実波形。選択PAD $padLabel。$loopDescription 現在ステップ $stepLabel。" +
        "鳴るBANK $bankText。全配置 $arrangementDescription"

    Box(
        modifier = modifier
            .background(Color(0xFF090806), TimelineShape)
            .border(
                width = if (transportPlaying || loopPlaying) 2.dp else 1.dp,
                color = if (transportPlaying || loopPlaying) DeckLamp else Color(0xFF4A422E),
                shape = TimelineShape,
            )
            .semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val headerHeight = 22.dp.toPx()
            val bankAreaHeight = 20.dp.toPx()
            val timelineLeft = 20.dp.toPx()
            val timelineWidth = (size.width - timelineLeft).coerceAtLeast(1f)
            val stepWidth = timelineWidth / SamplerConfig.STEP_COUNT
            val waveTop = headerHeight
            val waveBottom = (size.height - bankAreaHeight).coerceAtLeast(waveTop + 1f)
            val waveCenter = (waveTop + waveBottom) / 2f
            val waveAmplitude = (waveBottom - waveTop) * 0.42f

            loopProgress?.let { progress ->
                val playheadX = timelineLeft + progress.coerceIn(0f, 1f) * timelineWidth
                drawLine(
                    color = Color(0xFFFFF0D0),
                    start = Offset(playheadX, headerHeight),
                    end = Offset(playheadX, waveBottom),
                    strokeWidth = 3.dp.toPx(),
                )
                drawCircle(
                    color = DeckLamp,
                    radius = 4.dp.toPx(),
                    center = Offset(playheadX, headerHeight),
                )
            }

            if (currentStep in 0 until SamplerConfig.STEP_COUNT) {
                val playheadX = timelineLeft + currentStep * stepWidth
                drawRect(
                    color = DeckLamp.copy(alpha = 0.18f),
                    topLeft = Offset(playheadX, headerHeight),
                    size = Size(stepWidth, size.height - headerHeight),
                )
                drawLine(
                    color = Color(0xFFFFF0D0),
                    start = Offset(playheadX + stepWidth / 2f, headerHeight),
                    end = Offset(playheadX + stepWidth / 2f, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
                drawCircle(
                    color = DeckLamp,
                    radius = 4.dp.toPx(),
                    center = Offset(playheadX + stepWidth / 2f, headerHeight),
                )
            }

            repeat(SamplerConfig.STEP_COUNT + 1) { step ->
                val x = timelineLeft + step * stepWidth
                drawLine(
                    color = if (step % 4 == 0) Color(0xFF776B4E) else Color(0xFF332E22),
                    start = Offset(x, headerHeight),
                    end = Offset(x, size.height),
                    strokeWidth = if (step % 4 == 0) 1.2.dp.toPx() else 0.6.dp.toPx(),
                )
            }

            peaks.forEachIndexed { index, peak ->
                val x = timelineLeft + index.toFloat() / max(1, peaks.lastIndex) * timelineWidth
                drawLine(
                    color = DeckGreen,
                    start = Offset(x, waveCenter - peak.second * waveAmplitude),
                    end = Offset(x, waveCenter - peak.first * waveAmplitude),
                    strokeWidth = 1.1f,
                )
            }

            val bankRowHeight = bankAreaHeight / SamplerConfig.BANK_COUNT
            repeat(SamplerConfig.BANK_COUNT) { bank ->
                val y = waveBottom + bankRowHeight * (bank + 0.5f)
                repeat(SamplerConfig.STEP_COUNT) { step ->
                    val bankHasEvent = bank in bankActivityByStep[step]
                    if (bankHasEvent) {
                        val selectedPadEvent = bank == pad.bankIndex &&
                            stepKey(pad.globalIndex, step) in activeSteps
                        drawCircle(
                            color = if (selectedPadEvent) DeckLamp else DeckGreen,
                            radius = if (selectedPadEvent) 2.2.dp.toPx() else 1.6.dp.toPx(),
                            center = Offset(timelineLeft + (step + 0.5f) * stepWidth, y),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 7.dp, end = 7.dp, top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "実波形  PAD $padLabel",
                color = DeckGreen,
                fontFamily = TimelineFont,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
            )
            Text(
                text = loopProgress?.let { "ループ ${(it * 100).toInt()}%  ·  PAD $padLabel" }
                    ?: "いま $stepLabel  ·  BANK $bankText",
                color = if (transportPlaying || loopPlaying) Color(0xFFFFD29A) else Color(0xFFA89A78),
                fontFamily = TimelineFont,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 5.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "A\nB\nC\nD",
                color = Color(0xFF9B8E6B),
                fontFamily = TimelineFont,
                fontWeight = FontWeight.Bold,
                fontSize = 4.sp,
                lineHeight = 5.sp,
            )
        }

        if (!pad.isAssigned) {
            Text(
                text = "音の入ったPADを選ぶと波形が出ます",
                color = Color(0xFF8D8060),
                fontFamily = TimelineFont,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun buildSlicePeaks(pad: PadModel): List<Pair<Float, Float>> {
    val audio = pad.audio ?: return emptyList()
    if (!pad.isAssigned || audio.samples.isEmpty()) return emptyList()
    val start = pad.startFrame.coerceIn(0, audio.frameCount - 1)
    val end = pad.endFrame.coerceIn(start + 1, audio.frameCount)
    val width = min(512, end - start)
    val framesPerBucket = max(1, (end - start) / width)
    val rawPeaks = List(width) { bucket ->
        val from = (start + bucket * framesPerBucket).coerceAtMost(end - 1)
        val to = (from + framesPerBucket).coerceAtMost(end)
        val stride = max(1, (to - from) / 32)
        var minimum = 0f
        var maximum = 0f
        var frame = from
        while (frame < to) {
            val value = audio.monoSampleAt(frame) / 32_768f
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            frame += stride
        }
        minimum to maximum
    }
    val strongestPeak = rawPeaks.maxOfOrNull { (minimum, maximum) ->
        max(abs(minimum), abs(maximum))
    }?.coerceAtLeast(0.05f) ?: 1f
    return rawPeaks.map { (minimum, maximum) ->
        minimum / strongestPeak to maximum / strongestPeak
    }
}
