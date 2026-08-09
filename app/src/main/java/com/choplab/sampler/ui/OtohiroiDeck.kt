package com.choplab.sampler.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.visiblePads
import kotlin.math.max

internal val DeckBackground = Color(0xFF14110A)
internal val DeckPanel = Color(0xFFEFE6D0)
internal val DeckPanelDark = Color(0xFFD8CCB0)
internal val DeckInk = Color(0xFF241F14)
internal val DeckLamp = Color(0xFFFF7A1A)
internal val DeckGreen = Color(0xFF9CCF6E)
internal val DeckPad = Color(0xFF2B2618)
internal val DeckPadAssigned = Color(0xFF3A3320)
internal val DeckPadLit = Color(0xFFFFB15E)

private val DeckFont = FontFamily.Monospace

@Composable
fun OtohiroiDeck(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onClearChops: () -> Unit,
    onToggleSourcePlayback: () -> Unit,
    onSeekSource: (Int) -> Unit,
    onMasterPitchChange: (Float) -> Unit,
    onSelectBank: (Int) -> Unit,
    onSelectPad: (Int) -> Unit,
    onTriggerPad: (Int) -> Unit,
    onReleasePad: (Int) -> Unit,
    onPadPitchChange: (Float) -> Unit,
    onToggleBeat: () -> Unit,
    onBpmChange: (Float) -> Unit,
    onToggleStep: (Int) -> Unit,
    onStopAll: () -> Unit,
    advancedContent: @Composable () -> Unit,
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val audio = state.currentAudio
    val selectedPad = state.selectedPadModel()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .background(DeckPanel, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFFFFFBEF), RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(onStopAll = onStopAll)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeckButton(
                    label = "曲を読込",
                    onClick = onImportAudio,
                    enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                    modifier = Modifier.weight(1f),
                )
                DeckButton(
                    label = if (state.microphoneRecording) "■ 録音停止" else "● 録音",
                    onClick = onToggleMicrophoneRecording,
                    enabled = !state.systemAudioRecording,
                    modifier = Modifier.weight(1f),
                )
                DeckButton(
                    label = "チョップ\n全消去",
                    onClick = onClearChops,
                    enabled = audio != null,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                )
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = DeckLamp,
                    trackColor = DeckPanelDark,
                )
            }

            SourceWaveform(
                audio = audio,
                pads = state.visiblePads(),
                playheadFrame = state.sourcePlayheadFrame,
                sampling = state.sourcePlaying,
                onSeek = onSeekSource,
            )

            Text(
                text = when {
                    state.sourcePlaying -> "● サンプリング中 — 『ここだ』でPADを叩くと、その瞬間が刻まれます"
                    audio != null -> "停止中 — PADで頭から再生 / 波形タップで頭出し"
                    else -> "曲を読み込んでください"
                },
                color = if (state.sourcePlaying) DeckLamp else Color(0xFF7A6C4B),
                fontFamily = DeckFont,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (audio != null) {
                Text(
                    text = "${audio.name}  •  1ch ${(audio.sampleRate / 1_000f).formatOne()}kHz  /  出力 2ch",
                    color = Color(0xFF7A6C4B),
                    fontFamily = DeckFont,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeckButton(
                    label = if (state.sourcePlaying) "■ 停止（サンプリング中）" else "▶ 曲を再生",
                    onClick = onToggleSourcePlayback,
                    enabled = audio != null,
                    active = state.sourcePlaying,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                )
                Text(
                    text = "${formatDeckTime(state.sourcePlayheadFrame, audio?.sampleRate)} / " +
                        formatDeckTime(audio?.frameCount ?: 0, audio?.sampleRate),
                    color = Color(0xFF4C4430),
                    fontFamily = DeckFont,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(112.dp),
                )
            }

            DeckSlider(
                label = "曲の全体トーン",
                value = state.masterPitchSemitones,
                valueRange = -12f..12f,
                valueLabel = signedSemitones(state.masterPitchSemitones),
                enabled = audio != null,
                onValueChange = onMasterPitchChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(SamplerConfig.BANK_COUNT) { bank ->
                    DeckButton(
                        label = "BANK ${('A'.code + bank).toChar()}",
                        onClick = { onSelectBank(bank) },
                        active = state.selectedBank == bank,
                        modifier = Modifier.weight(1f),
                        fontSize = 10.sp,
                    )
                }
            }

            PadGrid(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                captureMode = state.sourcePlaying,
                onTrigger = onTriggerPad,
                onRelease = onReleasePad,
                onSelect = onSelectPad,
            )

            DeckSlider(
                label = "トーン（PAD %02d）".format(selectedPad.indexInBank + 1),
                value = selectedPad.pitchSemitones.coerceIn(-12f, 12f),
                valueRange = -12f..12f,
                valueLabel = signedSemitones(selectedPad.pitchSemitones),
                enabled = selectedPad.isAssigned,
                onValueChange = onPadPitchChange,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeckPanelDark, RoundedCornerShape(10.dp))
                    .border(2.dp, DeckInk, RoundedCornerShape(10.dp))
                    .padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckButton(
                        label = if (state.transportPlaying) "■ 停止" else "▶ ビート",
                        onClick = onToggleBeat,
                        active = state.transportPlaying,
                        modifier = Modifier.width(102.dp),
                        fontSize = 11.sp,
                    )
                    Text(
                        "BPM ${state.bpm.toInt()}",
                        color = DeckInk,
                        fontFamily = DeckFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                    Slider(
                        value = state.bpm.coerceIn(60f, 160f),
                        onValueChange = onBpmChange,
                        valueRange = 60f..160f,
                        colors = deckSliderColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
                StepSequencer(
                    selectedPad = state.selectedPad,
                    activeSteps = state.activeSteps,
                    currentStep = state.currentStep,
                    onToggleStep = onToggleStep,
                )
                Text(
                    "PADを選んで、鳴らすステップをタップ",
                    color = Color(0xFF5C523A),
                    fontFamily = DeckFont,
                    fontSize = 9.sp,
                )
            }

            Text(
                text = state.statusMessage,
                color = Color(0xFF8A3D00),
                fontFamily = DeckFont,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "状態: ${state.statusMessage}" },
            )

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showAdvanced) "▲ 詳細機能を閉じる" else "▼ 詳細機能（端末音・範囲編集・FX・書き出し）",
                    color = DeckInk,
                    fontFamily = DeckFont,
                    fontSize = 11.sp,
                )
            }
            if (showAdvanced) advancedContent()
        }
    }
}

