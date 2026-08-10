package com.choplab.sampler.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.SamplerViewModel
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.visiblePads
import kotlin.math.max
import kotlin.math.roundToInt

internal val DeckBackground = Color(0xFF0F0D08)
internal val DeckPanel = Color(0xFFEDE2C8)
internal val DeckPanelDark = Color(0xFFD3C5A5)
internal val DeckInk = Color(0xFF211D13)
internal val DeckLamp = Color(0xFFFF7417)
internal val DeckGreen = Color(0xFF9FD46B)
internal val DeckPad = Color(0xFF262116)
internal val DeckPadAssigned = Color(0xFF38301D)
internal val DeckPadLit = Color(0xFFFFB25E)

private val DeckFont = FontFamily.Monospace
private val ConsoleShape = RoundedCornerShape(13.dp)
private val PanelShape = RoundedCornerShape(8.dp)

private enum class ConsoleMode(val label: String) {
    CHOP("CHOP"),
    PAD("PAD"),
    SEQ("SEQ"),
    SOURCE("SOURCE"),
}

private enum class PadEditorPage(val label: String) {
    PARAM("PARAM"),
    PLAY("PLAY"),
}

@Composable
fun OtohiroiDeck(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
) {
    var modeName by rememberSaveable { mutableStateOf(ConsoleMode.CHOP.name) }
    var padPageName by rememberSaveable { mutableStateOf(PadEditorPage.PARAM.name) }
    val mode = ConsoleMode.valueOf(modeName)
    val padPage = PadEditorPage.valueOf(padPageName)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 980.dp),
        ) {
            val metrics = resolveDeckLayout(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
            val gap = metrics.gapDp.dp
            Surface(
                color = DeckPanel,
                contentColor = DeckInk,
                shape = ConsoleShape,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color(0xFFFFF8E8), ConsoleShape),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(metrics.contentPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    MachineHeader(
                        state = state,
                        mode = mode,
                        height = metrics.headerHeightDp.dp,
                        onStopAll = viewModel::stopAllSounds,
                    )
                    ModeStrip(
                        selected = mode,
                        height = metrics.modeBarHeightDp.dp,
                        onSelect = { modeName = it.name },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        when (mode) {
                            ConsoleMode.CHOP -> ChopWorkspace(
                                state = state,
                                metrics = metrics,
                                onImportAudio = onImportAudio,
                                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                                viewModel = viewModel,
                            )
                            ConsoleMode.PAD -> PadWorkspace(
                                state = state,
                                metrics = metrics,
                                page = padPage,
                                onPageChange = { padPageName = it.name },
                                viewModel = viewModel,
                            )
                            ConsoleMode.SEQ -> SequenceWorkspace(
                                state = state,
                                metrics = metrics,
                                onExportBeat = onExportBeat,
                                viewModel = viewModel,
                            )
                            ConsoleMode.SOURCE -> SourceWorkspace(
                                state = state,
                                metrics = metrics,
                                onImportAudio = onImportAudio,
                                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                                viewModel = viewModel,
                            )
                        }
                    }
                    if (metrics.showStatusStrip) {
                        ConsoleStatusStrip(
                            state = state,
                            mode = mode,
                            height = metrics.statusHeightDp.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MachineHeader(
    state: SamplerUiState,
    mode: ConsoleMode,
    height: Dp,
    onStopAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(DeckInk, PanelShape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusLamp(
            active = state.sourcePlaying || state.transportPlaying ||
                state.microphoneRecording || state.systemAudioRecording,
            alert = state.microphoneRecording || state.systemAudioRecording,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "OTOHIROI",
                color = Color(0xFFFFF1CF),
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                maxLines = 1,
            )
            Text(
                if (state.statusMessage.isBlank()) "PRO MOBILE SAMPLER / ${mode.label}"
                else "${mode.label} / ${state.statusMessage}",
                color = Color(0xFF9C906F),
                fontFamily = DeckFont,
                fontSize = 7.sp,
                maxLines = 1,
            )
        }
        Text(
            "BANK ${bankName(state.selectedBank)}  ${state.bpm.toInt()} BPM",
            color = DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1,
        )
        MachineButton(
            label = "ALL STOP",
            onClick = onStopAll,
            active = state.sourcePlaying || state.transportPlaying,
            modifier = Modifier
                .width(82.dp)
                .fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun StatusLamp(active: Boolean, alert: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = when {
                    alert -> Color(0xFFFF3B24)
                    active -> DeckLamp
                    else -> Color(0xFF665C43)
                },
                shape = CircleShape,
            )
            .border(1.dp, Color.Black, CircleShape)
            .semantics { contentDescription = if (active || alert) "動作中" else "停止中" },
    )
}

@Composable
private fun ModeStrip(
    selected: ConsoleMode,
    height: Dp,
    onSelect: (ConsoleMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ConsoleMode.entries.forEach { mode ->
            MachineButton(
                label = mode.label,
                onClick = { onSelect(mode) },
                active = selected == mode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun ChopWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            ChopControlDeck(
                state = state,
                metrics = metrics,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                flexibleWaveform = true,
            )
            PadGrid(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                captureMode = state.sourcePlaying,
                onTrigger = viewModel::triggerPad,
                onRelease = viewModel::releasePad,
                onSelect = viewModel::selectPad,
                gap = gap,
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            ChopControlDeck(
                state = state,
                metrics = metrics,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
                flexibleWaveform = false,
            )
            PadGrid(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                captureMode = state.sourcePlaying,
                onTrigger = viewModel::triggerPad,
                onRelease = viewModel::releasePad,
                onSelect = viewModel::selectPad,
                gap = gap,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChopControlDeck(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    viewModel: SamplerViewModel,
    modifier: Modifier,
    flexibleWaveform: Boolean,
) {
    val audio = state.currentAudio
    val gap = metrics.gapDp.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.controlHeightDp.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MachineButton(
                label = "LOAD",
                onClick = onImportAudio,
                enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MachineButton(
                label = if (state.microphoneRecording) "MIC STOP" else "MIC REC",
                onClick = onToggleMicrophoneRecording,
                enabled = !state.systemAudioRecording,
                active = state.microphoneRecording,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            ConfirmActionButton(
                label = "CLEAR BANK",
                confirmLabel = "CONFIRM",
                onConfirm = viewModel::clearVisibleChops,
                enabled = audio != null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        SourceWaveform(
            audio = audio,
            pads = state.visiblePads(),
            playheadFrame = state.sourcePlayheadFrame,
            sampling = state.sourcePlaying,
            onSeek = viewModel::seekSourcePlayback,
            modifier = if (flexibleWaveform) {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(metrics.waveformHeightDp.dp)
            },
        )
        SourceReadout(state = state, height = 22.dp)
        TransportStrip(
            state = state,
            height = metrics.controlHeightDp.dp,
            onToggle = viewModel::toggleSourcePlayback,
            onPitchChange = viewModel::setMasterPitch,
        )
        BankStrip(
            selectedBank = state.selectedBank,
            height = metrics.controlHeightDp.dp,
            onSelectBank = viewModel::selectBank,
        )
    }
}

@Composable
private fun SourceReadout(state: SamplerUiState, height: Dp) {
    val audio = state.currentAudio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(DeckInk, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = when {
                state.sourcePlaying -> "SAMPLING"
                audio != null -> "READY"
                else -> "NO SOURCE"
            },
            color = if (state.sourcePlaying) DeckLamp else DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
        )
        Text(
            text = audio?.name ?: "Load or record audio",
            color = Color(0xFFE8DDBF),
            fontFamily = DeckFont,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${formatDeckTime(state.sourcePlayheadFrame, audio?.sampleRate)} / " +
                formatDeckTime(audio?.frameCount ?: 0, audio?.sampleRate),
            color = Color(0xFFB5A984),
            fontFamily = DeckFont,
            fontSize = 8.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun TransportStrip(
    state: SamplerUiState,
    height: Dp,
    onToggle: () -> Unit,
    onPitchChange: (Float) -> Unit,
) {
    val audioLoaded = state.currentAudio != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MachineButton(
            label = if (state.sourcePlaying) "STOP SOURCE" else "PLAY SOURCE",
            onClick = onToggle,
            enabled = audioLoaded,
            active = state.sourcePlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = "TUNE -",
            onClick = { onPitchChange(state.masterPitchSemitones - 1f) },
            enabled = audioLoaded,
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight(),
            compact = true,
        )
        ValueDisplay(
            label = "SRC",
            value = signedValue(state.masterPitchSemitones),
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
        )
        MachineButton(
            label = "TUNE +",
            onClick = { onPitchChange(state.masterPitchSemitones + 1f) },
            enabled = audioLoaded,
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun PadWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    page: PadEditorPage,
    onPageChange: (PadEditorPage) -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectBank,
                )
                PadGrid(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    onTrigger = viewModel::triggerPad,
                    onRelease = viewModel::releasePad,
                    onSelect = viewModel::selectPad,
                    gap = gap,
                    modifier = Modifier.weight(1f),
                )
            }
            PadEditor(
                state = state,
                page = page,
                onPageChange = onPageChange,
                viewModel = viewModel,
                controlHeight = metrics.controlHeightDp.dp,
                gap = gap,
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            BankStrip(
                selectedBank = state.selectedBank,
                height = metrics.controlHeightDp.dp,
                onSelectBank = viewModel::selectBank,
            )
            PadGrid(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                onTrigger = viewModel::triggerPad,
                onRelease = viewModel::releasePad,
                onSelect = viewModel::selectPad,
                gap = gap,
                modifier = Modifier.weight(1.15f),
            )
            PadEditor(
                state = state,
                page = page,
                onPageChange = onPageChange,
                viewModel = viewModel,
                controlHeight = metrics.controlHeightDp.dp,
                gap = gap,
                modifier = Modifier.weight(0.85f),
            )
        }
    }
}

@Composable
private fun PadEditor(
    state: SamplerUiState,
    page: PadEditorPage,
    onPageChange: (PadEditorPage) -> Unit,
    viewModel: SamplerViewModel,
    controlHeight: Dp,
    gap: Dp,
    modifier: Modifier,
) {
    val pad = state.selectedPadModel()
    Column(
        modifier = modifier
            .background(DeckPanelDark, PanelShape)
            .border(1.5.dp, DeckInk, PanelShape)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(controlHeight),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            ValueDisplay(
                label = "SELECTED",
                value = "${bankName(pad.bankIndex)}-%02d".format(pad.indexInBank + 1),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            PadEditorPage.entries.forEach { option ->
                MachineButton(
                    label = option.label,
                    onClick = { onPageChange(option) },
                    active = page == option,
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight(),
                    compact = true,
                )
            }
        }
        if (page == PadEditorPage.PARAM) {
            ParameterEditor(
                pad = pad,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            PlayModeEditor(
                pad = pad,
                viewModel = viewModel,
                controlHeight = controlHeight,
                gap = gap,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ParameterEditor(
    pad: PadModel,
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        MachineSlider(
            label = "PITCH",
            value = pad.pitchSemitones,
            valueRange = -24f..24f,
            valueLabel = "${signedValue(pad.pitchSemitones)} st",
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadPitch,
            modifier = Modifier.weight(1f),
        )
        MachineSlider(
            label = "TONE",
            value = pad.tone,
            valueRange = 0f..1f,
            valueLabel = "${(pad.tone * 100).toInt()}%",
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadTone,
            modifier = Modifier.weight(1f),
        )
        MachineSlider(
            label = "LEVEL",
            value = pad.gain,
            valueRange = 0f..1.5f,
            valueLabel = "${(pad.gain * 100).toInt()}%",
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadGain,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayModeEditor(
    pad: PadModel,
    viewModel: SamplerViewModel,
    controlHeight: Dp,
    gap: Dp,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MachineButton(
                label = "REVERSE",
                onClick = viewModel::toggleSelectedPadReverse,
                enabled = pad.isAssigned,
                active = pad.reverse,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MachineButton(
                label = if (pad.playMode == PadPlayMode.GATE) "GATE" else "ONE SHOT",
                onClick = viewModel::toggleSelectedPadPlayMode,
                enabled = pad.isAssigned,
                active = pad.playMode == PadPlayMode.GATE,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(5) { group ->
                MachineButton(
                    label = if (group == 0) "CHOKE OFF" else "CHOKE $group",
                    onClick = { viewModel.setSelectedPadChokeGroup(group) },
                    enabled = pad.isAssigned,
                    active = pad.chokeGroup == group,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    compact = true,
                )
            }
        }
        ConfirmActionButton(
            label = "CLEAR SELECTED PAD",
            confirmLabel = "CONFIRM PAD CLEAR",
            onConfirm = viewModel::clearSelectedPad,
            enabled = pad.isAssigned,
            modifier = Modifier
                .fillMaxWidth()
                .height(controlHeight),
        )
    }
}

@Composable
private fun SequenceWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectBank,
                )
                PadGrid(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    onTrigger = viewModel::triggerPad,
                    onRelease = viewModel::releasePad,
                    onSelect = viewModel::selectPad,
                    gap = gap,
                    modifier = Modifier.weight(1f),
                )
            }
            SequenceControlDeck(
                state = state,
                metrics = metrics,
                onExportBeat = onExportBeat,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                flexibleSteps = true,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            BankStrip(
                selectedBank = state.selectedBank,
                height = metrics.controlHeightDp.dp,
                onSelectBank = viewModel::selectBank,
            )
            SequenceTransportRow(
                state = state,
                height = metrics.controlHeightDp.dp,
                gap = gap,
                viewModel = viewModel,
            )
            PadGrid(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                onTrigger = viewModel::triggerPad,
                onRelease = viewModel::releasePad,
                onSelect = viewModel::selectPad,
                gap = gap,
                modifier = Modifier.weight(1f),
            )
            TempoRow(
                state = state,
                height = metrics.controlHeightDp.dp,
                viewModel = viewModel,
            )
            StepSequencer(
                selectedPad = state.selectedPad,
                activeSteps = state.activeSteps,
                currentStep = state.currentStep,
                onToggleStep = viewModel::toggleStep,
                columns = 8,
                gap = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (metrics.density == DeckDensity.COMPACT) 84.dp else 96.dp),
            )
            SequenceActionRow(
                state = state,
                height = metrics.controlHeightDp.dp,
                gap = gap,
                onExportBeat = onExportBeat,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun SequenceControlDeck(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
    modifier: Modifier,
    flexibleSteps: Boolean,
) {
    val gap = metrics.gapDp.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SequenceTransportRow(
            state = state,
            height = metrics.controlHeightDp.dp,
            gap = gap,
            viewModel = viewModel,
        )
        TempoRow(
            state = state,
            height = metrics.controlHeightDp.dp,
            viewModel = viewModel,
        )
        if (metrics.density != DeckDensity.COMPACT) {
            ValueDisplay(
                label = "EDITING",
                value = "PAD ${bankName(state.selectedBank)}-%02d".format(state.selectedPadModel().indexInBank + 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
            )
        }
        StepSequencer(
            selectedPad = state.selectedPad,
            activeSteps = state.activeSteps,
            currentStep = state.currentStep,
            onToggleStep = viewModel::toggleStep,
            columns = 8,
            gap = 4.dp,
            modifier = if (flexibleSteps) Modifier.weight(1f) else Modifier,
        )
        SequenceActionRow(
            state = state,
            height = metrics.controlHeightDp.dp,
            gap = gap,
            onExportBeat = onExportBeat,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun SequenceTransportRow(
    state: SamplerUiState,
    height: Dp,
    gap: Dp,
    viewModel: SamplerViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MachineButton(
            label = if (state.transportPlaying) "STOP BEAT" else "PLAY BEAT",
            onClick = viewModel::toggleTransport,
            active = state.transportPlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.recordArmed) "REC ARMED" else "REC ARM",
            onClick = viewModel::toggleRecordArm,
            active = state.recordArmed,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        ConfirmActionButton(
            label = "CLEAR PAD STEPS",
            confirmLabel = "CONFIRM",
            onConfirm = viewModel::clearSelectedPadPattern,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun TempoRow(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StepperControl(
            label = "BPM",
            value = state.bpm.toInt().toString(),
            onDecrease = { viewModel.setBpm(state.bpm - 1f) },
            onIncrease = { viewModel.setBpm(state.bpm + 1f) },
            modifier = Modifier.weight(1f),
        )
        StepperControl(
            label = "SWING",
            value = "${state.swing.toInt()}%",
            onDecrease = { viewModel.setSwing(state.swing - 1f) },
            onIncrease = { viewModel.setSwing(state.swing + 1f) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SequenceActionRow(
    state: SamplerUiState,
    height: Dp,
    gap: Dp,
    onExportBeat: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val hasAudibleSteps = state.activeSteps.any { key ->
        val padIndex = key / SamplerConfig.STEP_COUNT
        state.pads.getOrNull(padIndex)?.isAssigned == true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MachineButton(
            label = "EXPORT 4 BARS",
            onClick = onExportBeat,
            enabled = hasAudibleSteps && !state.isLoading,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        ConfirmActionButton(
            label = "CLEAR PATTERN",
            confirmLabel = "CONFIRM ALL",
            onConfirm = viewModel::clearAllPattern,
            enabled = state.activeSteps.isNotEmpty(),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun SourceWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            SourceEditorWaveform(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
            )
            SourceControlDeck(
                state = state,
                metrics = metrics,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            SourceCaptureRow(
                state = state,
                height = metrics.controlHeightDp.dp,
                gap = gap,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
            )
            SourceReadout(state = state, height = 24.dp)
            SourceEditorWaveform(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            SourceEditRows(
                state = state,
                metrics = metrics,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun SourceControlDeck(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    val gap = metrics.gapDp.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SourceCaptureRow(
            state = state,
            height = metrics.controlHeightDp.dp,
            gap = gap,
            onImportAudio = onImportAudio,
            onToggleMicrophoneRecording = onToggleMicrophoneRecording,
            onToggleSystemAudioRecording = onToggleSystemAudioRecording,
        )
        if (metrics.density != DeckDensity.COMPACT) {
            SourceReadout(state = state, height = 28.dp)
        }
        SourceEditRows(
            state = state,
            metrics = metrics,
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
            weighted = true,
        )
    }
}

@Composable
private fun SourceCaptureRow(
    state: SamplerUiState,
    height: Dp,
    gap: Dp,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MachineButton(
            label = "LOAD FILE",
            onClick = onImportAudio,
            enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.microphoneRecording) "MIC STOP" else "MIC REC",
            onClick = onToggleMicrophoneRecording,
            enabled = !state.systemAudioRecording,
            active = state.microphoneRecording,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.systemAudioRecording) "DEVICE STOP" else "DEVICE REC",
            onClick = onToggleSystemAudioRecording,
            enabled = !state.microphoneRecording,
            active = state.systemAudioRecording,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun SourceEditorWaveform(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    val audio = state.currentAudio
    MachinePanel(modifier = modifier) {
        if (audio == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "NO SOURCE\nLOAD OR RECORD AUDIO",
                    color = Color(0xFF766B50),
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
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
                fillCanvas = true,
                showViewportControls = false,
                compactViewportControls = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SourceEditRows(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    viewModel: SamplerViewModel,
    modifier: Modifier = Modifier,
    weighted: Boolean = false,
) {
    val gap = metrics.gapDp.dp
    val audioEnabled = state.currentAudio != null
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SourceToolRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (weighted) Modifier.weight(1f)
                    else Modifier.height(metrics.controlHeightDp.dp),
                ),
        ) {
            MachineButton("PREVIEW", viewModel::previewCurrentSelection, Modifier.weight(1f).fillMaxHeight(), audioEnabled)
            MachineButton("MANUAL", viewModel::toggleManualChop, Modifier.weight(1f).fillMaxHeight(), audioEnabled, state.manualChopEnabled)
            MachineButton("RESET RANGE", viewModel::resetRange, Modifier.weight(1f).fillMaxHeight(), audioEnabled)
        }
        SourceToolRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (weighted) Modifier.weight(1f)
                    else Modifier.height(metrics.controlHeightDp.dp),
                ),
        ) {
            listOf(4, 8, 16).forEach { count ->
                MachineButton(
                    label = "$count SLICE",
                    onClick = { viewModel.autoChopEqual(count) },
                    enabled = audioEnabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
            MachineButton(
                label = "TRANSIENT",
                onClick = viewModel::autoChopTransient,
                enabled = audioEnabled,
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                compact = true,
            )
        }
        SourceToolRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (weighted) Modifier.weight(1f)
                    else Modifier.height(metrics.controlHeightDp.dp),
                ),
        ) {
            MachineButton("ASSIGN", viewModel::assignCurrentSelectionToPad, Modifier.weight(1f).fillMaxHeight(), audioEnabled)
            MachineButton("ASSIGN ALL", viewModel::assignAllSlicesToPads, Modifier.weight(1f).fillMaxHeight(), audioEnabled)
            MachineButton(
                label = if (state.autoNextPad) "AUTO NEXT ON" else "AUTO NEXT OFF",
                onClick = viewModel::toggleAutoNext,
                enabled = audioEnabled,
                active = state.autoNextPad,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
        SourceToolRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (weighted) Modifier.weight(1f)
                    else Modifier.height(metrics.controlHeightDp.dp),
                ),
        ) {
            ValueDisplay(
                label = "SLICES",
                value = state.sliceRanges().size.toString(),
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
            )
            MachineButton(
                label = "REMOVE BOUNDARY",
                onClick = viewModel::removeBoundaryForActiveSlice,
                enabled = state.sliceMarkers.isNotEmpty(),
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
                compact = true,
            )
            ConfirmActionButton(
                label = "CLEAR MARKERS",
                confirmLabel = "CONFIRM",
                onConfirm = viewModel::clearSliceMarkers,
                enabled = state.sliceMarkers.isNotEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SourceToolRow(
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
private fun BankStrip(
    selectedBank: Int,
    height: Dp,
    onSelectBank: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(SamplerConfig.BANK_COUNT) { bank ->
            MachineButton(
                label = "BANK ${bankName(bank)}",
                onClick = { onSelectBank(bank) },
                active = selectedBank == bank,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun ConsoleStatusStrip(
    state: SamplerUiState,
    mode: ConsoleMode,
    height: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(DeckInk, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusLamp(
            active = state.isLoading || state.sourcePlaying || state.transportPlaying,
            alert = state.microphoneRecording || state.systemAudioRecording,
        )
        Text(
            text = mode.label,
            color = DeckLamp,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
        )
        Text(
            text = state.statusMessage,
            color = Color(0xFFE8DDBF),
            fontFamily = DeckFont,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "状態: ${state.statusMessage}" },
        )
    }
}

@Composable
private fun MachinePanel(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color(0xFF17130D), PanelShape)
            .border(1.5.dp, DeckInk, PanelShape)
            .padding(5.dp),
    ) {
        content()
    }
}

@Composable
private fun MachineSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            color = DeckInk,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
            modifier = Modifier.width(50.dp),
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
            color = DeckInk.copy(alpha = if (enabled) 1f else 0.4f),
            fontFamily = DeckFont,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

@Composable
private fun StepperControl(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MachineButton(
            label = "-",
            onClick = onDecrease,
            modifier = Modifier.width(40.dp).fillMaxHeight(),
            compact = true,
        )
        ValueDisplay(
            label = label,
            value = value,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        MachineButton(
            label = "+",
            onClick = onIncrease,
            modifier = Modifier.width(40.dp).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun ValueDisplay(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(DeckInk, RoundedCornerShape(5.dp))
            .border(1.dp, Color.Black, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color(0xFF958967),
            fontFamily = DeckFont,
            fontSize = 6.sp,
            maxLines = 1,
        )
        Text(
            value,
            color = DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConfirmActionButton(
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var armed by remember { mutableStateOf(false) }
    MachineButton(
        label = if (armed) confirmLabel else label,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        enabled = enabled,
        active = armed,
        modifier = modifier,
        compact = true,
    )
}

@Composable
private fun MachineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        pressed -> DeckPadLit
        active -> DeckLamp
        else -> DeckPanelDark
    }
    val foreground = if (pressed || active) Color(0xFF2A1000) else DeckInk
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = if (enabled && !pressed) 2.dp else 0.dp,
        modifier = modifier
            .graphicsLayer { translationY = if (pressed) 1.5.dp.toPx() else 0f }
            .alpha(if (enabled) 1f else 0.38f)
            .border(1.5.dp, DeckInk, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = label
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = foreground,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = if (compact) 8.sp else 10.sp,
                lineHeight = if (compact) 9.sp else 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
private fun SourceWaveform(
    audio: PcmAudio?,
    pads: List<PadModel>,
    playheadFrame: Int,
    sampling: Boolean,
    onSeek: (Int) -> Unit,
    modifier: Modifier,
) {
    val peaks = remember(audio?.id) { audio?.let(::buildDeckPeaks) }
    Box(
        modifier = modifier
            .clip(PanelShape)
            .background(Color(0xFF0B0906))
            .border(if (sampling) 3.dp else 1.5.dp, if (sampling) DeckLamp else DeckInk, PanelShape),
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
                .semantics { contentDescription = "ソース波形。タップで再生位置を移動" },
        ) {
            val source = audio ?: return@Canvas
            val values = peaks ?: return@Canvas
            val center = size.height / 2f
            val amplitude = size.height * 0.43f
            values.forEachIndexed { index, peak ->
                val x = index.toFloat() / max(1, values.lastIndex) * size.width
                drawLine(
                    color = DeckGreen,
                    start = androidx.compose.ui.geometry.Offset(x, center - peak.second * amplitude),
                    end = androidx.compose.ui.geometry.Offset(x, center - peak.first * amplitude),
                    strokeWidth = 1.2f,
                )
            }

            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(26, 13, 0)
                textSize = 9.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            pads.filter { it.isAssigned && it.audio?.id == source.id }
                .sortedBy(PadModel::startFrame)
                .forEach { pad ->
                    val x = pad.startFrame.toFloat() / source.frameCount.coerceAtLeast(1) * size.width
                    drawLine(
                        DeckLamp,
                        androidx.compose.ui.geometry.Offset(x, 12.dp.toPx()),
                        androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = 1.5f,
                    )
                    drawRect(
                        color = DeckLamp,
                        topLeft = androidx.compose.ui.geometry.Offset((x - 1.dp.toPx()).coerceAtLeast(0f), 0f),
                        size = androidx.compose.ui.geometry.Size(17.dp.toPx(), 12.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "%02d".format(pad.indexInBank + 1),
                        x + 1.dp.toPx(),
                        9.dp.toPx(),
                        markerPaint,
                    )
                }

            val headX = playheadFrame.coerceIn(0, source.frameCount).toFloat() /
                source.frameCount.coerceAtLeast(1) * size.width
            drawLine(
                color = if (sampling) Color(0xFFFFF0D0) else Color(0xFFA89A78),
                start = androidx.compose.ui.geometry.Offset(headX, 0f),
                end = androidx.compose.ui.geometry.Offset(headX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        if (audio == null) {
            Text(
                "NO SOURCE",
                color = Color(0xFF766B50),
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun buildDeckPeaks(audio: PcmAudio): List<Pair<Float, Float>> {
    val width = 640
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

private fun bankName(index: Int): String =
    ('A'.code + index.coerceIn(0, SamplerConfig.BANK_COUNT - 1)).toChar().toString()

private fun signedValue(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun formatDeckTime(frame: Int, sampleRate: Int?): String {
    val rate = sampleRate?.takeIf { it > 0 } ?: return "0:00.0"
    val seconds = frame.coerceAtLeast(0).toDouble() / rate
    val minutes = (seconds / 60.0).toInt()
    val remainder = seconds - minutes * 60.0
    return "%d:%04.1f".format(minutes, remainder)
}
