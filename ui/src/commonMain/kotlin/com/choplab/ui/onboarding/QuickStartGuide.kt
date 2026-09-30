package com.choplab.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun QuickStartHelp(controller: QuickStartController) {
    val state by controller.state.collectAsState()
    OutlinedButton(controller::open, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("next-help-open")) {
        Text(stringResource(Res.string.quick_start_help))
    }
    state.problem?.let {
        Text(stringResource(if (it == QuickStartProblem.SAVE_SETTINGS) Res.string.quick_start_save_failed else Res.string.quick_start_read_failed),
            Modifier.testTag("next-help-settings-notice").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
    }
}

/** A single scrolling explanation; normal input order and fixed Stop/Close remain reachable at large text. */
@Composable fun QuickStartGuide(controller: QuickStartController, canChooseAudio: Boolean, onChooseAudio: () -> Unit, onStop: () -> Unit) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = controller::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(.96f).fillMaxHeight(.94f).testTag("next-quick-start")
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { controller.dismiss(); true } else false
            }) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("next-help-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.quick_start_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(Res.string.quick_start_intro))
                    for ((title, body) in listOf(Res.string.ce_stage_capture to Res.string.quick_start_capture,
                        Res.string.ce_stage_chop to Res.string.quick_start_chop, Res.string.ce_stage_beat to Res.string.quick_start_beat,
                        Res.string.ce_stage_save to Res.string.quick_start_save)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(body))
                    }
                    Text(stringResource(Res.string.quick_start_controls_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(Res.string.quick_start_controls), Modifier.testTag("next-help-keyboard"))
                    Text(stringResource(Res.string.quick_start_reopen), Modifier.testTag("next-help-reopen"))
                }
                Button({ controller.dismiss(); onChooseAudio() }, enabled = canChooseAudio,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("next-help-import")) { Text(stringResource(Res.string.ce_load_audio)) }
                OutlinedButton(onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("next-help-stop")) { Text(stringResource(Res.string.ce_stop_all)) }
                OutlinedButton(controller::dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(closeFocus).testTag("next-help-close")) {
                    Text(stringResource(Res.string.quick_start_continue))
                }
            }
        }
        LaunchedEffect(controller) { closeFocus.requestFocus() }
    }
}