@Composable
private fun Header(onStopAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.dp, color = Color.Transparent)
            .padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(
                "おとひろい",
                color = DeckInk,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
                letterSpacing = 2.sp,
            )
            Text(
                "ChopLab / open sampler",
                color = Color(0xFF6B6046),
                fontFamily = DeckFont,
                fontSize = 8.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "曲を流しながらPADを叩いてチョップ",
                color = Color(0xFF6B6046),
                fontFamily = DeckFont,
                fontSize = 8.sp,
            )
            Text(
                "ALL STOP",
                color = DeckInk,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                modifier = Modifier
                    .clickable(onClick = onStopAll)
                    .padding(top = 4.dp, start = 12.dp, bottom = 2.dp),
            )
        }
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(DeckInk),
    )
}

@Composable
private fun SourceWaveform(
    audio: PcmAudio?,
    pads: List<PadModel>,
    playheadFrame: Int,
    sampling: Boolean,
    onSeek: (Int) -> Unit,
) {
    val peaks = remember(audio?.id) { audio?.let(::buildDeckPeaks) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Color(0xFF0D0B06), shape)
            .border(if (sampling) 3.dp else 2.dp, if (sampling) DeckLamp else DeckInk, shape),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(audio?.id) {
                    detectTapGestures { offset ->
                        val source = audio ?: return@detectTapGestures
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((fraction * source.frameCount).toInt().coerceIn(0, source.frameCount - 1))
                    }
                }
                .semantics { contentDescription = "曲の波形。タップで再生位置を移動" },
        ) {
            val source = audio ?: return@Canvas
            val values = peaks ?: return@Canvas
            val center = size.height / 2f
            val amplitude = size.height * 0.44f
            values.forEachIndexed { index, peak ->
                val x = index.toFloat() / max(1, values.lastIndex) * size.width
                drawLine(
                    color = DeckGreen,
                    start = Offset(x, center - peak.second * amplitude),
                    end = Offset(x, center - peak.first * amplitude),
                    strokeWidth = 1.2f,
                )
            }

            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(26, 13, 0)
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            pads.filter { it.isAssigned && it.audio?.id == source.id }
                .sortedBy(PadModel::startFrame)
                .forEach { pad ->
                    val x = pad.startFrame.toFloat() / source.frameCount.coerceAtLeast(1) * size.width
                    drawLine(DeckLamp, Offset(x, 13.dp.toPx()), Offset(x, size.height), strokeWidth = 1.5f)
                    drawRect(
                        color = DeckLamp,
                        topLeft = Offset((x - 1.dp.toPx()).coerceAtLeast(0f), 0f),
                        size = androidx.compose.ui.geometry.Size(18.dp.toPx(), 13.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "%02d".format(pad.indexInBank + 1),
                        x + 1.dp.toPx(),
                        10.dp.toPx(),
                        markerPaint,
                    )
                }

            val headX = playheadFrame.coerceIn(0, source.frameCount).toFloat() /
                source.frameCount.coerceAtLeast(1) * size.width
            drawLine(
                color = if (sampling) Color(0xFFFFF0D0) else Color(0xFFA89A78),
                start = Offset(headX, 0f),
                end = Offset(headX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

private fun buildDeckPeaks(audio: PcmAudio): List<Pair<Float, Float>> {
    val width = 536
    if (audio.samples.isEmpty()) return emptyList()
    val framesPerBucket = max(1, audio.samples.size / width)
    return List(width) { bucket ->
        val from = (bucket * framesPerBucket).coerceAtMost(audio.samples.lastIndex)
        val to = ((bucket + 1) * framesPerBucket).coerceAtMost(audio.samples.size)
        val stride = max(1, (to - from) / 48)
        var minimum = 0f
        var maximum = 0f
        var frame = from
        while (frame < to) {
            val value = audio.samples[frame] / 32_768f
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
            frame += stride
        }
        minimum to maximum
    }
}

@Composable
private fun DeckSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            label,
            color = DeckInk,
            fontFamily = DeckFont,
            fontSize = 9.sp,
            modifier = Modifier.width(116.dp),
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = deckSliderColors(),
            modifier = Modifier.weight(1f),
        )
        Text(
            valueLabel,
            color = DeckInk,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(35.dp),
        )
    }
}

@Composable
private fun deckSliderColors() = SliderDefaults.colors(
    thumbColor = DeckLamp,
    activeTrackColor = DeckLamp,
    inactiveTrackColor = Color(0xFF887B5E),
    disabledThumbColor = Color(0xFFA89A78),
    disabledActiveTrackColor = Color(0xFFA89A78),
    disabledInactiveTrackColor = DeckPanelDark,
)

@Composable
private fun DeckButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
) {
    val background = when {
        active -> DeckLamp
        else -> DeckPanelDark
    }
    val foreground = if (active) Color(0xFF2A1000) else DeckInk
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = if (enabled) 3.dp else 0.dp,
        modifier = modifier
            .border(2.dp, DeckInk.copy(alpha = if (enabled) 1f else 0.35f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label.replace("\n", " ") },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(43.dp)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = foreground.copy(alpha = if (enabled) 1f else 0.35f),
                fontFamily = DeckFont,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp,
            )
        }
    }
}

private fun signedSemitones(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun formatDeckTime(frame: Int, sampleRate: Int?): String {
    val rate = sampleRate?.takeIf { it > 0 } ?: return "0:00.0"
    val seconds = frame.coerceAtLeast(0).toDouble() / rate
    val minutes = (seconds / 60.0).toInt()
    val remainder = seconds - minutes * 60.0
    return "%d:%04.1f".format(minutes, remainder)
}

private fun Float.formatOne(): String = "%.1f".format(this)
