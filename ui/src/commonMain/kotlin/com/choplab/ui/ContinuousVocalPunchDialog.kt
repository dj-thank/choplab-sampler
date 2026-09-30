package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.vocal.*

@Composable internal fun CEVocalPunchDialog(controller: VocalPunchController, onAction: (ContinuousEditorAction) -> Unit) {
    Dialog(controller::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 860.dp).fillMaxHeight(.94f).testTag("ce-vocal-punch-dialog")) {
            VocalPunchPanel(controller, { onAction(ContinuousEditorAction.OpenVocalTakes) }, { onAction(ContinuousEditorAction.CloseVocalPunch) })
        }
    }
}
