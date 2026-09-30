package com.choplab.ui.mixer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.ui.*
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CEExportOptions(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit) {
    val enabled = state.permits(ContinuousCapability.EXPORT_WAV)
    Column {
        for (bits in listOf(24, 16)) {
            val label = stringResource(Res.string.mixer_export_bits, bits)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.exportBits == bits, { onAction(ContinuousEditorAction.ExportBits(bits)) }, enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = CEColor.Orange, unselectedColor = CEColor.Cream,
                        disabledSelectedColor = CEColor.Orange.copy(alpha = .5f), disabledUnselectedColor = CEColor.Tan),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ce-export-bits-$bits")
                        .semantics { contentDescription = label })
                Text(label, color = CEColor.Cream, fontSize = 16.sp)
            }
        }
        val label = stringResource(Res.string.mixer_export_tail)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(state.exportTail, { onAction(ContinuousEditorAction.ExportTail(it)) }, enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = CEColor.Ink, checkedTrackColor = CEColor.Orange,
                    uncheckedThumbColor = CEColor.Cream, uncheckedTrackColor = CEColor.Border, uncheckedBorderColor = CEColor.Cream),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ce-export-tail")
                    .semantics { contentDescription = label })
            Text(label, Modifier.weight(1f), color = CEColor.Cream, fontSize = 16.sp)
        }
        Text(stringResource(if (state.exportTail) Res.string.mixer_export_tail_hint else Res.string.mixer_export_exact_hint),
            fontSize = 14.sp, color = CEColor.Tan)
    }
}
