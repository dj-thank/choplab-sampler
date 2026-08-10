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

@Composable
fun StepSequencer(
    selectedPad: Int,
    activeSteps: Set<Int>,
    currentStep: Int,
    onToggleStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 8,
    gap: Dp = 4.dp,
) {
    require(SamplerConfig.STEP_COUNT % columns == 0)
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
                    val step = row * columns + column
                    StepCell(
                        step = step,
                        active = stepKey(selectedPad, step) in activeSteps,
                        playhead = currentStep == step,
                        onClick = { onToggleStep(step) },
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .background(if (active) DeckLamp else DeckPanel, shape)
            .border(
                width = when {
                    playhead -> 3.dp
                    step % 4 == 0 -> 2.dp
                    else -> 1.dp
                },
                color = if (playhead) Color(0xFFFFF0D0) else DeckInk,
                shape = shape,
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = "ステップ ${step + 1} ${if (active) "オン" else "オフ"}" +
                    if (playhead) "。現在の再生位置" else ""
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (step + 1).toString(),
            color = if (active) Color(0xFF2A1500) else DeckInk,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (step % 4 == 0) FontWeight.Black else FontWeight.Bold,
            fontSize = 9.sp,
        )
    }
}
