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

private enum class PadEditorPage(val label: String) {
    PARAM("音づくり\nPARAM"),
    PLAY("鳴り方\nPLAY"),
}

@Composable
fun OtohiroiDeck(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onExportBeat: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    viewModel: SamplerViewModel,
) {
    var stageName by rememberSaveable {
        mutableStateOf(initialWorkflowStage(state.currentAudio != null).name)
    }
    var padPageName by rememberSaveable { mutableStateOf(PadEditorPage.PARAM.name) }
    var showPadDetails by rememberSaveable { mutableStateOf(false) }
    val stage = restoreWorkflowStage(stageName)
    val padPage = PadEditorPage.entries.firstOrNull { it.name == padPageName } ?: PadEditorPage.PARAM

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
                        stage = stage,
                        height = metrics.headerHeightDp.dp,
                        onStopAll = viewModel::stopAllSounds,
                    )
                    WorkflowStrip(
                        selected = stage,
                        height = metrics.modeBarHeightDp.dp,
                        compact = metrics.density == DeckDensity.COMPACT,
                        onSelect = {
                            stageName = it.name
                            if (it != WorkflowStage.PLAY) showPadDetails = false
                        },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        when (stage) {
                            WorkflowStage.CAPTURE -> CaptureWorkspace(
                                state = state,
                                metrics = metrics,
                                onImportAudio = onImportAudio,
                                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                                onContinue = { stageName = WorkflowStage.PLAY.name },
                                viewModel = viewModel,
                            )
                            WorkflowStage.SLICE -> SourceWorkspace(
                                state = state,
                                metrics = metrics,
                                onImportAudio = onImportAudio,
                                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                                viewModel = viewModel,
                            )
                            WorkflowStage.PLAY -> if (showPadDetails) {
                                PadWorkspace(
                                    state = state,
                                    metrics = metrics,
                                    page = padPage,
                                    onPageChange = { padPageName = it.name },
                                    onReturn = { showPadDetails = false },
                                    viewModel = viewModel,
                                )
                            } else {
                                ChopWorkspace(
                                    state = state,
                                    metrics = metrics,
                                    onImportAudio = onImportAudio,
                                    onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                                    onOpenDetails = { showPadDetails = true },
                                    viewModel = viewModel,
                                )
                            }
                            WorkflowStage.ARRANGE -> SequenceWorkspace(
                                state = state,
                                metrics = metrics,
                                viewModel = viewModel,
                            )
                            WorkflowStage.FINISH -> FinishWorkspace(
                                state = state,
                                metrics = metrics,
                                onExportBeat = onExportBeat,
                                onOpenProject = onOpenProject,
                                onSaveProject = onSaveProject,
                                onBackToArrange = { stageName = WorkflowStage.ARRANGE.name },
                                viewModel = viewModel,
                            )
                        }
                    }
                    if (metrics.showStatusStrip) {
                        ConsoleStatusStrip(
                            state = state,
                            stage = stage,
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
    stage: WorkflowStage,
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
                if (state.statusMessage.isBlank()) "PRO MOBILE SAMPLER / ${stage.caption}"
                else "${stage.label} / ${state.statusMessage}",
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
private fun WorkflowStrip(
    selected: WorkflowStage,
    height: Dp,
    compact: Boolean,
    onSelect: (WorkflowStage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        WorkflowStage.entries.forEachIndexed { index, stage ->
            WorkflowStageButton(
                number = index + 1,
                stage = stage,
                selected = selected == stage,
                compact = compact,
                onClick = { onSelect(stage) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun WorkflowStageButton(
    number: Int,
    stage: WorkflowStage,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        pressed -> DeckPadLit
        selected -> DeckLamp
        else -> DeckPanelDark
    }
    Surface(
        color = background,
        contentColor = DeckInk,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = if (pressed) 0.dp else 2.dp,
        modifier = modifier
            .border(1.5.dp, DeckInk, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                contentDescription = "工程$number ${stage.label} ${stage.caption}"
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$number ${stage.label}",
                color = DeckInk,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = if (compact) 8.sp else 10.sp,
                maxLines = 1,
            )
            if (!compact) {
                Text(
                    text = stage.caption,
                    color = DeckInk.copy(alpha = 0.62f),
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 6.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CaptureWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    onContinue: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    val audio = state.currentAudio
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                SourceWaveform(
                    audio = audio,
                    pads = state.visiblePads(),
                    playheadFrame = state.sourcePlayheadFrame,
                    sampling = state.sourcePlaying,
                    onSeek = viewModel::seekSourcePlayback,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                SourceReadout(state = state, height = 24.dp)
            }
            Column(
                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                CaptureChoicePanel(
                    state = state,
                    onImportAudio = onImportAudio,
                    onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                    onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                    modifier = Modifier.weight(1f),
                )
                TransportStrip(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    onToggle = viewModel::toggleSourcePlayback,
                    onPitchChange = viewModel::setMasterPitch,
                )
                MachineButton(
                    label = "音が入ったら叩くへ\nNEXT: PLAY",
                    onClick = onContinue,
                    enabled = audio != null,
                    active = audio != null,
                    modifier = Modifier.fillMaxWidth().height(metrics.controlHeightDp.dp),
                    compact = true,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            CaptureChoicePanel(
                state = state,
                onImportAudio = onImportAudio,
                onToggleMicrophoneRecording = onToggleMicrophoneRecording,
                onToggleSystemAudioRecording = onToggleSystemAudioRecording,
                modifier = Modifier.fillMaxWidth().height(
                    if (metrics.density == DeckDensity.COMPACT) 92.dp else 110.dp,
                ),
            )
            SourceReadout(state = state, height = 24.dp)
            SourceWaveform(
                audio = audio,
                pads = state.visiblePads(),
                playheadFrame = state.sourcePlayheadFrame,
                sampling = state.sourcePlaying,
                onSeek = viewModel::seekSourcePlayback,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            TransportStrip(
                state = state,
                height = metrics.controlHeightDp.dp,
                onToggle = viewModel::toggleSourcePlayback,
                onPitchChange = viewModel::setMasterPitch,
            )
            MachineButton(
                label = "音が入ったら叩くへ  /  NEXT: PLAY",
                onClick = onContinue,
                enabled = audio != null,
                active = audio != null,
                modifier = Modifier.fillMaxWidth().height(metrics.controlHeightDp.dp),
                compact = true,
            )
        }
    }
}

@Composable
private fun CaptureChoicePanel(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleSystemAudioRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MachinePanel(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "1. 音を入れる",
                color = DeckGreen,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = "ファイル、マイク、端末音声から1つ選びます",
                color = Color(0xFFE8DDBF),
                fontFamily = DeckFont,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MachineButton(
                    label = "曲を読込\nFILE",
                    onClick = onImportAudio,
                    enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = if (state.microphoneRecording) "録音を止める\nMIC STOP" else "マイク録音\nMIC REC",
                    onClick = onToggleMicrophoneRecording,
                    enabled = !state.systemAudioRecording,
                    active = state.microphoneRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = if (state.systemAudioRecording) "録音を止める\nDEVICE STOP" else "端末を録音\nDEVICE REC",
                    onClick = onToggleSystemAudioRecording,
                    enabled = !state.microphoneRecording,
                    active = state.systemAudioRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun ChopWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onOpenDetails: () -> Unit,
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
            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                PadGrid(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    captureMode = state.sourcePlaying,
                    onTrigger = viewModel::triggerPad,
                    onRelease = viewModel::releasePad,
                    onSelect = viewModel::selectPad,
                    gap = gap,
                    modifier = Modifier.weight(1f),
                )
                SelectedPadQuickEditor(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    expanded = false,
                    onOpenDetails = onOpenDetails,
                    viewModel = viewModel,
                )
            }
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
            SelectedPadQuickEditor(
                state = state,
                height = metrics.controlHeightDp.dp,
                expanded = metrics.density == DeckDensity.REGULAR,
                onOpenDetails = onOpenDetails,
                viewModel = viewModel,
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
                label = "曲を読込\nLOAD",
                onClick = onImportAudio,
                enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MachineButton(
                label = if (state.microphoneRecording) "録音を止める\nMIC STOP" else "マイク録音\nMIC REC",
                onClick = onToggleMicrophoneRecording,
                enabled = !state.systemAudioRecording,
                active = state.microphoneRecording,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            ConfirmActionButton(
                label = "PADを空に\nCLEAR BANK",
                confirmLabel = "もう一度で削除",
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
            text = when {
                state.sourcePlaying -> "ここだと思ったらPADを押すと、その瞬間が入ります"
                audio != null -> audio.name
                else -> "Load or record audio"
            },
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
            label = if (state.sourcePlaying) "曲を止める\nSTOP" else "曲を再生\nPLAY SONG",
            onClick = onToggle,
            enabled = audioLoaded,
            active = state.sourcePlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = "曲キー -",
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
            label = "曲キー +",
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
private fun SelectedPadQuickEditor(
    state: SamplerUiState,
    height: Dp,
    expanded: Boolean,
    onOpenDetails: (() -> Unit)?,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (expanded) height * 2 + 4.dp else height),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (expanded) {
                ValueDisplay(
                    label = "選択PAD",
                    value = "${bankName(pad.bankIndex)}-%02d".format(pad.indexInBank + 1),
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                )
            }
            MachineButton(
                label = "KEY -",
                onClick = { viewModel.setSelectedPadPitch(pad.pitchSemitones - 1f) },
                enabled = pad.isAssigned,
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                compact = true,
            )
            ValueDisplay(
                label = "KEY ${semitoneLabel(pad.pitchSemitones)}",
                value = semitoneToKeyName(pad.pitchSemitones),
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
            )
            MachineButton(
                label = "KEY +",
                onClick = { viewModel.setSelectedPadPitch(pad.pitchSemitones + 1f) },
                enabled = pad.isAssigned,
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                compact = true,
            )
            if (!expanded) {
                QuickCycleButton(
                    label = "音色",
                    percent = (pad.tone * 100).toInt(),
                    enabled = pad.isAssigned,
                    onClick = {
                        val next = if (pad.tone >= 0.99f) 0f else (pad.tone + 0.1f).coerceAtMost(1f)
                        viewModel.setSelectedPadTone(next)
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                QuickCycleButton(
                    label = "音量",
                    percent = (pad.gain * 100).toInt(),
                    enabled = pad.isAssigned,
                    onClick = {
                        val next = if (pad.gain >= 1.49f) 0f else (pad.gain + 0.1f).coerceAtMost(1.5f)
                        viewModel.setSelectedPadGain(next)
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (onOpenDetails != null) {
                MachineButton(
                    label = "詳細\nEDIT",
                    onClick = onOpenDetails,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().height(height),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MachineSlider(
                    label = "音色",
                    value = pad.tone,
                    valueRange = 0f..1f,
                    valueLabel = "${(pad.tone * 100).toInt()}%",
                    enabled = pad.isAssigned,
                    onValueChange = viewModel::setSelectedPadTone,
                    modifier = Modifier.weight(1f),
                )
                MachineSlider(
                    label = "音量",
                    value = pad.gain,
                    valueRange = 0f..1.5f,
                    valueLabel = "${(pad.gain * 100).toInt()}%",
                    enabled = pad.isAssigned,
                    onValueChange = viewModel::setSelectedPadGain,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuickCycleButton(
    label: String,
    percent: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MachineButton(
        label = "$label\n$percent%",
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        compact = true,
    )
}

private fun semitoneLabel(value: Float): String = "(${signedValue(value)})"

@Composable
private fun PadWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    page: PadEditorPage,
    onPageChange: (PadEditorPage) -> Unit,
    onReturn: () -> Unit,
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
                onReturn = onReturn,
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
                onReturn = onReturn,
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
    onReturn: () -> Unit,
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
            MachineButton(
                label = "叩くへ戻る\nBACK",
                onClick = onReturn,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                compact = true,
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
            label = "音程\nPITCH",
            value = pad.pitchSemitones,
            valueRange = -24f..24f,
            valueLabel = "${signedValue(pad.pitchSemitones)} st",
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadPitch,
            modifier = Modifier.weight(1f),
        )
        MachineSlider(
            label = "音色\nTONE",
            value = pad.tone,
            valueRange = 0f..1f,
            valueLabel = "${(pad.tone * 100).toInt()}%",
            enabled = pad.isAssigned,
            onValueChange = viewModel::setSelectedPadTone,
            modifier = Modifier.weight(1f),
        )
        MachineSlider(
            label = "音量\nLEVEL",
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
                label = "逆再生\nREVERSE",
                onClick = viewModel::toggleSelectedPadReverse,
                enabled = pad.isAssigned,
                active = pad.reverse,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MachineButton(
                label = if (pad.playMode == PadPlayMode.GATE) "押す間だけ\nGATE" else "一回鳴る\nONE SHOT",
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
                    label = if (group == 0) "同時停止なし\nOFF" else "同時停止 $group\nCHOKE",
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
            label = "このPADを空に\nCLEAR PAD",
            confirmLabel = "もう一度で削除",
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
            if (metrics.density == DeckDensity.REGULAR) {
                BeginnerCoachBar(
                    text = "光るマスで音が鳴ります。まず 1・5・9・13 を押そう",
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
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
            SelectedPadQuickEditor(
                state = state,
                height = metrics.controlHeightDp.dp,
                expanded = metrics.density == DeckDensity.REGULAR,
                onOpenDetails = null,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun BeginnerCoachBar(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(DeckInk, RoundedCornerShape(5.dp))
            .border(1.dp, Color.Black, RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "TIP",
            color = DeckLamp,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
        )
        Text(
            text = text,
            color = DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SequenceControlDeck(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
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
        SelectedPadQuickEditor(
            state = state,
            height = metrics.controlHeightDp.dp,
            expanded = false,
            onOpenDetails = null,
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
            label = if (state.transportPlaying) "ビート停止\nSTOP" else "ビート再生\nPLAY",
            onClick = viewModel::toggleTransport,
            active = state.transportPlaying,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.recordArmed) "演奏を記録中\nREC ON" else "演奏を記録\nREC",
            onClick = viewModel::toggleRecordArm,
            active = state.recordArmed,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        ConfirmActionButton(
            label = "この音を消す\nCLEAR STEPS",
            confirmLabel = "もう一度で削除",
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
private fun FinishWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onExportBeat: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onBackToArrange: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    val assignedPads = state.pads.count(PadModel::isAssigned)
    val audibleSteps = state.activeSteps.count { key ->
        val padIndex = key / SamplerConfig.STEP_COUNT
        state.pads.getOrNull(padIndex)?.isAssigned == true
    }
    val ready = audibleSteps > 0
    val summary: @Composable (Modifier) -> Unit = { modifier ->
        MachinePanel(modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Text(
                    text = if (ready) "ビートを書き出せます" else "あと少し。鳴らす場所を並べましょう",
                    color = if (ready) DeckGreen else DeckLamp,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Black,
                    fontSize = if (metrics.density == DeckDensity.COMPACT) 12.sp else 16.sp,
                    maxLines = 2,
                )
                Text(
                    text = if (ready) {
                        "操作は端末内へ自動保存。再生で確認し、4小節WAVにもできます。"
                    } else {
                        "操作は端末内へ自動保存。『並べる』で鳴らすマスを光らせてください。"
                    },
                    color = Color(0xFFE8DDBF),
                    fontFamily = DeckFont,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    ValueDisplay(
                        label = "使えるPAD",
                        value = "$assignedPads / ${SamplerConfig.PAD_COUNT}",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ValueDisplay(
                        label = "鳴るマス",
                        value = audibleSteps.toString(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ValueDisplay(
                        label = "テンポ",
                        value = "${state.bpm.toInt()} BPM",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ValueDisplay(
                        label = "再生状態",
                        value = if (state.transportPlaying) "再生中" else "停止中",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }

    val actions: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            MachineButton(
                label = if (state.transportPlaying) "確認を止める\nSTOP" else "ビートを確認\nPLAY BEAT",
                onClick = viewModel::toggleTransport,
                enabled = ready,
                active = state.transportPlaying,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            MachineButton(
                label = "WAVを書き出す\nEXPORT 4 BARS",
                onClick = onExportBeat,
                enabled = ready && !state.isLoading,
                active = ready,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                MachineButton(
                    label = "制作を保存\nSAVE PROJECT",
                    onClick = onSaveProject,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = "制作を開く\nOPEN PROJECT",
                    onClick = onOpenProject,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                MachineButton(
                    label = "1つ戻す\nUNDO",
                    onClick = viewModel::undoEdit,
                    enabled = state.canUndo && !state.isLoading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = "やり直す\nREDO",
                    onClick = viewModel::redoEdit,
                    enabled = state.canRedo && !state.isLoading,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                MachineButton(
                    label = "並べるへ戻る\nBACK",
                    onClick = onBackToArrange,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                ConfirmActionButton(
                    label = "全部消す\nCLEAR",
                    confirmLabel = "もう一度で削除",
                    onConfirm = viewModel::clearAllPattern,
                    enabled = state.activeSteps.isNotEmpty(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }

    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            summary(Modifier.weight(1.2f).fillMaxHeight())
            actions(Modifier.weight(0.8f).fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            summary(Modifier.fillMaxWidth().weight(1f))
            actions(
                Modifier
                    .fillMaxWidth()
                    .height(if (metrics.density == DeckDensity.COMPACT) 202.dp else 238.dp),
            )
        }
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
            label = "曲を読込\nLOAD FILE",
            onClick = onImportAudio,
            enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.microphoneRecording) "録音を止める\nMIC STOP" else "マイク録音\nMIC REC",
            onClick = onToggleMicrophoneRecording,
            enabled = !state.systemAudioRecording,
            active = state.microphoneRecording,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        MachineButton(
            label = if (state.systemAudioRecording) "録音を止める\nDEVICE STOP" else "端末を録音\nDEVICE REC",
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
            MachineButton("選んだ音を聴く\nPREVIEW", viewModel::previewCurrentSelection, Modifier.weight(1f).fillMaxHeight(), audioEnabled, compact = true)
            MachineButton("手で切る\nMANUAL", viewModel::toggleManualChop, Modifier.weight(1f).fillMaxHeight(), audioEnabled, state.manualChopEnabled, compact = true)
            MachineButton("範囲を戻す\nRESET", viewModel::resetRange, Modifier.weight(1f).fillMaxHeight(), audioEnabled, compact = true)
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
                    label = "$count 分割\n$count SLICE",
                    onClick = { viewModel.autoChopEqual(count) },
                    enabled = audioEnabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
            MachineButton(
                label = "音を自動検出\nTRANSIENT",
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
            MachineButton("選択をPADへ\nASSIGN", viewModel::assignCurrentSelectionToPad, Modifier.weight(1f).fillMaxHeight(), audioEnabled, compact = true)
            MachineButton("全部PADへ\nASSIGN ALL", viewModel::assignAllSlicesToPads, Modifier.weight(1f).fillMaxHeight(), audioEnabled, compact = true)
            MachineButton(
                label = if (state.autoNextPad) "次のPADへ ON\nAUTO NEXT" else "次のPADへ OFF\nAUTO NEXT",
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
                label = "分割数",
                value = state.sliceRanges().size.toString(),
                modifier = Modifier.weight(0.7f).fillMaxHeight(),
            )
            MachineButton(
                label = "境界を1つ消す\nREMOVE",
                onClick = viewModel::removeBoundaryForActiveSlice,
                enabled = state.sliceMarkers.isNotEmpty(),
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
                compact = true,
            )
            ConfirmActionButton(
                label = "境界を全部消す\nCLEAR",
                confirmLabel = "もう一度で削除",
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
    stage: WorkflowStage,
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
            text = stage.label,
            color = DeckLamp,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
        )
        Text(
            text = "${stage.guidance}  /  ${state.statusMessage}",
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
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MachineButton(
            label = "-",
            onClick = onDecrease,
            enabled = enabled,
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
            enabled = enabled,
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
