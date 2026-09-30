package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.vocal.*

@Composable internal fun CEVocalTakeDialog(controller: VocalTakeController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val close = { onAction(ContinuousEditorAction.CloseVocalTakes) }
    Dialog({ if (state.phase != VocalPhase.APPLYING) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 860.dp).fillMaxHeight(.94f).testTag("ce-vocal-takes-dialog")) {
            VocalTakePanel(controller, close)
        }
    }
}
