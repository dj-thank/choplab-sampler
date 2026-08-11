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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.LaneStepState
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.bankRoleFor
import com.choplab.sampler.model.laneStepState

private val BeatBoardShape = RoundedCornerShape(7.dp)
private val BeatBoardFont = FontFamily.Monospace

@Composable
fun BeatLaneBoard(
    pads: List<PadModel>,
    activeSteps: Set<Int>,
    currentStep: Int,
    selectedPad: Int,
    onSelectPad: (Int) -> Unit,
    onToggleStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(pads.size == SamplerConfig.PAD_COUNT)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0907), BeatBoardShape)
            .border(1.5.dp, Color(0xFF2B261C), BeatBoardShape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(SamplerConfig.BANK_COUNT) { bank ->
            val bankStart = bank * SamplerConfig.PADS_PER_BANK
            val bankEnd = bankStart + SamplerConfig.PADS_PER_BANK
            val selectedInBank = selectedPad.takeIf { it in bankStart until bankEnd }
                ?: pads.subList(bankStart, bankEnd).firstOrNull(PadModel::isAssigned)?.globalIndex
                ?: bankStart
            BeatLane(
                bankIndex = bank,
                padIndex = selectedInBank,
                activeSteps = activeSteps,
                currentStep = currentStep,
                selected = selectedPad in bankStart until bankEnd,
                onSelectPad = { onSelectPad(selectedInBank) },
                onToggleStep = { step ->
                    onSelectPad(selectedInBank)
                    onToggleStep(step)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun BeatLane(
    bankIndex: Int,
    padIndex: Int,
    activeSteps: Set<Int>,
    currentStep: Int,
    selected: Boolean,
    onSelectPad: () -> Unit,
    onToggleStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bankRole = bankRoleFor(bankIndex)
    val accent = bankRoleAccent(bankIndex)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier
                .width(74.dp)
                .fillMaxHeight()
                .background(if (selected) accent else Color(0xFF211E17), RoundedCornerShape(5.dp))
                .border(1.dp, if (selected) Color(0xFFFFE7B0) else Color(0xFF4A422E), RoundedCornerShape(5.dp))
                .clickable(role = Role.Button, onClick = onSelectPad)
                .semantics { contentDescription = "BANK ${bankRole.letter} ${bankRole.japaneseLabel} PAD ${padIndex % 16 + 1}" }
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "${bankRole.letter}  ${bankRole.japaneseLabel}",
                color = if (selected) Color(0xFFFFF2CF) else Color(0xFFCFC29E),
                fontFamily = BeatBoardFont,
                fontWeight = FontWeight.Black,
                fontSize = 7.sp,
                maxLines = 1,
            )
            Text(
                text = "PAD %02d".format(padIndex % SamplerConfig.PADS_PER_BANK + 1),
                color = if (selected) Color.White else Color(0xFF837858),
                fontFamily = BeatBoardFont,
                fontSize = 6.sp,
                maxLines = 1,
            )
        }
        repeat(SamplerConfig.STEP_COUNT) { step ->
            val state = laneStepState(activeSteps, bankIndex, padIndex, step)
            val playhead = currentStep == step
            val background = when (state) {
                LaneStepState.SELECTED_SOUND -> DeckLamp
                LaneStepState.OTHER_SOUND -> accent
                LaneStepState.OFF -> if (step % 4 == 0) Color(0xFF29251B) else Color(0xFF171510)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(background, RoundedCornerShape(3.dp))
                    .border(
                        width = if (playhead) 2.dp else if (step % 4 == 0) 1.dp else 0.5.dp,
                        color = if (playhead) Color.White else Color(0xFF4B432F),
                        shape = RoundedCornerShape(3.dp),
                    )
                    .clickable(role = Role.Button) { onToggleStep(step) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "${bankRole.japaneseLabel} ステップ${step + 1} ${state.name}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (step % 4 == 0 || state != LaneStepState.OFF) {
                    Text(
                        text = if (state == LaneStepState.OTHER_SOUND) "•" else (step + 1).toString(),
                        color = when (state) {
                            LaneStepState.SELECTED_SOUND -> Color(0xFF2A1500)
                            LaneStepState.OTHER_SOUND -> Color(0xFFFFE5B2)
                            LaneStepState.OFF -> Color(0xFF766B4E)
                        },
                        fontFamily = BeatBoardFont,
                        fontWeight = FontWeight.Black,
                        fontSize = 5.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun BeatSoundRail(
    pads: List<PadModel>,
    selectedPad: Int,
    onSelectPad: (Int) -> Unit,
    onPreviewPad: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(pads.size == SamplerConfig.PADS_PER_BANK)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(2) { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(8) { column ->
                    val pad = pads[row * 8 + column]
                    val selected = pad.globalIndex == selectedPad
                    val role = bankRoleFor(pad.bankIndex)
                    val label = when {
                        !pad.isAssigned -> "%02d  EMPTY".format(pad.indexInBank + 1)
                        else -> "%02d  %s".format(
                            pad.indexInBank + 1,
                            pad.audio?.name?.substringBeforeLast('.')?.take(11).orEmpty(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (selected) DeckLamp else if (pad.isAssigned) bankRoleAccent(pad.bankIndex) else DeckPanelDark,
                                RoundedCornerShape(4.dp),
                            )
                            .border(if (selected) 2.dp else 1.dp, DeckInk, RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button) {
                                onSelectPad(pad.globalIndex)
                                if (pad.isAssigned) onPreviewPad(pad.globalIndex)
                            }
                            .semantics {
                                contentDescription = "${role.japaneseLabel} PAD ${pad.indexInBank + 1} ${if (pad.isAssigned) "試聴" else "空"}"
                            }
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color(0xFF2A1500) else Color(0xFFFFE7B5),
                            fontFamily = BeatBoardFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
