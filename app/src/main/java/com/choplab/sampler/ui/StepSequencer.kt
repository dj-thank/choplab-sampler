package com.choplab.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(SamplerConfig.STEP_COUNT) { step ->
            val active = stepKey(selectedPad, step) in activeSteps
            val playhead = currentStep == step
            val shape = RoundedCornerShape(3.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(if (active) DeckLamp else DeckPanel, shape)
                    .border(
                        width = when {
                            playhead -> 3.dp
                            step % 4 == 0 -> 2.dp
                            else -> 1.dp
                        },
                        color = if (playhead) DeckInk else DeckInk.copy(alpha = 0.78f),
                        shape = shape,
                    )
                    .clickable { onToggleStep(step) }
                    .semantics {
                        contentDescription = "ステップ ${step + 1} ${if (active) "オン" else "オフ"}"
                    },
            )
        }
    }
}
