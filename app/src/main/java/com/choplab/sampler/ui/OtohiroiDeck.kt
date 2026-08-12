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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import com.choplab.sampler.model.PadTrimPrecision
import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.assignedPadCountOnPage
import com.choplab.sampler.model.bankRoleFor
import com.choplab.sampler.model.canUsePatternSteps
import com.choplab.sampler.model.hasAudiblePatternContent
import com.choplab.sampler.model.padTrimNudgeFrames
import com.choplab.sampler.model.repeatGridForPad
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.selectedPadPage
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.sourceUiPhase
import com.choplab.sampler.model.visiblePads
import kotlin.math.max
import kotlin.math.abs
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
    TRIM("切り位置\nTRIM"),
    PARAM("音づくり\nPARAM"),
    PLAY("鳴り方\nPLAY"),
}

private enum class LayerStudioPage(val label: String) {
    SOUNDS("SOUNDS\n音を重ねる"),
    DRUMS("DRUMS\nドラム"),
    VOICE("VOICE\n声を録る"),
    SCRATCH("SCRATCH\nこする"),
}

private enum class ScratchTarget(val label: String) {
    SOURCE("元曲をこする\nSOURCE"),
    PAD("選択PADをこする\nPAD"),
}

private enum class ScratchSensitivity(val label: String, val divisor: Float) {
    FINE("細かい\nFINE", 12f),
    NORMAL("標準\nNORMAL", 7f),
    WIDE("大きい\nWIDE", 4f),
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
    var showPadDetails by rememberSaveable { mutableStateOf(false) }
    var layerStudioPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val stage = restoreWorkflowStage(stageName)
    val padPage = PadEditorPage.entries.firstOrNull { it.name == padPageName } ?: PadEditorPage.PARAM
    val layerStudioPage = LayerStudioPage.entries.firstOrNull { it.name == layerStudioPageName }

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
                        onSelect = { target ->
                            val actions = workflowNavigationActions(stage, target)
                            if (WorkflowNavigationAction.STOP_SOURCE in actions) {
                                viewModel.stopSourceForWorkspaceChange()
                            }
                            if (WorkflowNavigationAction.ENSURE_PLAYABLE_PAD in actions) {
                                viewModel.ensurePlayablePadSelected()
                            }
                            stageName = target.name
                            if (target != WorkflowStage.CHOP) showPadDetails = false
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
                                onContinue = {
                                    val policy = startChopPolicy(state.sourceUiPhase())
                                    if (policy.enabled) {
                                        if (policy.prepareMelodyDestination) {
                                            viewModel.prepareDefaultChopDestination()
                                        }
                                        stageName = WorkflowStage.CHOP.name
                                        if (policy.startSource) viewModel.restartSourcePlayback()
                                    }
                                },
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
                                    onImportAudio = onImportAudio,
                                    onOpenDetails = {
                                        padPageName = PadEditorPage.PARAM.name
                                        showPadDetails = true
                                    },
                                    onOpenTrim = { padIndex ->
                                        viewModel.selectPad(padIndex)
                                        padPageName = PadEditorPage.TRIM.name
                                        showPadDetails = true
                                    },
                                    onContinueToBeat = {
                                        viewModel.stopSourceForWorkspaceChange()
                                        viewModel.ensurePlayablePadSelected()
                                        stageName = WorkflowStage.BEAT.name
                                    },
                                    onOpenLayerStudio = { layerStudioPageName = it.name },
                                    viewModel = viewModel,
                                )
                            }
                            WorkflowStage.BEAT -> SequenceWorkspace(
                                state = state,
                                metrics = metrics,
                                onOpenLayerStudio = { layerStudioPageName = it.name },
                                viewModel = viewModel,
                            )
                            WorkflowStage.FINISH -> FinishWorkspace(
                                state = state,
                                metrics = metrics,
                                onExportBeat = onExportBeat,
                                onOpenProject = onOpenProject,
                                onSaveProject = onSaveProject,
                                onBackToArrange = {
                                    viewModel.stopSourceForWorkspaceChange()
                                    viewModel.ensurePlayablePadSelected()
                                    stageName = WorkflowStage.BEAT.name
                                },
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
        if (layerStudioPage != null) {
            LayerStudio(
                state = state,
                initialPage = layerStudioPage,
                onDismiss = { layerStudioPageName = null },
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
    onImportAudio: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenTrim: (Int) -> Unit,
    onContinueToBeat: () -> Unit,
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
    viewModel: SamplerViewModel,
) {
    PerformanceWorkspace(
        state = state,
        metrics = metrics,
        onImportAudio = onImportAudio,
        onOpenDetails = onOpenDetails,
        onOpenTrim = onOpenTrim,
        onContinueToBeat = onContinueToBeat,
        onOpenLayerStudio = onOpenLayerStudio,
        viewModel = viewModel,
    )
}

@Composable
private fun PerformanceWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onImportAudio: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenTrim: (Int) -> Unit,
    onContinueToBeat: () -> Unit,
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    val sourcePhase = state.sourceUiPhase()
    val presentation = chopSessionPresentation(
        sourcePhase = sourcePhase,
        assignedPadCount = state.pads.count(PadModel::isAssigned),
    )
    if (performanceWorkspaceLayout(metrics) == PerformanceWorkspaceLayout.SPLIT_PAD_GRID) {
        LandscapePerformanceWorkspace(
            state = state,
            metrics = metrics,
            presentation = presentation,
            sourcePhase = sourcePhase,
            onImportAudio = onImportAudio,
            onOpenDetails = onOpenDetails,
            onOpenTrim = onOpenTrim,
            onContinueToBeat = onContinueToBeat,
            onOpenLayerStudio = onOpenLayerStudio,
            viewModel = viewModel,
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        ChopCoachRow(
            state = state,
            presentation = presentation,
            gap = gap,
            onImportAudio = onImportAudio,
        )
        SourceEditorWaveform(
            state = state,
            viewModel = viewModel,
            condensed = true,
            waveformTapOverride = viewModel::playSourceFrom,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (metrics.density == DeckDensity.COMPACT) 58.dp else 78.dp),
        )
        ChopSourceControlRow(
            state = state,
            presentation = presentation,
            height = metrics.controlHeightDp.dp,
            gap = gap,
            viewModel = viewModel,
        )
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
            captureMode = presentation.captureMode,
            sourcePhase = sourcePhase,
            onTrigger = viewModel::capturePad,
            onRelease = viewModel::releasePad,
            onSelect = viewModel::selectPad,
            onLongPress = onOpenTrim,
            gap = gap,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        ChopNextActionRow(
            state = state,
            height = metrics.productionDockHeightDp.dp,
            gap = gap,
            onOpenDetails = onOpenDetails,
            onContinueToBeat = onContinueToBeat,
            onOpenLayerStudio = onOpenLayerStudio,
        )
    }
}

@Composable
private fun LandscapePerformanceWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    presentation: ChopSessionPresentation,
    sourcePhase: SourceUiPhase,
    onImportAudio: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenTrim: (Int) -> Unit,
    onContinueToBeat: () -> Unit,
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
    viewModel: SamplerViewModel,
) {
    val gap = metrics.gapDp.dp
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            ChopCoachRow(
                state = state,
                presentation = presentation,
                gap = gap,
                onImportAudio = onImportAudio,
            )
            SourceEditorWaveform(
                state = state,
                viewModel = viewModel,
                condensed = true,
                waveformTapOverride = viewModel::playSourceFrom,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ChopSourceControlRow(
                state = state,
                presentation = presentation,
                height = metrics.productionDockHeightDp.dp,
                gap = gap,
                viewModel = viewModel,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(metrics.controlHeightDp.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectBank,
                    modifier = Modifier.weight(1.6f),
                )
                PadPageStrip(
                    state = state,
                    height = metrics.controlHeightDp.dp,
                    onSelectPage = viewModel::selectPadPage,
                    modifier = Modifier.weight(1f),
                )
            }
            ChopNextActionRow(
                state = state,
                height = metrics.productionDockHeightDp.dp,
                gap = gap,
                onOpenDetails = onOpenDetails,
                onContinueToBeat = onContinueToBeat,
                onOpenLayerStudio = onOpenLayerStudio,
            )
        }
        PadGrid(
            pads = state.visiblePads(),
            selectedPad = state.selectedPad,
            captureMode = presentation.captureMode,
            sourcePhase = sourcePhase,
            onTrigger = viewModel::capturePad,
            onRelease = viewModel::releasePad,
            onSelect = viewModel::selectPad,
            onLongPress = onOpenTrim,
            gap = gap,
            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
        )
    }
}

