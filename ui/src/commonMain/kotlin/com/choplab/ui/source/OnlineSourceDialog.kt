package com.choplab.ui.source

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Non-blocking modal composition: host owns the controller and returns from its Open action immediately. */
@Composable
fun OnlineSourceDialog(controller: OnlineSourceController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { scope.launch { if (controller.requestClose()) onClose() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        OnlineSourcePanel(controller, onClose, Modifier.widthIn(max = 780.dp).fillMaxWidth(.96f).fillMaxHeight(.96f))
    }
}
