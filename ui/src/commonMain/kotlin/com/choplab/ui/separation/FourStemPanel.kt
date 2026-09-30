@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.choplab.ui.separation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.core.separation.*
import com.choplab.ui.CEButton
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Fixed Stop/Close remain reachable while the body scrolls, including large accessibility fonts. */
@Composable fun FourStemDialog(controller: FourStemController, onStop: () -> Unit, onClose: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.close() } }
    val state by controller.state.collectAsState()
    val canClose = state.phase != FourStemPhase.APPLYING
    val close = { if (canClose) { controller.close(); onClose() } }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 860.dp).fillMaxHeight(.94f).testTag("four-stem-dialog")) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_stop_all), { controller.cancel(); onStop() },
                        Modifier.weight(1f), tag = "four-stem-stop")
                    CEButton(stringResource(Res.string.ce_close), close, Modifier.weight(1f), enabled = canClose, tag = "four-stem-close")
                }
                FourStemPanel(controller, Modifier.weight(1f))
            }
        }
    }
}

@Composable fun FourStemPanel(controller: FourStemController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var firstBar by remember { mutableStateOf(state.firstBar.toString()) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).testTag("four-stem-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.four_stem_title), style = MaterialTheme.typography.headlineSmall)
        Text(state.sourceName ?: stringResource(Res.string.ce_no_source))
        Text(stringResource(Res.string.four_stem_local))
        state.memoryReceipt?.let { memory ->
            val source = stringResource(when (memory.source) {
                SeparationMemorySource.MAC_FREE_AND_FILE_BACKED -> Res.string.four_stem_memory_mac
                SeparationMemorySource.WINDOWS_GLOBAL_MEMORY_STATUS -> Res.string.four_stem_memory_windows
                SeparationMemorySource.LINUX_MEM_AVAILABLE -> Res.string.four_stem_memory_linux
                SeparationMemorySource.ANDROID_ACTIVITY_MANAGER -> Res.string.four_stem_memory_android
            })
            Text(stringResource(Res.string.four_stem_memory_observation, memory.availableBytes / 1_048_576,
                memory.totalBytes / 1_048_576, source), Modifier.testTag("four-stem-memory"))
            Text(stringResource(Res.string.four_stem_memory_limit), Modifier.testTag("four-stem-memory-limit"))
        }
        Row(Modifier.fillMaxWidth()) {
            Checkbox(state.allowModelDownload, { value -> scope.launch { controller.settings(allowModelDownload = value) } },
                enabled = state.editable, modifier = Modifier.testTag("four-stem-download"))
            Text(stringResource(Res.string.four_stem_download), Modifier.weight(1f))
        }
        Button(onClick = { scope.launch { controller.start() } }, enabled = state.editable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("four-stem-start")) { Text(stringResource(Res.string.four_stem_start)) }
        if (state.phase == FourStemPhase.PREPARING || state.phase == FourStemPhase.CANCELLING) {
            val progress = state.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                LinearProgressIndicator(progress = { (progress.completedFrames.toDouble() / progress.totalFrames).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.four_stem_progress, (progress.completedFrames * 100 / progress.totalFrames).toInt()))
            }
            Text(stringResource(if (state.phase == FourStemPhase.CANCELLING) Res.string.four_stem_cancelling else Res.string.four_stem_preparing))
            OutlinedButton(onClick = { controller.cancel() }, enabled = state.phase == FourStemPhase.PREPARING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("four-stem-cancel")) { Text(stringResource(Res.string.four_stem_cancel)) }
        }
        Text(stringResource(Res.string.four_stem_heads))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mix in StemMix.entries) FilterChip(state.mix == mix, { scope.launch { controller.settings(mix = mix) } },
                enabled = state.editable, modifier = Modifier.heightIn(min = 48.dp).testTag("four-stem-mix-${mix.name}"), label = {
                    Text(stringResource(when (mix) {
                        StemMix.ALL -> Res.string.four_stem_all
                        StemMix.ACAPELLA -> Res.string.four_stem_acapella
                        StemMix.INSTRUMENTAL -> Res.string.four_stem_instrumental
                    }))
                })
        }
        OutlinedTextField(firstBar, { value ->
            firstBar = value.take(6)
            firstBar.toIntOrNull()?.let { scope.launch { controller.settings(firstBar = it) } }
        }, enabled = state.editable, singleLine = true, label = { Text(stringResource(Res.string.four_stem_first_bar)) },
            modifier = Modifier.fillMaxWidth().testTag("four-stem-first-bar"))
        Text(stringResource(Res.string.four_stem_apply_hint))
        state.problem?.let { problem ->
            Text(stringResource(when (problem) {
                SeparationProblem.MODEL_MISSING -> Res.string.four_stem_model_missing
                SeparationProblem.MODEL_INVALID -> Res.string.four_stem_model_invalid
                SeparationProblem.DOWNLOAD_FAILED -> Res.string.four_stem_download_failed
                SeparationProblem.RAM_UNAVAILABLE, SeparationProblem.LOW_MEMORY -> Res.string.four_stem_ram
                SeparationProblem.PCM_LIMIT -> Res.string.four_stem_pcm
                SeparationProblem.NO_SPACE, SeparationProblem.TOO_LONG -> Res.string.four_stem_space
                SeparationProblem.STALE_DOCUMENT -> Res.string.four_stem_stale
                SeparationProblem.RECORDING, SeparationProblem.BUSY -> Res.string.four_stem_busy
                SeparationProblem.CANCELLED, SeparationProblem.CLOSED -> Res.string.four_stem_cancelled
                else -> Res.string.four_stem_failed
            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("four-stem-problem"))
        }
        Button(onClick = { scope.launch { controller.apply() } }, enabled = state.editable && state.phase == FourStemPhase.READY && firstBar.toIntOrNull() == state.firstBar,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("four-stem-apply")) { Text(stringResource(Res.string.four_stem_apply)) }
        if (state.phase == FourStemPhase.APPLIED) Text(stringResource(Res.string.four_stem_applied), Modifier.testTag("four-stem-applied"))
    }
}
