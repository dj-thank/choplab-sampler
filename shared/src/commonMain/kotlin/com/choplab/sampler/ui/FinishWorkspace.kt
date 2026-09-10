package com.choplab.sampler.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.*

private val DeckFont = FontFamily.Monospace

@Composable
internal fun FinishWorkspace(
    state: SamplerUiState,
    metrics: DeckLayoutMetrics,
    onExportBeat: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onBackToArrange: () -> Unit,
    viewModel: SamplerDeckController,
) {
    val gap = metrics.gapDp.dp
    val assignedPads = state.pads.count(PadModel::isAssigned)
    val audibleSteps = state.audiblePlaybackStepCount()
    val ready = state.hasAudiblePlaybackPatternContent()
    val readiness = finishReadinessPresentation(ready)
    val clearAction = finishClearActionPresentation()
    val summary: @Composable (Modifier) -> Unit = { modifier ->
        MachinePanel(modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Text(
                    text = readiness.title,
                    color = if (ready) DeckGreen else DeckLamp,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Black,
                    fontSize = if (metrics.density == DeckDensity.COMPACT) 12.sp else 16.sp,
                    maxLines = 2,
                )
                Text(
                    text = readiness.guidance,
                    color = Color(0xFFE8DDBF),
                    fontFamily = DeckFont,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
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
                enabled = ready && externalDocumentActionsEnabled(state),
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
                    enabled = externalDocumentActionsEnabled(state),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = "制作を開く\nOPEN PROJECT",
                    onClick = onOpenProject,
                    enabled = externalDocumentActionsEnabled(state),
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
                    enabled = state.undoRequestEnabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                )
                MachineButton(
                    label = "やり直す\nREDO",
                    onClick = viewModel::redoEdit,
                    enabled = state.redoRequestEnabled,
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
                    label = clearAction.label,
                    confirmLabel = clearAction.confirmLabel,
                    onConfirm = viewModel::clearAllPattern,
                    enabled = state.hasAnyPatternSteps(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = finishWorkspaceGeometry(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
            gapDp = metrics.gapDp,
        )
        if (layout.sideBySide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                summary(Modifier.weight(1.2f).fillMaxHeight())
                actions(Modifier.weight(0.8f).fillMaxHeight())
            }
        } else {
            // Only this non-performance screen scrolls. The four-stage header and STOP stay fixed.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                summary(Modifier.fillMaxWidth().height(layout.summaryHeightDp.dp))
                actions(Modifier.fillMaxWidth().height(layout.actionsHeightDp.dp))
            }
        }
    }
}
