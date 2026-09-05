package com.choplab.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.bankRoleFor

/** Selecting a sound never starts/stops playback or changes its loop mode. */
@Composable
fun BeatLoopSoundChoices(pads: List<PadModel>, selectedPad: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= 420.dp) 3 else 2
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pads.isEmpty()) Text("このBANKには音がありません。チョップで音を入れるか、ドラムを追加してください。", color = DeckInk, fontSize = 12.sp)
            pads.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { pad ->
                        val audio = requireNotNull(pad.audio)
                        val selected = pad.globalIndex == selectedPad
                        val label = "${bankRoleFor(pad.bankIndex).letter}-${"%02d".format(pad.indexInBank + 1)}"
                        val wave = remember(audio.id, pad.startFrame, pad.endFrame) { automaticRangeEnvelope(audio, pad.startFrame, pad.endFrame, 64) }
                        Column(Modifier.weight(1f).fillMaxHeight()
                            .background(if (selected) DeckLamp else DeckPanelDark, RoundedCornerShape(6.dp))
                            .border(if (selected) 2.dp else 1.dp, DeckInk, RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onSelect(pad.globalIndex) }
                            .semantics {
                                contentDescription = "音を選ぶ $label。${audio.name}"
                                this.selected = selected
                                stateDescription = if (pad.playMode == PadPlayMode.LOOP) "ループ設定あり" else "まだループしていません"
                            }.padding(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label + if (pad.playMode == PadPlayMode.LOOP) " • LOOP" else "", color = DeckInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(audio.name, color = DeckInk, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                                val mid = size.height / 2f
                                for (i in wave.minimums.indices) {
                                    val x = i.toFloat() / wave.minimums.size * size.width
                                    drawLine(DeckInk, Offset(x, mid - wave.maximums[i] * mid), Offset(x, mid - wave.minimums[i] * mid), 2f)
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
