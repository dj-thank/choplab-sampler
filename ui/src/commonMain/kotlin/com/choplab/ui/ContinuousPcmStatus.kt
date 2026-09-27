package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.engine.PcmReadStatus
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** PCM starvation is distinct from a device underrun, and from normal ahead-of-playhead prefetch. */
@Immutable data class ContinuousPcmReadout(
    val status: PcmReadStatus = PcmReadStatus.READY,
    val underrunFrames: Long = 0,
    val droppedRequests: Long = 0,
)

@Composable internal fun cePcmStatus(status: PcmReadStatus): String = stringResource(when (status) {
    PcmReadStatus.READY -> Res.string.ce_pcm_ready
    PcmReadStatus.PREFETCHING -> Res.string.ce_pcm_preparing
    PcmReadStatus.FAILED -> Res.string.ce_pcm_failed
    PcmReadStatus.CLOSED -> Res.string.ce_pcm_closed
})

/** Uses the existing brand space: no extra row takes height away from the playable PADs. */
@Composable internal fun CEPcmHealth(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
    readout: () -> ContinuousEditorReadout, refreshKey: Long, normal: @Composable () -> Unit) {
    val latestRead by rememberUpdatedState(readout)
    var pcm by remember { mutableStateOf(readout().pcm) }
    LaunchedEffect(refreshKey) {
        // SOURCE/song frames do not invalidate the header; only PCM health changes do.
        while (true) withFrameNanos { pcm = latestRead().pcm }
    }
    var acknowledgedFrames by remember { mutableLongStateOf(0) }
    var acknowledgedDrops by remember { mutableLongStateOf(0) }
    var open by remember { mutableStateOf(false) }
    // Reattaching an output starts fresh engine counters; old acknowledgements must not hide new misses.
    LaunchedEffect(pcm.underrunFrames, pcm.droppedRequests) {
        if (pcm.underrunFrames < acknowledgedFrames) acknowledgedFrames = 0
        if (pcm.droppedRequests < acknowledgedDrops) acknowledgedDrops = 0
    }
    val failed = pcm.status == PcmReadStatus.FAILED || pcm.status == PcmReadStatus.CLOSED
    val notice = failed || pcm.underrunFrames > acknowledgedFrames || pcm.droppedRequests > acknowledgedDrops
    val status = cePcmStatus(pcm.status)
    if (notice) CEButton(stringResource(Res.string.ce_pcm_short), { open = true },
        Modifier.semantics { stateDescription = status; liveRegion = LiveRegionMode.Polite },
        tag = "ce-pcm-status") else normal()
    if (!open) return
    fun close() { acknowledgedFrames = pcm.underrunFrames; acknowledgedDrops = pcm.droppedRequests; open = false }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("ce-pcm-dialog"), color = CEColor.Cream) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.ce_pcm_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(status, fontSize = 16.sp, modifier = Modifier.testTag("ce-pcm-state"))
                        Text(stringResource(Res.string.ce_pcm_missing, pcm.underrunFrames.toString()), fontSize = 14.sp)
                        Text(stringResource(Res.string.ce_pcm_queue, pcm.droppedRequests.toString()), fontSize = 14.sp)
                        Text(stringResource(Res.string.ce_pcm_reload_help), fontSize = 14.sp)
                    }
                    CEActionButton(stringResource(Res.string.ce_pcm_reload), ContinuousEditorAction.ReloadAudio, state,
                        ContinuousCapability.RELOAD_AUDIO, { onAction(it); close() }, Modifier.fillMaxWidth(), tag = "ce-pcm-reload")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CEButton(stringResource(Res.string.ce_stop_all), { onAction(ContinuousEditorAction.StopAll) },
                            Modifier.weight(1f), primary = true, tag = "ce-pcm-stop")
                        CEButton(stringResource(Res.string.ce_close), ::close, Modifier.weight(1f), tag = "ce-pcm-close")
                    }
                }
            }
        }
    }
}
