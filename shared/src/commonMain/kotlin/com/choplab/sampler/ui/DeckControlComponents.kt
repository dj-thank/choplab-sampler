package com.choplab.sampler.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

internal val DeckDisabledInk = Color(0xFF655A43)
internal val DeckFocusInk = Color(0xFF245534)
private val DeckFont = FontFamily.Monospace

@Composable
internal fun MachineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean? = null,
    compact: Boolean = false,
    contentLabel: String? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    val accessibleDescription = remember(label, contentLabel) {
        contentLabel ?: machineButtonAccessibleDescription(label)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val hoverLift = hovered && enabled && !pressed
    val background = when {
        !enabled -> DeckPanel
        pressed -> DeckPadLit
        active == true -> if (hoverLift) DeckLampHover else DeckLamp
        hoverLift -> DeckPanelHover
        else -> DeckPanelDark
    }
    val foreground = when {
        !enabled -> DeckDisabledInk
        pressed || active == true -> Color(0xFF2A1000)
        else -> DeckInk
    }
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = if (!enabled || pressed) 0.dp else if (hoverLift) 4.dp else 2.dp,
        modifier = modifier
            .graphicsLayer { translationY = if (pressed) 1.5.dp.toPx() else 0f }
            .border(if (focused) 3.dp else 1.5.dp, if (focused) DeckFocusInk else DeckInk, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = accessibleDescription
                active?.let { this.selected = it }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Use the smallest existing control budget. Avoid a subcomposition in every button.
            val copy = remember(label, compact, fontScale) {
                deckButtonCopy(label, compact, fontScale, if (compact) 36f else 44f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = copy.primary,
                    color = foreground,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Black,
                    fontSize = copy.primarySizeSp.sp,
                    lineHeight = copy.primaryLineHeightSp.sp,
                    textAlign = TextAlign.Center,
                    maxLines = copy.primaryMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                copy.secondary?.let { caption ->
                    Text(
                        text = caption,
                        color = foreground,
                        fontFamily = DeckFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MachineSlider(
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
            fontSize = 11.sp,
            modifier = Modifier.width(58.dp),
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = deckSliderColors(),
            modifier = Modifier.weight(1f).semantics {
                contentDescription = parameterAccessibleName(label)
                stateDescription = valueLabel
            },
        )
        Text(
            valueLabel,
            color = DeckInk.copy(alpha = if (enabled) 1f else 0.4f),
            fontFamily = DeckFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(60.dp),
        )
    }
}

@Composable
internal fun StepperControl(
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
            label = "−",
            contentLabel = parameterAdjustmentDescription(label, value, increase = false),
            onClick = onDecrease,
            enabled = enabled,
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            compact = true,
        )
        ValueDisplay(
            label = label,
            value = value,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        MachineButton(
            label = "+",
            contentLabel = parameterAdjustmentDescription(label, value, increase = true),
            onClick = onIncrease,
            enabled = enabled,
            modifier = Modifier.width(48.dp).fillMaxHeight(),
            compact = true,
        )
    }
}

@Composable
internal fun ValueDisplay(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "${parameterAccessibleName(label)}、$value" }
            .background(DeckInk, RoundedCornerShape(5.dp))
            .border(1.dp, Color.Black, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color(0xFFC7BA98),
            fontFamily = DeckFont,
            fontSize = 9.sp,
            maxLines = 1,
        )
        Text(
            value,
            color = DeckGreen,
            fontFamily = DeckFont,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun WorkflowStageButton(
    number: Int,
    stage: WorkflowStage,
    selected: Boolean,
    compact: Boolean,
    enabled: Boolean,
    blockedReason: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val background = when {
        !enabled -> DeckPanel
        pressed -> DeckPadLit
        selected -> if (hovered && enabled) DeckLampHover else DeckLamp
        hovered && enabled -> DeckPanelHover
        else -> DeckPanelDark
    }
    Surface(
        color = background,
        contentColor = DeckInk,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = if (pressed) 0.dp else if (hovered && enabled) 4.dp else 2.dp,
        modifier = modifier
            .border(if (focused || selected) 3.dp else 1.5.dp, if (focused) DeckFocusInk else DeckInk, RoundedCornerShape(6.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                contentDescription = "工程$number ${stage.label} ${stage.caption}"
                stateDescription = workflowStageStateDescription(
                    WorkflowStageAvailability(enabled, blockedReason),
                )
                this.selected = selected
                if (!enabled) disabled()
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
                color = if (enabled) DeckInk else DeckDisabledInk,
                fontFamily = DeckFont,
                fontWeight = FontWeight.Black,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1,
            )
            if (!compact) {
                Text(
                    text = stage.caption,
                    color = DeckDisabledInk,
                    fontFamily = DeckFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
