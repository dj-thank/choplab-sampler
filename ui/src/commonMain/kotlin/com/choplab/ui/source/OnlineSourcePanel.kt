@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.choplab.ui.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Scrollable candidates/details; output Stop, cancellation and Close remain outside that scroll. */
@Composable
fun OnlineSourcePanel(controller: OnlineSourceController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val candidateScroll = rememberScrollState()
    val detailScroll = key(state.selectedId) { rememberScrollState() }
    val detailFocus = remember { FocusRequester() }
    LaunchedEffect(state.showingDetails, state.worker.details?.id) {
        if (state.showingDetails && state.worker.details != null) detailFocus.requestFocus()
    }
    fun action(value: OnlineSourceAction) { scope.launch { controller.dispatch(value) } }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Surface(modifier.fillMaxSize().testTag("online-panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val phase = state.worker.phase
                val status = when {
                    state.applied -> Res.string.online_applied
                    state.applying -> Res.string.online_applying
                    phase == OnlinePhase.CANCELLED && state.worker.busy -> Res.string.online_cancelling
                    phase == OnlinePhase.CANDIDATES && state.worker.candidates.isEmpty() -> Res.string.online_empty
                    phase == OnlinePhase.SEARCHING -> Res.string.online_searching
                    phase == OnlinePhase.INSPECTING -> Res.string.online_inspecting
                    phase == OnlinePhase.DOWNLOADING -> Res.string.online_downloading
                    phase == OnlinePhase.SAVING -> Res.string.online_saving
                    phase == OnlinePhase.SAVED -> Res.string.online_saved
                    phase == OnlinePhase.FAILED -> when (state.worker.failedOperation) {
                        OnlinePhase.SEARCHING -> Res.string.online_failed_search
                        OnlinePhase.INSPECTING -> Res.string.online_failed_details
                        OnlinePhase.SAVING -> Res.string.online_failed_saving
                        else -> Res.string.online_failed_download
                    }
                    phase == OnlinePhase.CANCELLED -> Res.string.online_cancelled
                    phase == OnlinePhase.DETAILS -> Res.string.online_choose_format
                    else -> Res.string.online_ready
                }
                Text(stringResource(status), Modifier.testTag("online-status").semantics { liveRegion = LiveRegionMode.Polite })
                if (state.worker.busy) {
                    val progress = state.worker.progress?.coerceIn(0, 100)
                    if (phase == OnlinePhase.DOWNLOADING && progress != null) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$progress%", Modifier.testTag("online-progress"))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (phase == OnlinePhase.DOWNLOADING) Text(stringResource(Res.string.online_progress_unknown), Modifier.testTag("online-progress"))
                    }
                }

                (state.issue ?: state.worker.problem)?.let {
                    Text(stringResource(onlineProblemText(it)), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("online-problem"))
                }
                if (phase == OnlinePhase.FAILED) Text(stringResource(Res.string.online_retry_hint), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (state.showingDetails) detailScroll else candidateScroll).testTag("online-scroll"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.showingDetails) {
                Text(stringResource(Res.string.online_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.online_intro))
                OutlinedTextField(state.query, { action(OnlineSourceAction.Query(it)) }, enabled = state.editable,
                    label = { Text(stringResource(Res.string.online_query)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { action(OnlineSourceAction.Search) }),
                    modifier = Modifier.fillMaxWidth().testTag("online-query"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (catalog in OnlineCatalog.entries) FilterChip(state.catalog == catalog,
                        { action(OnlineSourceAction.Catalog(catalog)) }, enabled = state.editable,
                        label = { Text(stringResource(if (catalog == OnlineCatalog.VIDEOS) Res.string.online_videos else Res.string.online_music)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("online-catalog-${catalog.name.lowercase()}"))
                }
                Button({ action(OnlineSourceAction.Search) }, enabled = state.canSearch,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-search")) {
                    Text(stringResource(Res.string.online_search))
                }
                if (!state.inputMatches && state.worker.candidates.isNotEmpty()) Text(stringResource(Res.string.online_query_changed))
                for ((index, candidate) in state.worker.candidates.withIndex()) {
                    OutlinedButton({ action(OnlineSourceAction.Inspect(candidate.id)) }, enabled = state.canInspect, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-candidate-$index")
                            .semantics { selected = state.selectedId == candidate.id }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                            Text(candidate.uploader)
                            Text(duration(candidate.durationSeconds))
                        }
                    }
                }
                }
                if (state.showingDetails) {
                TextButton({ action(OnlineSourceAction.BackToCandidates) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-back")) {
                    Text(stringResource(Res.string.online_back_candidates))
                }
                if (state.worker.phase == OnlinePhase.FAILED && state.selectedId != null) OutlinedButton(
                    { action(OnlineSourceAction.Inspect(requireNotNull(state.selectedId))) }, enabled = state.canInspect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-recheck")) { Text(stringResource(Res.string.online_recheck)) }
                state.worker.details?.let { detail ->
                    HorizontalDivider()
                    Text(detail.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("online-detail-heading")
                        .focusRequester(detailFocus).focusable().semantics { heading() })
                    if (detail.artwork != null) Image(detail.artwork, stringResource(Res.string.online_artwork),
                        Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("online-artwork"), contentScale = ContentScale.Fit)
                    else Text(stringResource(if (state.worker.artworkLoading) Res.string.online_artwork_loading else Res.string.online_artwork_unavailable))
                    val unknown = stringResource(Res.string.online_unknown)
                    Text(stringResource(Res.string.online_artist, detail.artist ?: unknown))
                    Text(stringResource(Res.string.online_album, detail.album ?: unknown))
                    Text(stringResource(Res.string.online_uploader, detail.uploader.ifBlank { unknown }))
                    Text(stringResource(Res.string.online_verified, stringResource(when (detail.uploaderVerified) {
                        true -> Res.string.online_yes
                        false -> Res.string.online_no
                        null -> Res.string.online_unknown
                    })))
                    Text(duration(detail.durationSeconds))
                    Text(stringResource(Res.string.online_format_observations), style = MaterialTheme.typography.bodySmall)
                    for ((index, format) in detail.formats.withIndex()) {
                        OutlinedButton({ action(OnlineSourceAction.Format(format.id)) },
                            enabled = state.canInspect && detail.id == state.selectedId && format.problem(state.worker.maxDownloadBytes) == null, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-format-$index")
                                .semantics { selected = detail.selectedFormat == format.id }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${format.container.uppercase()} / ${format.codec ?: unknown}")
                                Text(stringResource(Res.string.online_rate_channels, format.sampleRate?.toString() ?: unknown,
                                    format.channels?.toString() ?: unknown))
                                val bitrate = format.bitrate?.let {
                                    stringResource(if (format.approximateBitrate) Res.string.online_bitrate_estimated else Res.string.online_bitrate, it)
                                } ?: stringResource(Res.string.online_bitrate_unknown)
                                Text(bitrate)
                                Text(stringResource(Res.string.online_format_bytes, format.bytes?.toString() ?: unknown))
                                format.problem(state.worker.maxDownloadBytes)?.let { Text(stringResource(onlineProblemText(it)), Modifier.testTag("online-format-problem-$index")) }
                                Text(stringResource(Res.string.online_track, format.trackName ?: unknown, format.language ?: unknown,
                                    format.trackType ?: unknown))
                                Text(stringResource(Res.string.online_drc, stringResource(when (format.dynamicRangeCompressed) {
                                    true -> Res.string.online_yes
                                    false -> Res.string.online_no
                                    null -> Res.string.online_unknown
                                })))
                            }
                        }
                    }
                    Button({ action(OnlineSourceAction.Save) }, enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-save")) {
                        Text(stringResource(Res.string.online_save))
                    }
                }
                state.worker.saved?.let { saved ->
                    Text(stringResource(Res.string.online_saved_title, saved.title), Modifier.testTag("online-saved"))
                    Text(stringResource(Res.string.online_save_only))
                    Button({ action(OnlineSourceAction.UseOriginal) }, enabled = state.canUse,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-use")) {
                        Text(stringResource(Res.string.online_use))
                    }
                    if (state.availability != OnlineAvailability.EDITABLE) Text(stringResource(if (state.availability == OnlineAvailability.RECORDING)
                        Res.string.online_problem_recording else Res.string.online_problem_busy))
                }
                }
            }
            HorizontalDivider()
            if (state.worker.busy || state.worker.artworkLoading) OutlinedButton({ action(OnlineSourceAction.Cancel) },
                enabled = !state.applying && state.worker.phase != OnlinePhase.CANCELLED,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-cancel")) {
                Text(stringResource(Res.string.online_cancel))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(controller::stopAll, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("online-stop")) {
                    Text(stringResource(Res.string.online_stop))
                }
                OutlinedButton({ scope.launch { if (controller.requestClose()) onClose() } }, enabled = !state.applying,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("online-close")) {
                    Text(stringResource(Res.string.online_close))
                }
            }
        }
    }
}

@Composable private fun duration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return stringResource(Res.string.online_duration_unknown)
    val whole = seconds.toLong()
    return stringResource(Res.string.online_duration, "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}")
}
internal fun onlineProblemText(problem: OnlineProblem): StringResource = when (problem) {
    OnlineProblem.INVALID_INPUT -> Res.string.online_problem_input
    OnlineProblem.UNAVAILABLE -> Res.string.online_problem_unavailable
    OnlineProblem.RESTRICTED -> Res.string.online_problem_restricted
    OnlineProblem.RATE_LIMITED -> Res.string.online_problem_rate
    OnlineProblem.TOO_LARGE -> Res.string.online_problem_large
    OnlineProblem.MALFORMED_RESPONSE -> Res.string.online_problem_response
    OnlineProblem.NETWORK -> Res.string.online_problem_network
    OnlineProblem.TIMED_OUT -> Res.string.online_problem_timeout
    OnlineProblem.CANCELLED -> Res.string.online_cancelled
    OnlineProblem.CLOSED -> Res.string.online_problem_closed
    OnlineProblem.BUSY -> Res.string.online_problem_busy
    OnlineProblem.UNSUPPORTED_FORMAT -> Res.string.online_problem_format
    OnlineProblem.FORMAT_CHANGED -> Res.string.online_problem_changed
    OnlineProblem.INVALID_AUDIO -> Res.string.online_problem_audio
    OnlineProblem.STORAGE -> Res.string.online_problem_storage
    OnlineProblem.RECORDING -> Res.string.online_problem_recording
    OnlineProblem.STALE_DOCUMENT -> Res.string.online_problem_stale
    OnlineProblem.APPLY_REJECTED -> Res.string.online_problem_apply
}
