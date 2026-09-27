package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.ai.LyricProposalController
import com.choplab.ui.ai.LyricProposalPanel
import com.choplab.ui.ai.LyricProposalPhase
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The form scrolls independently; closing and stopping playback stay reachable at large text sizes. */
@Composable internal fun CELyricProposalDialog(controller: LyricProposalController,
                                              onAction: (ContinuousEditorAction) -> Unit) {
    val proposal by controller.state.collectAsState()
    val canClose = proposal.phase != LyricProposalPhase.APPLYING
    val close = { onAction(ContinuousEditorAction.CloseLyricProposal) }
    Dialog(onDismissRequest = { if (canClose) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 860.dp).fillMaxHeight(.94f).testTag("ce-lyric-proposal-dialog")) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) },
                        Modifier.weight(1f), tag = "ce-lyric-proposal-stop")
                    CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f),
                        enabled = canClose, tag = "ce-lyric-proposal-close")
                }
                LyricProposalPanel(controller, close, Modifier.weight(1f))
            }
        }
    }
}