@Composable
private fun ChopCoachRow(
    state: SamplerUiState,
    presentation: ChopSessionPresentation,
    gap: Dp,
    onImportAudio: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        BeginnerCoachBar(
            text = presentation.guidance,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        NewSourceActionButton(
            state = state,
            label = "素材を入れ替える\nREPLACE SOURCE",
            onConfirm = onImportAudio,
            modifier = Modifier.width(104.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun ChopSourceControlRow(
    state: SamplerUiState,
    presentation: ChopSessionPresentation,
    height: Dp,
    gap: Dp,
    viewModel: SamplerViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MachineButton(
            label = presentation.primaryActionLabel,
            onClick = viewModel::toggleChopPlayback,
            enabled = state.currentAudio != null && presentation.primaryEnabled,
            active = presentation.captureMode,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
        MachineButton(
            label = "高さ -\nKEY",
            onClick = { viewModel.setMasterPitch(state.masterPitchSemitones - 1f) },
            enabled = state.currentAudio != null,
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            compact = true,
        )
        ValueDisplay(
            label = "KEY",
            value = signedValue(state.masterPitchSemitones),
            modifier = Modifier.width(52.dp).fillMaxHeight(),
        )
        MachineButton(
            label = "高さ +\nKEY",
            onClick = { viewModel.setMasterPitch(state.masterPitchSemitones + 1f) },
            enabled = state.currentAudio != null,
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun ChopNextActionRow(
    state: SamplerUiState,
    height: Dp,
    gap: Dp,
    onOpenDetails: () -> Unit,
    onContinueToBeat: () -> Unit,
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
) {
    ProductionDock(
        height = height,
        gap = gap,
        items = chopProductionDockItems(state),
        handlers = mapOf(
            ProductionDockIntent.OPEN_BEAT to onContinueToBeat,
            ProductionDockIntent.OPEN_PAD_EDIT to onOpenDetails,
            ProductionDockIntent.OPEN_ADD to { onOpenLayerStudio(LayerStudioPage.DRUMS) },
            ProductionDockIntent.OPEN_SCRATCH to { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
        ),
    )
}

@Composable
private fun ProductionDock(
    items: List<ProductionDockItem>,
    height: Dp,
    gap: Dp,
    handlers: Map<ProductionDockIntent, () -> Unit>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        items.forEach { item ->
            val onClick = handlers.getValue(item.intent)
            val modifier = Modifier.weight(item.weight).fillMaxHeight()
            if (item.confirmLabel != null) {
                ConfirmActionButton(
                    label = item.label,
                    confirmLabel = item.confirmLabel,
                    onConfirm = onClick,
                    enabled = item.enabled,
                    modifier = modifier,
                )
            } else {
                MachineButton(
                    label = item.label,
                    onClick = onClick,
                    enabled = item.enabled,
                    active = item.active,
                    modifier = modifier,
                    compact = true,
                )
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
    val fontScale = LocalDensity.current.fontScale
    val playbackActive = state.hasPlaybackActivity()
    val recordingActive = state.hasRecordingActivity()
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
            active = playbackActive || recordingActive,
            alert = recordingActive,
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
            if (machineHeaderShowsCaption(fontScale)) {
                Text(
                    "${stage.label} / ${stage.caption}",
                    color = Color(0xFF9C906F),
                    fontFamily = DeckFont,
                    fontSize = 7.sp,
                    maxLines = 1,
                )
            }
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
            label = "音を全停止\nALL STOP",
            onClick = onStopAll,
            active = playbackActive,
            modifier = Modifier
                .width(82.dp)
                .fillMaxHeight(),
            compact = true,
        )
    }
}

private fun SamplerUiState.hasPlaybackActivity(): Boolean =
    sourceUiPhase() != SourceUiPhase.STOPPED ||
        transportPlaying ||
        loopingPadIndex != null ||
        scratchingPadIndex != null ||
        sourceScratchActive

private fun SamplerUiState.hasRecordingActivity(): Boolean =
    microphoneRecording || systemAudioRecording || vocalOverdubRecording

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
                this.selected = selected
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
                    isLoading = state.isLoading,
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
                CaptureNextRow(
                    state = state,
                    height = metrics.productionDockHeightDp.dp,
                    onContinue = onContinue,
                    onReset = viewModel::resetProject,
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
                isLoading = state.isLoading,
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
            CaptureNextRow(
                state = state,
                height = metrics.productionDockHeightDp.dp,
                onContinue = onContinue,
                onReset = viewModel::resetProject,
            )
        }
    }
}

@Composable
private fun CaptureNextRow(
    state: SamplerUiState,
    height: Dp,
    onContinue: () -> Unit,
    onReset: () -> Unit,
) {
    ProductionDock(
        height = height,
        gap = 5.dp,
        items = captureProductionDockItems(state),
        handlers = mapOf(
            ProductionDockIntent.RESET_ALL to onReset,
            ProductionDockIntent.START_CHOP to onContinue,
        ),
    )
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
                NewSourceActionButton(
                    state = state,
                    label = "曲を読込\nFILE",
                    onConfirm = onImportAudio,
                    enabled = !state.isLoading && !state.microphoneRecording && !state.systemAudioRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                NewSourceActionButton(
                    state = state,
                    label = if (state.microphoneRecording) "録音を止める\nMIC STOP" else "マイク録音\nMIC REC",
                    onConfirm = onToggleMicrophoneRecording,
                    enabled = !state.systemAudioRecording,
                    active = state.microphoneRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                NewSourceActionButton(
                    state = state,
                    label = if (state.systemAudioRecording) "録音を止める\nDEVICE STOP" else "端末を録音\nDEVICE REC",
                    onConfirm = onToggleSystemAudioRecording,
                    enabled = !state.microphoneRecording,
                    active = state.systemAudioRecording,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun SourceReadout(state: SamplerUiState, height: Dp) {
    val audio = state.currentAudio
    val sourcePhase = state.sourceUiPhase()
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
            text = captureSourceStatusLabel(
                sourcePhase = sourcePhase,
                isLoading = state.isLoading,
                audioLoaded = audio != null,
            ),
            color = if (sourcePhase != SourceUiPhase.STOPPED) DeckLamp else DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
        )
        Text(
            text = when {
                sourcePhase == SourceUiPhase.STARTING -> "再生を準備中。音が鳴るまで空PADは選択のみ"
                sourcePhase == SourceUiPhase.PLAYING -> "ここだと思ったらPADを押すと、その瞬間が入ります"
                sourcePhase == SourceUiPhase.STOPPING -> "停止処理中。割当済みPADは上書きされません"
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
    val sourcePhase = state.sourceUiPhase()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MachineButton(
            label = when (sourcePhase) {
                SourceUiPhase.STOPPED -> "曲を再生\nPLAY SONG"
                SourceUiPhase.STARTING -> "再生準備中\nCANCEL"
                SourceUiPhase.PLAYING -> "曲を止める\nSTOP"
                SourceUiPhase.STOPPING -> "停止中\nPLEASE WAIT"
            },
            onClick = onToggle,
            enabled = audioLoaded && sourcePhase != SourceUiPhase.STOPPING,
            active = sourcePhase != SourceUiPhase.STOPPED,
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
                modifier = Modifier.weight(if (page == PadEditorPage.TRIM) 0.72f else 1.15f),
            )
            PadEditor(
                state = state,
                page = page,
                onPageChange = onPageChange,
                onReturn = onReturn,
                viewModel = viewModel,
                controlHeight = metrics.controlHeightDp.dp,
                gap = gap,
                modifier = Modifier.weight(if (page == PadEditorPage.TRIM) 1.28f else 0.85f),
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
        when (page) {
            PadEditorPage.TRIM -> PadTrimEditor(
                pad = pad,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            PadEditorPage.PARAM -> ParameterEditor(
                pad = pad,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            PadEditorPage.PLAY -> PlayModeEditor(
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
private fun PadTrimEditor(
    pad: PadModel,
    viewModel: SamplerViewModel,
    modifier: Modifier,
) {
    val audio = pad.audio
    if (!pad.isAssigned || audio == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "音の入ったPADを長押ししてください",
                color = DeckInk,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    val entryStartFrame = rememberSaveable(pad.globalIndex, audio.id) { pad.startFrame }
    val entryEndFrame = rememberSaveable(pad.globalIndex, audio.id) { pad.endFrame }
    val entrySnapshot = remember(
        pad.globalIndex,
        audio.id,
        entryStartFrame,
        entryEndFrame,
    ) {
        PadTrimSnapshot(
            padIndex = pad.globalIndex,
            audioId = audio.id,
            startFrame = entryStartFrame,
            endFrame = entryEndFrame,
        )
    }
    var precisionName by rememberSaveable(pad.globalIndex, audio.id) {
        mutableStateOf(PadTrimPrecision.MILLISECOND.name)
    }
    val precision = PadTrimPrecision.entries.firstOrNull { it.name == precisionName }
        ?: PadTrimPrecision.MILLISECOND
    val nudgeFrames = padTrimNudgeFrames(audio.sampleRate, precision)
    val nudgeLabel = when (precision) {
        PadTrimPrecision.FRAME -> "1 FRAME"
        PadTrimPrecision.MILLISECOND -> "1 ms"
        PadTrimPrecision.TEN_MILLISECONDS -> "10 ms"
    }
    val canRestore = pad.startFrame != entrySnapshot.startFrame || pad.endFrame != entrySnapshot.endFrame
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MachinePanel(modifier = Modifier.fillMaxWidth().weight(1f)) {
            WaveformEditor(
                audio = audio,
                rangeStartFrame = pad.startFrame,
                rangeEndFrame = pad.endFrame,
                sliceMarkers = emptyList(),
                activeSlice = SliceRange(pad.startFrame, pad.endFrame),
                manualChopEnabled = false,
                onRangeStartChange = viewModel::setSelectedPadStartFrame,
                onRangeEndChange = viewModel::setSelectedPadEndFrame,
                onSliceMarkerChange = { _, _ -> },
                onWaveformTap = { frame ->
                    if (abs(frame - pad.startFrame) <= abs(frame - pad.endFrame)) {
                        viewModel.setSelectedPadStartFrame(frame)
                    } else {
                        viewModel.setSelectedPadEndFrame(frame)
                    }
                },
                fillCanvas = true,
                showViewportControls = false,
                compactViewportControls = true,
                showTimeReadout = true,
                showInteractionHint = false,
                maximumZoom = 256f,
                zoomFocusFrame = pad.startFrame + (pad.endFrame - pad.startFrame) / 2,
                readoutColor = Color(0xFFE8DDBF),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PadTrimPrecision.entries.forEach { option ->
                val label = when (option) {
                    PadTrimPrecision.FRAME -> "1 FRAME"
                    PadTrimPrecision.MILLISECOND -> "1 ms"
                    PadTrimPrecision.TEN_MILLISECONDS -> "10 ms"
                }
                MachineButton(
                    label = label,
                    onClick = { precisionName = option.name },
                    active = precision == option,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MachineButton(
                label = "開始 −\n$nudgeLabel",
                onClick = { viewModel.setSelectedPadStartFrame(pad.startFrame - nudgeFrames) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "開始 ＋\n$nudgeLabel",
                onClick = { viewModel.setSelectedPadStartFrame(pad.startFrame + nudgeFrames) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "終了 −\n$nudgeLabel",
                onClick = { viewModel.setSelectedPadEndFrame(pad.endFrame - nudgeFrames) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "終了 ＋\n$nudgeLabel",
                onClick = { viewModel.setSelectedPadEndFrame(pad.endFrame + nudgeFrames) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MachineButton(
                label = "調整したPADを聴く\nPREVIEW",
                onClick = { viewModel.triggerPad(pad.globalIndex) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
            MachineButton(
                label = "編集前へ戻す\nREVERT",
                onClick = { viewModel.restoreSelectedPadTrim(entrySnapshot) },
                enabled = canRestore,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
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
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
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
                    onSelectPad = viewModel::selectPlayablePad,
                    onToggleStep = viewModel::toggleStep,
                    modifier = Modifier.weight(1.38f),
                )
            }
            if (showFineControls) {
                SequenceControlDeck(
                    state = state,
                    metrics = metrics,
                    onOpenLayerStudio = onOpenLayerStudio,
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
                    BeginnerCoachBar(
                        text = arrangeQuickGuidance(state.selectedPadModel(), compact = true),
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(metrics.controlHeightDp.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        BankStrip(
                            selectedBank = state.selectedBank,
                            height = metrics.controlHeightDp.dp,
                            onSelectBank = viewModel::selectPlayableBank,
                            modifier = Modifier.weight(1.6f),
                            compactLabels = true,
                        )
                        PadPageStrip(
                            state = state,
                            height = metrics.controlHeightDp.dp,
                            onSelectPage = viewModel::selectPlayablePadPage,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BeatSoundRail(
                        pads = state.visiblePads(),
                        selectedPad = state.selectedPad,
                        onSelectPad = viewModel::selectPlayablePad,
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
                    LandscapeBeatPlaybackRow(
                        state = state,
                        height = metrics.controlHeightDp.dp,
                        gap = gap,
                        viewModel = viewModel,
                    )
                    BeatProductionDock(
                        stepsVisible = false,
                        height = metrics.productionDockHeightDp.dp,
                        onOpenAdd = { onOpenLayerStudio(LayerStudioPage.DRUMS) },
                        onOpenScratch = { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
                        onStepsVisibleChange = { showFineControls = it },
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
                BeginnerCoachBar(
                    text = arrangeQuickGuidance(state.selectedPadModel(), compact = false),
                    modifier = Modifier.fillMaxWidth().height(
                        if (metrics.density == DeckDensity.COMPACT) 24.dp else 28.dp,
                    ),
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
                        .weight(0.5f),
                )
                BeatLaneBoard(
                    pads = state.pads,
                    activeSteps = state.activeSteps,
                    currentStep = state.currentStep,
                    selectedPad = state.selectedPad,
                    onSelectPad = viewModel::selectPlayablePad,
                    onToggleStep = viewModel::toggleStep,
                    modifier = Modifier.fillMaxWidth().weight(1.25f),
                )
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectPlayableBank,
                )
                PadPageStrip(
                    state = state,
                    height = if (metrics.density == DeckDensity.COMPACT) 24.dp else 28.dp,
                    onSelectPage = viewModel::selectPlayablePadPage,
                )
                BeatSoundRail(
                    pads = state.visiblePads(),
                    selectedPad = state.selectedPad,
                    onSelectPad = viewModel::selectPlayablePad,
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
                BeatProductionDock(
                    stepsVisible = false,
                    height = metrics.productionDockHeightDp.dp,
                    onOpenAdd = { onOpenLayerStudio(LayerStudioPage.DRUMS) },
                    onOpenScratch = { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
                    onStepsVisibleChange = { showFineControls = it },
                )
            } else {
                BankStrip(
                    selectedBank = state.selectedBank,
                    height = metrics.controlHeightDp.dp,
                    onSelectBank = viewModel::selectPlayableBank,
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
                    enabled = state.selectedPadModel().canUsePatternSteps(),
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
                BeatProductionDock(
                    stepsVisible = true,
                    height = metrics.productionDockHeightDp.dp,
                    onOpenAdd = { onOpenLayerStudio(LayerStudioPage.DRUMS) },
                    onOpenScratch = { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
                    onStepsVisibleChange = { showFineControls = it },
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
    onOpenLayerStudio: (LayerStudioPage) -> Unit,
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
                text = arrangeQuickGuidance(state.selectedPadModel(), compact = false),
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
            BeatProductionDock(
                stepsVisible = false,
                height = metrics.productionDockHeightDp.dp,
                onOpenAdd = { onOpenLayerStudio(LayerStudioPage.DRUMS) },
                onOpenScratch = { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
                onStepsVisibleChange = onShowFineControls,
            )
            return@Column
        }
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
                    enabled = state.selectedPadModel().canUsePatternSteps(),
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
        BeatProductionDock(
            stepsVisible = true,
            height = metrics.productionDockHeightDp.dp,
            onOpenAdd = { onOpenLayerStudio(LayerStudioPage.DRUMS) },
            onOpenScratch = { onOpenLayerStudio(LayerStudioPage.SCRATCH) },
            onStepsVisibleChange = onShowFineControls,
        )
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
    val fontScale = LocalDensity.current.fontScale
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
                label = beatLoopButtonLabel(looping = loopingPad != null, fontScale = fontScale),
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
private fun LandscapeBeatPlaybackRow(
    state: SamplerUiState,
    height: Dp,
    gap: Dp,
    viewModel: SamplerViewModel,
) {
    val fontScale = LocalDensity.current.fontScale
    val selectedPad = state.selectedPadModel()
    val loopingPad = state.loopingPadIndex?.let(state.pads::get)
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MachineButton(
            label = if (state.transportPlaying) "ビート停止\nSTOP" else "ビート再生\nPLAY",
            onClick = viewModel::toggleTransport,
            active = state.transportPlaying,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
        MachineButton(
            label = beatLoopButtonLabel(looping = loopingPad != null, fontScale = fontScale),
            onClick = viewModel::toggleBeatLoopControl,
            enabled = selectedPad.isAssigned || loopingPad != null,
            active = loopingPad != null,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
        MachineButton(
            label = if (state.recordArmed) "演奏を記録中\nREC ON" else "演奏を記録\nREC",
            onClick = viewModel::toggleRecordArm,
            active = state.recordArmed,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
private fun PlacementPresetPicker(
    state: SamplerUiState,
    height: Dp,
    viewModel: SamplerViewModel,
) {
    val pad = state.selectedPadModel()
    val activeGrid = state.activeSteps
        .takeIf { pad.canUsePatternSteps() }
        ?.repeatGridForPad(state.selectedPad)
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
                enabled = pad.canUsePatternSteps(),
                active = activeGrid == grid,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = true,
            )
        }
    }
}

@Composable
private fun BeatProductionDock(
    stepsVisible: Boolean,
    height: Dp,
    onOpenAdd: () -> Unit,
    onOpenScratch: () -> Unit,
    onStepsVisibleChange: (Boolean) -> Unit,
) {
    ProductionDock(
        items = beatProductionDockItems(stepsVisible),
        height = height,
        gap = 4.dp,
        handlers = mapOf(
            ProductionDockIntent.SHOW_QUICK to { onStepsVisibleChange(false) },
            ProductionDockIntent.SHOW_STEPS to { onStepsVisibleChange(true) },
            ProductionDockIntent.OPEN_ADD to onOpenAdd,
            ProductionDockIntent.OPEN_SCRATCH to onOpenScratch,
        ),
    )
}

@Composable
private fun LayerStudio(
    state: SamplerUiState,
    initialPage: LayerStudioPage,
    onDismiss: () -> Unit,
    onToggleVocalRecording: () -> Unit,
    viewModel: SamplerViewModel,
) {
    var pageName by rememberSaveable(initialPage.name) { mutableStateOf(initialPage.name) }
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
        BankStrip(state.selectedBank, 44.dp, viewModel::selectPlayableBank)
        PadPageStrip(state, 27.dp, viewModel::selectPlayablePadPage)
        BeatSoundRail(
            pads = state.visiblePads(),
            selectedPad = state.selectedPad,
            onSelectPad = viewModel::selectPlayablePad,
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
                    enabled = state.selectedPadModel().canUsePatternSteps(),
                    active = state.selectedPadModel().canUsePatternSteps() &&
                        state.activeSteps.repeatGridForPad(state.selectedPad) == grid,
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
    var targetName by rememberSaveable { mutableStateOf(ScratchTarget.SOURCE.name) }
    var sensitivityName by rememberSaveable { mutableStateOf(ScratchSensitivity.NORMAL.name) }
    val target = ScratchTarget.entries.firstOrNull { it.name == targetName } ?: ScratchTarget.SOURCE
    val sensitivity = ScratchSensitivity.entries.firstOrNull { it.name == sensitivityName }
        ?: ScratchSensitivity.NORMAL
    val audio = state.currentAudio
    val range = state.sourceScratchRange()
    val selectedPad = state.selectedPadModel()
    val selectedPadAudio = selectedPad.audio
    val scratchActive = state.sourceScratchActive || state.scratchingPadIndex != null
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
            text = "元曲かPADを選び、感度を決めて円盤を左右へ擦ります",
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ScratchTarget.entries.forEach { option ->
                MachineButton(
                    label = option.label,
                    onClick = { targetName = option.name },
                    enabled = if (option == ScratchTarget.SOURCE) audio != null else state.pads.any { it.isAssigned },
                    active = target == option,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
        if (target == ScratchTarget.SOURCE) {
            SourceEditorWaveform(
                state = state,
                viewModel = viewModel,
                condensed = true,
                selectSliceOnly = true,
                modifier = Modifier.fillMaxWidth().height(108.dp),
            )
        } else {
            BankStrip(state.selectedBank, 38.dp, viewModel::selectPlayableBank)
            BeatSoundRail(
                pads = state.visiblePads(),
                selectedPad = state.selectedPad,
                onSelectPad = viewModel::selectPlayablePad,
                onPreviewPad = viewModel::triggerPad,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ScratchSensitivity.entries.forEach { option ->
                MachineButton(
                    label = option.label,
                    onClick = { sensitivityName = option.name },
                    active = sensitivity == option,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFF11100C),
                shape = CircleShape,
                modifier = Modifier
                    .size(180.dp)
                    .border(5.dp, if (scratchActive) DeckLamp else DeckInk, CircleShape)
                    .pointerInput(audio?.id, range, target, sensitivity, state.selectedPad) {
                        detectDragGestures(
                            onDragStart = {
                                if (target == ScratchTarget.SOURCE) viewModel.beginSourceScratch()
                                else viewModel.beginScratch()
                            },
                            onDragEnd = { viewModel.endScratch() },
                            onDragCancel = { viewModel.endScratch() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                viewModel.updateScratchSpeed(dragAmount.x / sensitivity.divisor)
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
            text = if (target == ScratchTarget.PAD && selectedPad.isAssigned && selectedPadAudio != null) {
                "PAD ${bankName(selectedPad.bankIndex)}-%02d  ${formatDeckTime(selectedPad.startFrame, selectedPadAudio.sampleRate)}–${formatDeckTime(selectedPad.endFrame, selectedPadAudio.sampleRate)}"
                    .format(selectedPad.indexInBank + 1)
            } else if (audio != null && range != null) {
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
    val audibleSteps = state.activeSteps.audibleStepKeys(state.pads).size
    val ready = state.activeSteps.hasAudiblePatternContent(state.pads)
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
private fun SourceEditorWaveform(
    state: SamplerUiState,
    viewModel: SamplerViewModel,
    modifier: Modifier,
    condensed: Boolean = false,
    selectSliceOnly: Boolean = false,
    waveformTapOverride: ((Int) -> Unit)? = null,
) {
    val audio = state.currentAudio
    MachinePanel(modifier = modifier) {
        if (audio == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emptySourceWaveformLabel(state.isLoading),
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
                    if (waveformTapOverride != null) waveformTapOverride(frame)
                    else if (manualChopEnabled) viewModel.addSliceMarker(frame)
                    else viewModel.selectSliceAt(frame)
                },
                playheadFrame = state.sourcePlayheadFrame,
                fillCanvas = true,
                showViewportControls = false,
                compactViewportControls = !condensed,
                showTimeReadout = !condensed,
                showInteractionHint = !condensed || selectSliceOnly,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BankStrip(
    selectedBank: Int,
    height: Dp,
    onSelectBank: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compactLabels: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(SamplerConfig.BANK_COUNT) { bank ->
            MachineButton(
                label = bankSwitchLabel(
                    bankIndex = bank,
                    selected = selectedBank == bank,
                    compact = compactLabels,
                ),
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(maxOf(height, 34.dp)),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(SamplerConfig.PAD_PAGES_PER_BANK) { page ->
            val assigned = state.assignedPadCountOnPage(page)
            val selected = state.selectedPadPage() == page
            val selectedPad = state.selectedPadModel()
            val selectedPadLabel = "${bankRoleFor(selectedPad.bankIndex).letter}-%02d"
                .format(selectedPad.indexInBank + 1)
            MachineButton(
                label = padPageSwitchLabel(
                    pageIndex = page,
                    selected = selected,
                    assignedCount = assigned,
                    selectedPadLabel = selectedPadLabel,
                ),
                onClick = { onSelectPage(page) },
                active = selected,
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
            active = state.isLoading || state.hasPlaybackActivity() || state.hasRecordingActivity(),
            alert = state.hasRecordingActivity(),
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
private fun NewSourceActionButton(
    state: SamplerUiState,
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    if (requiresNewProjectConfirmation(state) && !active) {
        ConfirmActionButton(
            label = label,
            confirmLabel = "PAD・ビート消去\nもう一度",
            onConfirm = onConfirm,
            enabled = enabled,
            modifier = modifier,
        )
    } else {
        MachineButton(
            label = label,
            onClick = onConfirm,
            enabled = enabled,
            active = active,
            modifier = modifier,
            compact = true,
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
    active: Boolean? = null,
    compact: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = when {
        pressed -> DeckPadLit
        active == true -> DeckLamp
        else -> DeckPanelDark
    }
    val foreground = if (pressed || active == true) Color(0xFF2A1000) else DeckInk
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
                active?.let { this.selected = it }
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
                fontSize = if (compact) {
                    compactMachineButtonFontSizeSp(fontScale).sp
                } else {
                    10.sp
                },
                lineHeight = if (compact) {
                    compactMachineButtonLineHeightSp(fontScale).sp
                } else {
                    11.sp
                },
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
    isLoading: Boolean,
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
                emptySourceWaveformLabel(isLoading),
                color = Color(0xFF766B50),
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
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
