package com.choplab.sampler.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(2) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(8) { column ->
                    val step = row * 8 + column
                    val enabled = stepKey(selectedPad, step) in activeSteps
                    StepCell(
                        step = step,
                        active = enabled,
                        playhead = currentStep == step,
                        onClick = { onToggleStep(step) },
                        modifier = Modifier.weight(1f),
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
    val shape = RoundedCornerShape(6.dp)
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (playhead) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Surface(
        color = color,
        contentColor = contentColor,
        shape = shape,
        modifier = modifier
            .aspectRatio(0.86f)
            .border(if (playhead) 3.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = (step + 1).toString(),
                modifier = Modifier.padding(2.dp),
                fontWeight = if (step % 4 == 0) FontWeight.Black else FontWeight.Medium,
                fontSize = 11.sp,
            )
        }
    }
}
