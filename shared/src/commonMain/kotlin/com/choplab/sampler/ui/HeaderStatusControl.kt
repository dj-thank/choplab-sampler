package com.choplab.sampler.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.selectedPadModel

/** Read-only details. Opening/closing never routes, starts audio, edits, saves or resets. */
@Composable
internal fun HeaderStatusControl(
    state: SamplerUiState,
    stage: WorkflowStage,
    largeText: Boolean,
    showInlineStatus: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .border(if (focused) 2.dp else 0.dp, if (focused) DeckGreen else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactions, indication = null) { expanded = true }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "おとひろい、現在${stage.label}。状態の詳細"
                onClick(label = "状態の詳細を読む") { expanded = true; true }
            }
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "おとひろい",
            color = Color(0xFFFFF1CF),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = if (largeText || showInlineStatus) 11.sp else 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (showInlineStatus) state.statusMessage else "${stage.label}・状態を見る ›",
            color = DeckGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = if (largeText) 9.sp else 10.sp,
            lineHeight = 11.sp,
            maxLines = if (showInlineStatus) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }) {
            Surface(color = DeckPanel, contentColor = DeckInk, shape = RoundedCornerShape(14.dp)) {
                Column(
                    Modifier.widthIn(max = 520.dp).heightIn(max = 600.dp).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("現在の状態", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val next = workflowNextActionPresentation(state)
                        Text(state.statusMessage, fontSize = 16.sp, lineHeight = 24.sp)
                        Text("${next.title}\n${next.guidance}", fontSize = 14.sp, lineHeight = 22.sp)
                        Text("素材：${state.currentAudio?.name ?: "まだ読み込まれていません"}", fontSize = 14.sp)
                        val pad = state.selectedPadModel()
                        Text("選択：BANK ${('A'.code + pad.bankIndex).toChar()} / PAD ${pad.indexInBank + 1}", fontSize = 14.sp)
                    }
                    MachineButton(
                        label = "閉じる",
                        onClick = { expanded = false },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                }
            }
        }
    }
}
