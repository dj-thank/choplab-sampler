package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.pattern.PatternPhase
import com.choplab.ui.pattern.StepPatternController
import com.choplab.ui.pattern.StepPatternPanel
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** A scrollable pattern draft keeps close and all-stop in a fixed header. */
@Composable internal fun CEStepPatternsDialog(controller: StepPatternController, onAction: (ContinuousEditorAction) -> Unit) {
    val state by controller.state.collectAsState()
    val canClose = state.phase != PatternPhase.APPLYING
    val close = { onAction(ContinuousEditorAction.CloseStepPatterns) }
    Dialog(onDismissRequest = { if (canClose) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1000.dp).fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("ce-step-patterns-dialog")) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) },
                        Modifier.weight(1f), tag = "ce-step-patterns-stop")
                    CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f), enabled = canClose,
                        tag = "ce-step-patterns-close")
                }
                StepPatternPanel(controller, close, Modifier.weight(1f))
            }
        }
    }
}
