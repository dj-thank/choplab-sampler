package com.choplab.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey

internal data class StepCellPresentation(
    val stepIndex: Int,
    val stepKey: Int,
    val row: Int,
    val column: Int,
    val active: Boolean,
    val playhead: Boolean,
    val contentDescription: String,
) {
    val stepNumber: Int
        get() = stepIndex + 1
}

internal fun stepCellPresentations(
    selectedPad: Int,
    activeSteps: Set<Int>,
    currentStep: Int,
    enabled: Boolean,
    columns: Int,
): List<StepCellPresentation> {
    require(columns > 0 && SamplerConfig.STEP_COUNT % columns == 0)
    return List(SamplerConfig.STEP_COUNT) { stepIndex ->
        val active = enabled && stepKey(selectedPad, stepIndex) in activeSteps
        val playhead = currentStep == stepIndex
        StepCellPresentation(
            stepIndex = stepIndex,
            stepKey = stepKey(selectedPad, stepIndex),
            row = stepIndex / columns,
            column = stepIndex % columns,
            active = active,
            playhead = playhead,
            contentDescription = "ステップ ${stepIndex + 1} " +
                if (enabled) {
                    "${if (active) "オン" else "オフ"}" +
                        if (playhead) "。現在の再生位置" else ""
                } else {
                    "配置できません"
                },
        )
    }
}

@Composable
fun StepSequencer(
    selectedPad: Int,
    activeSteps: Set<Int>,
    currentStep: Int,
    onToggleStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    columns: Int = 8,
    gap: Dp = 4.dp,
) {
    val cells = stepCellPresentations(
        selectedPad = selectedPad,
        activeSteps = activeSteps,
        currentStep = currentStep,
        enabled = enabled,
        columns = columns,
    )
    val rows = SamplerConfig.STEP_COUNT / columns
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(columns) { column ->
                    val cell = cells[row * columns + column]
                    StepCell(
                        step = cell.stepIndex,
                        active = cell.active,
                        playhead = cell.playhead,
                        enabled = enabled,
                        accessibilityDescription = cell.contentDescription,
                        onClick = { onToggleStep(cell.stepIndex) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCell(
    step: Int,
    active: Boolean,
    playhead: Boolean,
    enabled: Boolean,
    accessibilityDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .background(if (!enabled) DeckPanelDark else if (active) DeckLamp else DeckPanel, shape)
            .border(
                width = when {
                    playhead -> 3.dp
                    step % 4 == 0 -> 2.dp
                    else -> 1.dp
                },
                color = if (playhead) Color(0xFFFFF0D0) else DeckInk,
                shape = shape,
            )
            .clickable(enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = accessibilityDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (step + 1).toString(),
            color = if (!enabled) DeckInk.copy(alpha = 0.45f) else if (active) Color(0xFF2A1500) else DeckInk,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (step % 4 == 0) FontWeight.Black else FontWeight.Bold,
            fontSize = 9.sp,
        )
    }
}
