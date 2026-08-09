package com.choplab.sampler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.SamplerViewModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.visiblePads

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplerScreen(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ChopLab", fontWeight = FontWeight.Black)
                        Text("MPC-style mobile sampler", fontSize = 11.sp)
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::stopAllSounds) {
                        Text("ALL STOP")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceSection(
                state = state,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
            )

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.statusMessage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    fontSize = 13.sp,
                )
            }

            WaveformSection(state = state, viewModel = viewModel)
            PadEditSection(state = state, viewModel = viewModel)

            SectionCard(
                title = "16 PAD / BANK ${bankName(state.selectedBank)}",
                subtitle = "タップで発音、長押しで編集対象を選択",
            ) {
                PadGrid(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    onTrigger = viewModel::triggerPad,
                    onRelease = viewModel::releasePad,
                    onSelect = viewModel::selectPad,
                )
            }

            SequencerSection(
                state = state,
                viewModel = viewModel,
                onExportBeat = onExportBeat,
            )

            Text(
                text = "ChopLabは独自UIの試作サンプラーです。AKAI Professionalの商標・製品UI・素材を複製していません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SourceSection(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
) {
    SectionCard(
        title = "SOURCE",
        subtitle = "ファイル、マイク、または端末内で再生中の録音可能な音声を取り込み",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Button(
                onClick = onImportAudio,
                enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text("音声\n読込", textAlign = TextAlign.Center, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onToggleMicrophoneRecording,
                enabled = !state.systemAudioRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.microphoneRecording) "マイク\n停止" else "マイク\n録音",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                onClick = onToggleSystemAudioRecording,
                enabled = !state.microphoneRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.systemAudioRecording) "端末音\n停止" else "端末音\n録音",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "端末音録音はAndroid 10以降の仕組みを使用します。配信・DRM保護音源など、再生側が録音を許可しない音は取り込めません。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaveformSection(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
) {
    SectionCard(
        title = "SAMPLE EDIT / CHOP",
        subtitle = "S/Eハンドルで長い音声の使用範囲を限定し、手動または自動でチョップ",
    ) {
        val audio = state.currentAudio
        if (audio == null) {
            Text(
                "音声を取り込むと波形編集画面が表示されます。",
                modifier = Modifier.padding(vertical = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Text(
            "${audio.name}  •  ${formatDuration(audio.durationSeconds)}  •  ${audio.sampleRate} Hz",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))

        WaveformEditor(
            audio = audio,
            rangeStartFrame = state.rangeStartFrame,
            rangeEndFrame = state.rangeEndFrame,
            sliceMarkers = state.sliceMarkers,
            activeSlice = state.activeSliceRange(),
            manualChopEnabled = state.manualChopEnabled,
            onRangeStartChange = viewModel::setRangeStart,
            onRangeEndChange = viewModel::setRangeEnd,
            onSliceMarkerChange = viewModel::moveSliceMarker,
            onWaveformTap = { frame ->
                if (state.manualChopEnabled) viewModel.addSliceMarker(frame)
                else viewModel.selectSliceAt(frame)
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::previewCurrentSelection,
                modifier = Modifier.weight(1f),
            ) { Text("試聴", fontSize = 12.sp) }
            FilterChip(
                selected = state.manualChopEnabled,
                onClick = viewModel::toggleManualChop,
                label = { Text("手動CHOP", fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = viewModel::resetRange,
                modifier = Modifier.weight(1f),
            ) { Text("範囲RESET", fontSize = 11.sp) }
        }

        Spacer(Modifier.height(6.dp))
        Text("AUTO CHOP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(4, 8, 16).forEach { divisions ->
                AssistChip(
                    onClick = { viewModel.autoChopEqual(divisions) },
                    label = { Text("$divisions 分割", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                )
            }
            AssistChip(
                onClick = viewModel::autoChopTransient,
                label = { Text("TRANSIENT", fontSize = 9.sp) },
                modifier = Modifier.weight(1.25f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${state.sliceRanges().size} slices" +
                    (state.activeSliceIndex?.let { "  /  選択 ${it + 1}" } ?: ""),
                fontSize = 11.sp,
            )
            Row {
                TextButton(onClick = viewModel::removeBoundaryForActiveSlice) {
                    Text("選択境界削除", fontSize = 10.sp)
                }
                TextButton(onClick = viewModel::clearSliceMarkers) {
                    Text("全消去", fontSize = 10.sp)
                }
            }
        }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Button(
                onClick = viewModel::assignCurrentSelectionToPad,
                modifier = Modifier.weight(1f),
            ) { Text("選択 → PAD", fontSize = 12.sp) }
            Button(
                onClick = viewModel::assignAllSlicesToPads,
                modifier = Modifier.weight(1f),
            ) { Text("全SLICE → PAD", fontSize = 11.sp) }
        }
        FilterChip(
            selected = state.autoNextPad,
            onClick = viewModel::toggleAutoNext,
            label = {
                Text(
                    if (state.autoNextPad) "AUTO NEXT (SLICE + PAD): ON" else "AUTO NEXT: OFF",
                    fontSize = 11.sp,
                )
            },
        )
    }
}

@Composable
private fun PadEditSection(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    SectionCard(
        title = "PAD EDIT",
        subtitle = "BANKとPADを選び、ピッチ・トーン・ゲイン・再生方式を個別設定",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            repeat(SamplerConfig.BANK_COUNT) { bank ->
                FilterChip(
                    selected = state.selectedBank == bank,
                    onClick = { viewModel.selectBank(bank) },
                    label = { Text(bankName(bank), fontWeight = FontWeight.Black) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            "BANK ${bankName(pad.bankIndex)} / PAD ${pad.indexInBank + 1}" +
                if (pad.isAssigned) "  •  ${pad.audio?.name}" else "  •  EMPTY",
            fontWeight = FontWeight.Bold,
        )

        ParameterSlider(
            label = "PITCH",
            valueLabel = "%+.1f st".format(pad.pitchSemitones),
            value = pad.pitchSemitones,
            range = -24f..24f,
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadPitch,
        )
        ParameterSlider(
            label = "TONE / LOW-PASS",
            valueLabel = "${(pad.tone * 100).toInt()}%",
            value = pad.tone,
            range = 0f..1f,
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadTone,
        )
        ParameterSlider(
            label = "GAIN",
            valueLabel = "${(pad.gain * 100).toInt()}%",
            value = pad.gain,
            range = 0f..1.5f,
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadGain,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = pad.reverse,
                onClick = viewModel::toggleSelectedPadReverse,
                enabled = pad.isAssigned,
                label = { Text("REVERSE") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = pad.playMode == PadPlayMode.GATE,
                onClick = viewModel::toggleSelectedPadPlayMode,
                enabled = pad.isAssigned,
                label = { Text(if (pad.playMode == PadPlayMode.GATE) "GATE" else "ONE SHOT") },
                modifier = Modifier.weight(1f),
            )
        }

        Text("CHOKE GROUP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(5) { group ->
                FilterChip(
                    selected = pad.chokeGroup == group,
                    onClick = { viewModel.setSelectedPadChokeGroup(group) },
                    enabled = pad.isAssigned,
                    label = { Text(if (group == 0) "OFF" else group.toString(), fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        OutlinedButton(
            onClick = viewModel::clearSelectedPad,
            enabled = pad.isAssigned,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("選択PADを消去")
        }
    }
}

@Composable
private fun SequencerSection(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    onExportBeat: () -> Unit,
) {
    val selectedPad = state.selectedPadModel()
    val hasAudibleSteps = state.activeSteps.any { key ->
        val padIndex = key / SamplerConfig.STEP_COUNT
        state.pads.getOrNull(padIndex)?.isAssigned == true
    }
    SectionCard(
        title = "16-STEP BEAT SEQUENCER",
        subtitle = "選択PADごとにステップを配置。REC中はPAD演奏を現在ステップへクオンタイズ",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Button(
                onClick = viewModel::toggleTransport,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.transportPlaying) "STOP" else "PLAY")
            }
            FilterChip(
                selected = state.recordArmed,
                onClick = viewModel::toggleRecordArm,
                label = { Text(if (state.recordArmed) "REC ARMED" else "REC") },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = viewModel::clearSelectedPadPattern,
                modifier = Modifier.weight(1f),
            ) {
                Text("PAD CLEAR", fontSize = 10.sp)
            }
        }

        ParameterSlider(
            label = "BPM",
            valueLabel = state.bpm.toInt().toString(),
            value = state.bpm,
            range = 40f..240f,
            onValueChange = viewModel::setBpm,
        )
        ParameterSlider(
            label = "SWING",
            valueLabel = "${state.swing.toInt()}%",
            value = state.swing,
            range = 50f..75f,
            onValueChange = viewModel::setSwing,
        )

        Text(
            "BANK ${bankName(selectedPad.bankIndex)} / PAD ${selectedPad.indexInBank + 1} のパターン",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        StepSequencer(
            selectedPad = state.selectedPad,
            activeSteps = state.activeSteps,
            currentStep = state.currentStep,
            onToggleStep = viewModel::toggleStep,
        )
        Button(
            onClick = onExportBeat,
            enabled = hasAudibleSteps && !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("4小節をWAV書き出し")
        }
        TextButton(
            onClick = viewModel::clearAllPattern,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("パターンを全消去")
        }
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(valueLabel, fontSize = 11.sp)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        enabled = enabled,
    )
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            subtitle?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

private fun bankName(index: Int): String = ('A'.code + index.coerceIn(0, 3)).toChar().toString()

private fun formatDuration(seconds: Float): String {
    val totalMilliseconds = (seconds * 1_000).toLong().coerceAtLeast(0L)
    val minutes = totalMilliseconds / 60_000
    val remainderSeconds = (totalMilliseconds % 60_000) / 1_000.0
    return "%d:%05.2f".format(minutes, remainderSeconds)
}
