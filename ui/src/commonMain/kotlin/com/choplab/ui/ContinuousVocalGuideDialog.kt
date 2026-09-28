package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.ai.*
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CEVocalGuideDialog(controller: VocalGuideController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val close = { onAction(ContinuousEditorAction.CloseVocalGuide) }
    val canClose = state.phase != VocalGuidePhase.APPLYING
    Dialog({ if (canClose) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 860.dp).fillMaxHeight(.94f).testTag("ce-vocal-guide-dialog")) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) },
                        Modifier.weight(1f), tag = "ce-vocal-guide-stop")
                    CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f), enabled = canClose, tag = "ce-vocal-guide-close")
                }
                VocalGuidePanel(controller, Modifier.weight(1f))
            }
        }
    }
}
