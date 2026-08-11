package com.choplab.sampler.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.sampler.SamplerViewModel
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.assignedPadCountOnPage
import com.choplab.sampler.model.bankRoleFor
import com.choplab.sampler.model.repeatGridForPad
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.selectedPadPage
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.visiblePads
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
private val PlacementPresetChoices = listOf(
    RepeatGrid.QUARTER to "4つ打ち\n1拍ごと",
    RepeatGrid.EIGHTH to "8分\n半拍ごと",
    RepeatGrid.SIXTEENTH to "16分\n細かく",
)

private enum class PadEditorPage(val label: String) {
    PARAM("音づくり\nPARAM"),
    PLAY("鳴り方\nPLAY"),
}

private enum class ChopStageMode(val label: String) {
    CUT("切る\nEDIT CHOPS"),
    PADS("鳴らす\nPLAY PADS"),
}

private enum class LayerStudioPage(val label: String) {
    SOUNDS("SOUNDS\n音を重ねる"),
    DRUMS("DRUMS\nドラム"),
    VOICE("VOICE\n声を録る"),
    SCRATCH("SCRATCH\nこする"),
}

@Composable
fun OtohiroiDeck(
    state: SamplerUiState,
    onImportAudio: () -> Unit,
    onToggleMicrophoneRecording: () -> Unit,
    onToggleVocalRecording: () -> Unit,
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
    var chopModeName by rememberSaveable { mutableStateOf(ChopStageMode.CUT.name) }
    var showPadDetails by rememberSaveable { mutableStateOf(false) }
    var showLayerStudio by rememberSaveable { mutableStateOf(false) }
    val stage = restoreWorkflowStage(stageName)
    val padPage = PadEditorPage.entries.firstOrNull { it.name == padPageName } ?: PadEditorPage.PARAM
    val chopMode = ChopStageMode.entries.firstOrNull { it.name == chopModeName } ?: ChopStageMode.CUT

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
                            if (it != WorkflowStage.CHOP) showPadDetails = false
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
                                onContinue = { stageName = WorkflowStage.CHOP.name },
                                viewModel = viewModel,
                            )
                            WorkflowStage.CHOP -> if (showPadDetails) {
                                PadWorkspace(
                                    state = state,
                                    metrics = metrics,
                                    page = padPage,
                                    onPageChange = { padPageName = it.name },
                                    onReturn = { showPadDetails = false },
                                    viewModel = viewModel,
                                )
                            } else {
                                ChopStageWorkspace(
                                    state = state,
                                    metrics = metrics,
                                    mode = chopMode,
                                    onModeChange = { chopModeName = it.name },
                                    onOpenDetails = { showPadDetails = true },
                                    viewModel = viewModel,
                                )
                            }
                            WorkflowStage.BEAT -> SequenceWorkspace(
                                state = state,
                                metrics = metrics,
                                onOpenLayerCapture = { showLayerStudio = true },
                                viewModel = viewModel,
                            )
                            WorkflowStage.FINISH -> FinishWorkspace(
                                state = state,
                                metrics = metrics,
                                onExportBeat = onExportBeat,
                                onOpenProject = onOpenProject,
                                onSaveProject = onSaveProject,
                                onBackToArrange = { stageName = WorkflowStage.BEAT.name },
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
        if (showLayerStudio) {
            LayerStudio(
                state = state,
                onDismiss = { showLayerStudio = false },
                onToggleVocalRecording = onToggleVocalRecording,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun ChopStageWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    mode: ChopStageMode,
    onModeChange: (ChopStageMode) -> Unit,
    onOpenDetails: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    var liveChopArmed by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (metrics.density == DeckDensity.COMPACT) 34.dp else 40.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            ChopStageMode.entries.forEach { candidate ->
                MachineButton(
                    label = candidate.label,
                    onClick = { onModeChange(candidate) },
                    active = mode == candidate,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (mode) {
                ChopStageMode.CUT -> SourceWorkspace(
                    state = state,
                    metrics = metrics,
                    viewModel = viewModel,
                )
                ChopStageMode.PADS -> PerformanceWorkspace(
                    state = state,
                    metrics = metrics,
                    liveChopArmed = liveChopArmed,
                    onToggleLiveChop = { liveChopArmed = !liveChopArmed },
                    onOpenDetails = onOpenDetails,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun PerformanceWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    liveChopArmed: Boolean,
    onToggleLiveChop: () -> Unit,
    onOpenDetails: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    val captureMode = liveChopArmed && state.sourcePlaying
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SourceEditorWaveform(
            state = state,
            viewModel = viewModel,
            condensed = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (metrics.density == DeckDensity.COMPACT) 58.dp else 78.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(metrics.controlHeightDp.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MachineButton(
                label = if (state.sourcePlaying) "曲を止める\nSOURCE STOP" else "曲を試聴\nSOURCE PLAY",
                onClick = viewModel::toggleSourcePlayback,
                enabled = state.currentAudio != null,
                active = state.sourcePlaying,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = if (liveChopArmed) "LIVE CHOP ON\nPADへ切る" else "LIVE CHOP OFF\n普通に鳴らす",
                onClick = onToggleLiveChop,
                enabled = state.currentAudio != null,
                active = liveChopArmed,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
        SourceToolRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (metrics.density == DeckDensity.COMPACT) 32.dp else 38.dp),
        ) {
            MachineButton(
                label = if (state.manualChopEnabled) "手で切る ON\nTAP WAVE" else "手で切る\nMANUAL",
                onClick = viewModel::toggleManualChop,
                enabled = state.currentAudio != null,
                active = state.manualChopEnabled,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "自動検出\nAUTO",
                onClick = viewModel::autoChopTransient,
                enabled = state.currentAudio != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "選択→PAD\nASSIGN",
                onClick = viewModel::assignCurrentSelectionToPad,
                enabled = state.currentAudio != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "全部→PAD\nASSIGN ALL",
                onClick = viewModel::assignAllSlicesToPads,
                enabled = state.currentAudio != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
        BankStrip(
            selectedBank = state.selectedBank,
            height = metrics.controlHeightDp.dp,
            onSelectBank = viewModel::selectBank,
        )
        PadPageStrip(
            state = state,
            height = if (metrics.density == DeckDensity.COMPACT) 25.dp else 29.dp,
            onSelectPage = viewModel::selectPadPage,
        )
        PadGrid(
            pads = state.visiblePads(),
            selectedPad = state.selectedPad,
            captureMode = captureMode,
            onTrigger = if (captureMode) viewModel::capturePad else viewModel::triggerPad,
            onRelease = viewModel::releasePad,
            onSelect = viewModel::selectPad,
            gap = gap,
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                "${stage.label} / ${stage.caption}",
                color = Color(0xFF9C906F),
                fontFamily = DeckFont,
                fontSize = 7.sp,
                maxLines = 1,
            )
        }
        val bankRole = bankRoleFor(state.selectedBank)
        Text(
            "${bankRole.letter} ${bankRole.englishLabel}  ${state.bpm.toInt()} BPM",
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
                    label = "音が入ったらチョップへ\nNEXT: CHOP",
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
                label = "音が入ったらチョップへ  /  NEXT: CHOP",
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
            fontSize = 9.sp,
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
                label = "KEY / PITCH",
                value = "${signedValue(pad.pitchSemitones)} st ${pitchDirectionLabel(pad.pitchSemitones)}",
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
                    value = "${toneCharacterLabel(pad.tone)} ${(pad.tone * 100).toInt()}%",
                    enabled = pad.isAssigned,
                    onClick = {
                        viewModel.setSelectedPadTone(nextTonePreset(pad.tone))
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                QuickCycleButton(
                    label = "音量",
                    value = "${(pad.gain * 100).toInt()}%",
                    enabled = pad.isAssigned,
                    onClick = { viewModel.setSelectedPadGain(nextLevelPreset(pad.gain)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            if (onOpenDetails != null) {
                MachineButton(
                    label = "音を整える\nPAD EDIT",
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
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MachineButton(
        label = "$label\n$value",
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        compact = true,
    )
}

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
                PadPageStrip(state, 27.dp, viewModel::selectPadPage)
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
            PadPageStrip(state, 27.dp, viewModel::selectPadPage)
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
                label = "PADへ戻る\nBACK",
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
                label = when (pad.playMode) {
                    PadPlayMode.ONE_SHOT -> "一回鳴る\nONE SHOT"
                    PadPlayMode.GATE -> "押す間だけ\nGATE"
                    PadPlayMode.LOOP -> "繰り返す\nLOOP"
                },
                onClick = viewModel::toggleSelectedPadPlayMode,
                enabled = pad.isAssigned,
                active = pad.playMode != PadPlayMode.ONE_SHOT,
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
    onOpenLayerCapture: () -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    var showFineControls by rememberSaveable { mutableStateOf(false) }
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                ArrangementWaveformTimeline(
                    pad = state.loopingPadIndex?.let(state.pads::get) ?: state.selectedPadModel(),
                    activeSteps = state.activeSteps.audibleStepKeys(state.pads),
                    currentStep = state.currentStep,
                    transportPlaying = state.transportPlaying,
                    loopPlayheadFrame = state.loopPlayheadFrame,
                    loopPlaying = state.loopingPadIndex != null,
                    modifier = Modifier.fillMaxWidth().weight(0.62f),
                )
                BeatLaneBoard(
                    pads = state.pads,
                    activeSteps = state.activeSteps,
                    currentStep = state.currentStep,
                    selectedPad = state.selectedPad,
                    onSelectPad = viewModel::selectPad,
                    onToggleStep = viewModel::toggleStep,
                    modifier = Modifier.weight(1.38f),
                )
            }
            if (showFineControls) {
                SequenceControlDeck(
                    state = state,
                    metrics = metrics,
                    onOpenLayerCapture = onOpenLayerCapture,
                    showFineControls = true,
                    onShowFineControls = { showFineControls = it },
                    viewModel = viewModel,
                    modifier = Modifier.weight(0.7f).fillMaxHeight(),
                )
            } else {
                Column(
                    modifier = Modifier.weight(0.7f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    BankStrip(
                        selectedBank = state.selectedBank,
                        height = metrics.controlHeightDp.dp,
                        onSelectBank = viewModel::selectBank,
                    )
                    PadPageStrip(state, 25.dp, viewModel::selectPadPage)
                    BeatSoundRail(
                        pads = state.visiblePads(),
                        selectedPad = state.selectedPad,
                        onSelectPad = viewModel::selectPad,
                        onPreviewPad = viewModel::triggerPad,
                        modifier = Modifier.weight(1f),
                    )
                    SelectedPadQuickEditor(
                        state = state,
                        height = metrics.controlHeightDp.dp,
                        expanded = false,
                        onOpenDetails = null,
                        viewModel = viewModel,
                    )
                    SequenceTransportRow(state, metrics.controlHeightDp.dp, gap, viewModel)
                    BeatLoopControl(state, metrics.controlHeightDp.dp * 1.5f, viewModel)
                    QuickArrangeActionRow(
                        height = metrics.controlHeightDp.dp,
                        onOpenLayerCapture = onOpenLayerCapture,
                        onOpenFineControls = { showFineControls = true },
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (!showFineControls) {
                ArrangementWaveformTimeline(
                    pad = state.loopingPadIndex?.let(state.pads::get) ?: state.selectedPadModel(),
                    activeSteps = state.activeSteps.audibleStepKeys(state.pads),
                    currentStep = state.currentStep,
                    transportPlaying = state.transportPlaying,
                    loopPlayheadFrame = state.loopPlayheadFrame,
                    loopPlaying = state.loopingPadIndex != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.5f),
                )
                BeatLaneBoard(
                    pads = state.pads,
                    activeSteps = state.activeSteps,
                    currentStep = state.currentStep,
                    selectedPad = state.selectedPad,
                    onSelectPad = viewModel::selectPad,
                    onToggleStep = viewModel::toggleStep,
                    modifier = Modifier.fillMaxWidth().weight(1.25f),
                )
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectBank,
                )
                PadPageStrip(
                    state = state,
                    height = if (metrics.density == DeckDensity.COMPACT) 24.dp else 28.dp,
                    onSelectPage = viewModel::selectPadPage,
                )
                BeatSoundRail(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    onSelectPad = viewModel::selectPad,
                    onPreviewPad = viewModel::triggerPad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (metrics.density == DeckDensity.COMPACT) 58.dp else 68.dp),
                )
                SelectedPadQuickEditor(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    expanded = false,
                    onOpenDetails = null,
                    viewModel = viewModel,
                )
                SequenceTransportRow(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    gap = gap,
                    viewModel = viewModel,
                )
                BeatLoopControl(
                    state = state,
                    height = metrics.controlHeightDp.dp * 1.35f,
                    viewModel = viewModel,
                )
                QuickArrangeActionRow(
                    height = metrics.controlHeightDp.dp,
                    onOpenLayerCapture = onOpenLayerCapture,
                    onOpenFineControls = { showFineControls = true },
                )
            } else {
                FineControlsHeader(
                    height = if (metrics.density == DeckDensity.COMPACT) 30.dp else 36.dp,
                    onBack = { showFineControls = false },
                )
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectBank,
                )
                ArrangementWaveformTimeline(
                    pad = state.loopingPadIndex?.let(state.pads::get) ?: state.selectedPadModel(),
                    activeSteps = state.activeSteps.audibleStepKeys(state.pads),
                    currentStep = state.currentStep,
                    transportPlaying = state.transportPlaying,
                    loopPlayheadFrame = state.loopPlayheadFrame,
                    loopPlaying = state.loopingPadIndex != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.75f),
                )
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
                PlacementPresetPicker(
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
                        .height(if (metrics.density == DeckDensity.COMPACT) 72.dp else 84.dp),
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
    onOpenLayerCapture: () -> Unit,
    showFineControls: Boolean,
    onShowFineControls: (Boolean) -> Unit,
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    val gap = metrics.gapDp.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (!showFineControls) {
            BeginnerCoachBar(
                text = ARRANGE_QUICK_GUIDANCE,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            ArrangementWaveformTimeline(
                pad = state.loopingPadIndex?.let(state.pads::get) ?: state.selectedPadModel(),
                activeSteps = state.activeSteps.audibleStepKeys(state.pads),
                currentStep = state.currentStep,
                transportPlaying = state.transportPlaying,
                loopPlayheadFrame = state.loopPlayheadFrame,
                loopPlaying = state.loopingPadIndex != null,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            BeatLoopControl(
                state = state,
                height = metrics.controlHeightDp.dp * 1.85f,
                viewModel = viewModel,
            )
            QuickArrangeActionRow(
                height = metrics.controlHeightDp.dp,
                onOpenLayerCapture = onOpenLayerCapture,
                onOpenFineControls = { onShowFineControls(true) },
            )
            return@Column
        }
        FineControlsHeader(
            height = 30.dp,
            onBack = { onShowFineControls(false) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                ArrangementWaveformTimeline(
                    pad = state.loopingPadIndex?.let(state.pads::get) ?: state.selectedPadModel(),
                    activeSteps = state.activeSteps.audibleStepKeys(state.pads),
                    currentStep = state.currentStep,
                    transportPlaying = state.transportPlaying,
                    loopPlayheadFrame = state.loopPlayheadFrame,
                    loopPlaying = state.loopingPadIndex != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                StepSequencer(
                    selectedPad = state.selectedPad,
                    activeSteps = state.activeSteps,
                    currentStep = state.currentStep,
                    onToggleStep = viewModel::toggleStep,
                    columns = 8,
                    gap = 3.dp,
                    modifier = Modifier.weight(1f),
                )
                PlacementPresetPicker(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    viewModel = viewModel,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                SequenceTransportRow(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    gap = gap,
                    viewModel = viewModel,
                )
                LandscapeTempoRow(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    viewModel = viewModel,
                )
                LandscapeKeyRow(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    viewModel = viewModel,
                )
                LandscapeToneLevelRow(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun LandscapeTempoRow(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(
            "BPM -\n${state.bpm.toInt()}" to { viewModel.setBpm(state.bpm - 1f) },
            "BPM +\n${state.bpm.toInt()}" to { viewModel.setBpm(state.bpm + 1f) },
            "SW -\n${state.swing.toInt()}%" to { viewModel.setSwing(state.swing - 1f) },
            "SW +\n${state.swing.toInt()}%" to { viewModel.setSwing(state.swing + 1f) },
        ).forEach { (label, action) ->
            MachineButton(
                label = label,
                onClick = action,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun LandscapeKeyRow(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MachineButton(
            label = "KEY -",
            onClick = { viewModel.setSelectedPadPitch(pad.pitchSemitones - 1f) },
            enabled = pad.isAssigned,
            modifier = Modifier.weight(0.8f).fillMaxHeight(),
            compact = true,
        )
        ValueDisplay(
            label = "KEY / PITCH",
            value = "${signedValue(pad.pitchSemitones)} st ${pitchDirectionLabel(pad.pitchSemitones)}",
            modifier = Modifier.weight(1.4f).fillMaxHeight(),
        )
        MachineButton(
            label = "KEY +",
            onClick = { viewModel.setSelectedPadPitch(pad.pitchSemitones + 1f) },
            enabled = pad.isAssigned,
            modifier = Modifier.weight(0.8f).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun LandscapeToneLevelRow(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        QuickCycleButton(
            label = "音色",
            value = "${toneCharacterLabel(pad.tone)} ${(pad.tone * 100).toInt()}%",
            enabled = pad.isAssigned,
            onClick = { viewModel.setSelectedPadTone(nextTonePreset(pad.tone)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        QuickCycleButton(
            label = "音量",
            value = "${(pad.gain * 100).toInt()}%",
            enabled = pad.isAssigned,
            onClick = { viewModel.setSelectedPadGain(nextLevelPreset(pad.gain)) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun BeatLoopControl(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    val padLabel = "${bankName(pad.bankIndex)}-%02d".format(pad.indexInBank + 1)
    val loopingPad = state.loopingPadIndex?.let(state.pads::get)
    val loopingPadLabel = loopingPad?.let {
        "${bankName(it.bankIndex)}-%02d".format(it.indexInBank + 1)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(DeckInk, RoundedCornerShape(6.dp))
            .border(2.dp, DeckLamp, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = arrangeBeatLoopPrompt(pad.isAssigned, padLabel, loopingPadLabel),
            color = if (pad.isAssigned || loopingPad != null) DeckGreen else DeckPanelDark,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MachineButton(
                label = if (loopingPad != null) "ループ停止\nSTOP" else "ビートをループ\nSTART",
                onClick = viewModel::toggleBeatLoopControl,
                enabled = pad.isAssigned || loopingPad != null,
                active = loopingPad != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun PlacementPresetPicker(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    val activeGrid = state.activeSteps.repeatGridForPad(state.selectedPad)
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ValueDisplay(
            label = "配置プリセット",
            value = if (pad.playMode == PadPlayMode.LOOP) "別PADに置く" else "鳴らす場所",
            modifier = Modifier.weight(1.15f).fillMaxHeight(),
        )
        PlacementPresetChoices.forEach { (grid, label) ->
            MachineButton(
                label = label,
                onClick = { viewModel.fillSelectedPadPattern(grid) },
                enabled = pad.isAssigned && pad.playMode != PadPlayMode.LOOP,
                active = activeGrid == grid,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun QuickArrangeActionRow(
    height: Dp,
    onOpenLayerCapture: () -> Unit,
    onOpenFineControls: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MachineButton(
            label = "3 音を重ねる\nDRUM · VOICE · SCRATCH",
            onClick = onOpenLayerCapture,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
        MachineButton(
            label = "細かく調整\nSTEPS / SOUND",
            onClick = onOpenFineControls,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun LayerStudio(
    state: SamplerUiState,
    onDismiss: () -> Unit,
    onToggleVocalRecording: () -> Unit,
    viewModel: SamplerViewModel,
) {
    var pageName by rememberSaveable { mutableStateOf(LayerStudioPage.SOUNDS.name) }
    var kitId by rememberSaveable { mutableStateOf(state.selectedDrumKitId) }
    val page = LayerStudioPage.entries.firstOrNull { it.name == pageName } ?: LayerStudioPage.SOUNDS

    Dialog(
        onDismissRequest = {
            if (state.scratchingPadIndex != null || state.sourceScratchActive) viewModel.endScratch()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC070604))
                .padding(horizontal = 12.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = DeckPanel,
                contentColor = DeckInk,
                shape = ConsoleShape,
                shadowElevation = 14.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .widthIn(max = 760.dp)
                    .border(2.dp, DeckLamp, ConsoleShape),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(DeckInk, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "LAYER STUDIO",
                                color = DeckLamp,
                                fontFamily = DeckFont,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                            )
                            Text(
                                "ビートに音を重ねる",
                                color = DeckGreen,
                                fontFamily = DeckFont,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                        MachineButton(
                            label = "閉じる\nCLOSE",
                            onClick = {
                                if (state.scratchingPadIndex != null || state.sourceScratchActive) viewModel.endScratch()
                                onDismiss()
                            },
                            modifier = Modifier.width(86.dp).fillMaxHeight(),
                            compact = true,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        LayerStudioPage.entries.forEach { candidate ->
                            MachineButton(
                                label = candidate.label,
                                onClick = {
                                    if (
                                        page == LayerStudioPage.SCRATCH &&
                                        (state.scratchingPadIndex != null || state.sourceScratchActive)
                                    ) {
                                        viewModel.endScratch()
                                    }
                                    pageName = candidate.name
                                },
                                active = page == candidate,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                compact = true,
                            )
                        }
                    }
                    when (page) {
                        LayerStudioPage.SOUNDS -> SampleLayerStudio(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f),
                        )
                        LayerStudioPage.DRUMS -> DrumKitStudio(
                            selectedKitId = kitId,
                            bankHasContent = state.pads
                                .subList(
                                    SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK,
                                    SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK +
                                        SamplerConfig.DRUM_KIT_PAD_COUNT,
                                )
                                .any { it.isAssigned },
                            onSelectKit = { kitId = it },
                            onApply = { replaceExisting ->
                                viewModel.applyBuiltInDrumKit(kitId, replaceExisting)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        LayerStudioPage.VOICE -> VocalStudio(
                            state = state,
                            onToggleRecording = onToggleVocalRecording,
                            modifier = Modifier.weight(1f),
                        )
                        LayerStudioPage.SCRATCH -> ScratchStudio(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleLayerStudio(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BeginnerCoachBar(
            text = "メロディー・ドラム・SE・声を選び、好きな間隔で同じビートへ重ねます",
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
        BankStrip(state.selectedBank, 44.dp, viewModel::selectBank)
        PadPageStrip(state, 27.dp, viewModel::selectPadPage)
        BeatSoundRail(
            pads = state.visiblePads(),
            selectedPad = state.selectedPad,
            onSelectPad = viewModel::selectPad,
            onPreviewPad = viewModel::triggerPad,
            modifier = Modifier.weight(1f),
        )
        SelectedPadQuickEditor(
            state = state,
            height = 44.dp,
            expanded = false,
            onOpenDetails = null,
            viewModel = viewModel,
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PlacementPresetChoices.forEach { (grid, label) ->
                MachineButton(
                    label = label,
                    onClick = { viewModel.fillSelectedPadPattern(grid) },
                    enabled = state.selectedPadModel().isAssigned,
                    active = state.activeSteps.repeatGridForPad(state.selectedPad) == grid,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
            ConfirmActionButton(
                label = "配置を消す\nCLEAR",
                confirmLabel = "もう一度で削除",
                onConfirm = viewModel::clearSelectedPadPattern,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        BeatLoopControl(state, 62.dp, viewModel)
    }
}

@Composable
private fun DrumKitStudio(
    selectedKitId: String,
    bankHasContent: Boolean,
    onSelectKit: (String) -> Unit,
    onApply: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BeginnerCoachBar(
            text = "5つのオリジナル音色。選ぶだけで初心者向けパターンも入ります",
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
        BuiltInDrumKits.catalog.forEachIndexed { index, kit ->
            DrumKitCard(
                index = index,
                kit = kit,
                selected = selectedKitId == kit.id,
                onClick = { onSelectKit(kit.id) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ValueDisplay(
                label = "BANK B / ドラム",
                value = "DRUMS",
                modifier = Modifier.weight(0.9f).fillMaxHeight(),
            )
            if (bankHasContent) {
                ConfirmActionButton(
                    label = "Bの音色を入替\nKEEP SAFE",
                    confirmLabel = "もう一度で入替",
                    onConfirm = { onApply(true) },
                    modifier = Modifier.weight(1.6f).fillMaxHeight(),
                )
            } else {
                MachineButton(
                    label = "Bに音色をセット\nKIT + STARTER BEAT",
                    onClick = { onApply(false) },
                    modifier = Modifier.weight(1.6f).fillMaxHeight(),
                    active = true,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DrumKitCard(
    index: Int,
    kit: com.choplab.sampler.audio.DrumKitDefinition,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) DeckLamp else DeckPanelDark
    val foreground = if (selected) Color(0xFF2A1000) else DeckInk
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(7.dp),
        shadowElevation = if (selected) 4.dp else 1.dp,
        modifier = modifier
            .border(if (selected) 3.dp else 1.5.dp, DeckInk, RoundedCornerShape(7.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${kit.name} ドラムキット ${kit.character}" },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.width(50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "%02d".format(index + 1),
                    color = foreground,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                )
                Text(
                    kit.accent,
                    color = foreground.copy(alpha = 0.75f),
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 7.sp,
                    maxLines = 1,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kit.name,
                    color = foreground,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Text(
                    kit.character,
                    color = foreground.copy(alpha = 0.78f),
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "KICK · SNARE · HAT · PERC  /  16 PADS",
                    color = foreground.copy(alpha = 0.62f),
                    fontFamily = DeckFont,
                    fontSize = 6.sp,
                    maxLines = 1,
                )
            }
            Canvas(modifier = Modifier.width(86.dp).fillMaxHeight().padding(vertical = 9.dp)) {
                val bars = floatArrayOf(0.46f, 0.9f, 0.62f, 0.34f, 0.74f, 0.52f, 0.84f, 0.4f)
                val barWidth = size.width / (bars.size * 1.65f)
                val gap = (size.width - barWidth * bars.size) / (bars.size - 1)
                bars.forEachIndexed { barIndex, amount ->
                    val varied = (amount + index * 0.045f * if (barIndex % 2 == 0) 1f else -1f)
                        .coerceIn(0.18f, 0.95f)
                    val height = size.height * varied
                    drawRoundRect(
                        color = foreground.copy(alpha = if (selected) 0.8f else 0.55f),
                        topLeft = Offset(barIndex * (barWidth + gap), (size.height - height) / 2f),
                        size = androidx.compose.ui.geometry.Size(barWidth, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VocalStudio(
    state: SamplerUiState,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val takeCount = state.pads.count { it.isAssigned && it.contentKind == com.choplab.sampler.model.PadContentKind.VOCAL }
    val loopReady = state.pads.any { it.isAssigned && it.playMode == PadPlayMode.LOOP }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BeginnerCoachBar(
            text = "ヘッドホン推奨：ビートを流しながらラップや声を録音します",
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
        ValueDisplay(
            label = "VOICE TAKES / D ボイス",
            value = "$takeCount / ${SamplerConfig.PADS_PER_BANK} テイク保存済み",
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ValueDisplay(
                label = "1 BEAT LOOP",
                value = if (loopReady) "準備OK" else "先に開始",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ValueDisplay(
                label = "2 HEADPHONE",
                value = "装着推奨",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            ValueDisplay(
                label = "3 VOICE REC",
                value = if (state.vocalOverdubRecording) "録音中" else "押して録音",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = if (state.vocalOverdubRecording) Color(0xFFB92B24) else DeckInk,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(156.dp)
                    .alpha(if (loopReady || state.vocalOverdubRecording) 1f else 0.42f)
                    .border(5.dp, if (state.vocalOverdubRecording) DeckLamp else DeckPanelDark, CircleShape)
                    .clickable(
                        enabled = loopReady || state.vocalOverdubRecording,
                        role = Role.Button,
                        onClick = onToggleRecording,
                    )
                    .semantics { contentDescription = if (state.vocalOverdubRecording) "声の録音を停止" else "声の録音を開始" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.vocalOverdubRecording -> "STOP\nテイクを保存"
                            loopReady -> "REC\n声を重ねる"
                            else -> "WAIT\n先にビートをループ"
                        },
                        color = Color.White,
                        fontFamily = DeckFont,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScratchStudio(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    modifier: Modifier = Modifier,
) {
    val audio = state.currentAudio
    val range = state.sourceScratchRange()
    val frame = state.scratchPlayheadFrame.takeIf { it >= 0 } ?: state.loopPlayheadFrame
    val progress = if (range != null && frame >= range.startFrame) {
        (frame - range.startFrame).toFloat() / range.length.coerceAtLeast(1)
    } else {
        0f
    }.coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BeginnerCoachBar(
            text = "橙のS/Eをドラッグ、または波形タップでチョップを選び、円盤を擦ります",
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        SourceEditorWaveform(
            state = state,
            viewModel = viewModel,
            condensed = true,
            selectSliceOnly = true,
            modifier = Modifier.fillMaxWidth().height(112.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MachineButton(
                label = "選んだ範囲を聴く\nPREVIEW RANGE",
                onClick = viewModel::previewCurrentSelection,
                enabled = range != null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "元曲のS/E範囲\nSOURCE RANGE",
                onClick = viewModel::useSourceRangeForScratch,
                enabled = audio != null,
                active = state.activeSliceIndex == null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFF11100C),
                shape = CircleShape,
                modifier = Modifier
                    .size(180.dp)
                    .border(5.dp, if (state.sourceScratchActive) DeckLamp else DeckInk, CircleShape)
                    .pointerInput(audio?.id, range) {
                        detectDragGestures(
                            onDragStart = { viewModel.beginSourceScratch() },
                            onDragEnd = { viewModel.endScratch() },
                            onDragCancel = { viewModel.endScratch() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                viewModel.updateScratchSpeed(dragAmount.x / 7f)
                            },
                        )
                    }
                    .semantics {
                        contentDescription = "スクラッチ円盤。左右へドラッグ"
                    },
            ) {
                Canvas(Modifier.fillMaxSize().padding(13.dp)) {
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    repeat(7) { ring ->
                        drawCircle(
                            color = if (ring % 2 == 0) Color(0xFF665B42) else Color(0xFF342F23),
                            radius = radius * (1f - ring * 0.105f),
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                        )
                    }
                    drawCircle(DeckPanelDark, radius * 0.28f, center)
                    drawCircle(DeckLamp, radius * 0.06f, center)
                    val angle = progress * (2.0 * PI) - PI / 2.0
                    drawLine(
                        color = DeckLamp,
                        start = center,
                        end = Offset(
                            center.x + cos(angle).toFloat() * radius * 0.78f,
                            center.y + sin(angle).toFloat() * radius * 0.78f,
                        ),
                        strokeWidth = 5f,
                    )
                }
            }
        }
        Text(
            text = if (audio != null && range != null) {
                "元曲  ${formatDeckTime(range.startFrame, audio.sampleRate)}–${formatDeckTime(range.endFrame, audio.sampleRate)}  •  %03d%%"
                    .format((progress * 100).toInt())
            } else {
                "先に元曲を読み込んで範囲を選んでください"
            },
            color = DeckInk,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun FineControlsHeader(
    height: Dp,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BeginnerCoachBar(
            text = "細かく調整：16ステップ・テンポ・音色",
            modifier = Modifier.weight(1.7f).fillMaxHeight(),
        )
        MachineButton(
            label = "かんたん作成へ戻る",
            onClick = onBack,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
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
            compact = true,
        )
        MachineButton(
            label = if (state.recordArmed) "演奏を記録中\nREC ON" else "演奏を記録\nREC",
            onClick = viewModel::toggleRecordArm,
            active = state.recordArmed,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            compact = true,
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
                        "操作は端末内へ3世代で自動保存。『ビート』で鳴らすマスを光らせてください。"
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
                    label = "ビートへ戻る\nBACK",
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
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    val gap = metrics.gapDp.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
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
private fun SourceEditorWaveform(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    modifier: Modifier,
    condensed: Boolean = false,
    selectSliceOnly: Boolean = false,
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
            val manualChopEnabled = state.manualChopEnabled && !selectSliceOnly
            WaveformEditor(
                audio = audio,
                rangeStartFrame = state.rangeStartFrame,
                rangeEndFrame = state.rangeEndFrame,
                sliceMarkers = state.sliceMarkers,
                activeSlice = state.activeSliceRange(),
                manualChopEnabled = manualChopEnabled,
                onRangeStartChange = viewModel::setRangeStart,
                onRangeEndChange = viewModel::setRangeEnd,
                onSliceMarkerChange = viewModel::moveSliceMarker,
                onWaveformTap = { frame ->
                    if (manualChopEnabled) viewModel.addSliceMarker(frame)
                    else viewModel.selectSliceAt(frame)
                },
                playheadFrame = state.sourcePlayheadFrame,
                fillCanvas = true,
                showViewportControls = false,
                compactViewportControls = !condensed,
                showTimeReadout = !condensed,
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
            val role = bankRoleFor(bank)
            MachineButton(
                label = "${role.letter} ${role.japaneseLabel}\n${role.englishLabel}",
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
private fun PadPageStrip(
    state: SamplerUiState,
    height: Dp,
    onSelectPage: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(SamplerConfig.PAD_PAGES_PER_BANK) { page ->
            val first = page * SamplerConfig.PAD_PAGE_SIZE + 1
            val last = first + SamplerConfig.PAD_PAGE_SIZE - 1
            val assigned = state.assignedPadCountOnPage(page)
            MachineButton(
                label = "PAD %02d–%02d  %s".format(first, last, if (assigned == 0) "空" else "${assigned}音"),
                onClick = { onSelectPage(page) },
                active = state.selectedPadPage() == page,
                modifier = Modifier.weight(1f).fillMaxHeight(),
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
