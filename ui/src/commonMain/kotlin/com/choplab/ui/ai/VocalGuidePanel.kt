@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.choplab.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.choplab.core.ai.*
import com.choplab.core.model.WordTimingOrigin
import com.choplab.ui.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Only the body scrolls. Host supplies fixed Stop/Close controls outside it. */
@Composable fun VocalGuidePanel(controller: VocalGuideController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val preview by controller.preview.state.collectAsState()
    val scope = rememberCoroutineScope()
    val busy = state.phase in listOf(VocalGuidePhase.PREPARING, VocalGuidePhase.APPLYING)
    val editable = !busy && state.phase !in listOf(VocalGuidePhase.APPLIED, VocalGuidePhase.CLOSED, VocalGuidePhase.FAILED)
    var first by remember { mutableStateOf(state.startBeat.toString()) }
    var voiceMenu by remember { mutableStateOf(false) }
    var previewFrame by remember { mutableLongStateOf(0) }
    LaunchedEffect(controller, preview.phase) {
        while (preview.phase == VocalPreviewPhase.PLAYING) { previewFrame = controller.preview.frame(); delay(30) }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).testTag("vocal-guide-panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.vocal_guide_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.vocal_offline))
        OutlinedTextField(state.title, { value -> scope.launch { controller.placement(title = value) } }, enabled = editable,
            label = { Text(stringResource(Res.string.vocal_composition_title)) }, modifier = Modifier.fillMaxWidth().testTag("vocal-title"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (language in LyricLanguage.entries) FilterChip(state.language == language,
                { scope.launch { controller.placement(language = language) } }, enabled = editable,
                label = { Text(stringResource(if (language == LyricLanguage.JAPANESE) Res.string.ai_lyrics_japanese else Res.string.ai_lyrics_english)) })
        }
        OutlinedTextField(first, { value -> first = value.take(8); first.toLongOrNull()?.let { scope.launch { controller.placement(startBeat = it) } } },
            enabled = editable, label = { Text(stringResource(Res.string.ai_lyrics_start)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("vocal-start"))
        Text(stringResource(Res.string.vocal_mode))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in FlowMode.entries) OutlinedButton(onClick = { scope.launch { controller.mode(null, mode) } }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(modeLabel(mode))) }
        }
        if (state.loadingVoices) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { voiceMenu = true }, enabled = editable && !state.loadingVoices,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-voice")) {
                Text(state.voice?.name ?: stringResource(Res.string.vocal_choose_voice))
            }
            DropdownMenu(voiceMenu, { voiceMenu = false }, Modifier.heightIn(max = 300.dp)) {
                state.voices.filter { it.language == state.language }.forEach { voice -> DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                    voiceMenu = false; scope.launch { controller.voice(voice) }
                }) }
            }
        }
        Text(stringResource(Res.string.vocal_rate, ratio(state.settings.ratePermille / 1000.0)))
        Slider(state.settings.ratePermille.toFloat(), { value -> scope.launch { controller.settings(state.settings.copy(ratePermille = value.toInt())) } },
            valueRange = 500f..2000f, enabled = editable, modifier = Modifier.testTag("vocal-rate"))
        if (state.voice?.supportsPitch == true) {
            Text(stringResource(Res.string.vocal_pitch, ratio(state.settings.pitchPermille / 1000.0)))
            Slider(state.settings.pitchPermille.toFloat(), { value -> scope.launch { controller.settings(state.settings.copy(pitchPermille = value.toInt())) } },
                valueRange = 500f..2000f, enabled = editable, modifier = Modifier.testTag("vocal-pitch"))
        }
        Text(stringResource(Res.string.vocal_timing_hint))
        val plan = state.plan
        if (plan != null) {
            plan.structure.sections.forEach { section -> Text(stringResource(Res.string.vocal_section, section.name, section.bars)) }
        }
        state.rows.forEach { row -> key(row.line.id) {
            HorizontalDivider()
            val planned = plan?.rows?.firstOrNull { it.line.id == row.line.id }
            val prepared = row.prepared
            val active = prepared != null && preview.assetHash == prepared.asset.hash && preview.phase == VocalPreviewPhase.PLAYING
            val line = prepared?.line ?: row.line
            val tick = if (prepared == null) line.startTick else line.startTick + (line.endTick - line.startTick) * previewFrame / prepared.asset.frames
            val highlight = MaterialTheme.colorScheme.primary
            Text(buildAnnotatedString {
                if (line.words.isEmpty()) append(line.text) else line.words.forEach { word ->
                    withStyle(SpanStyle(fontWeight = if (active && tick >= word.startTick && tick < word.endTick) FontWeight.Bold else FontWeight.Normal,
                        color = if (active && tick >= word.startTick && tick < word.endTick) highlight else androidx.compose.ui.graphics.Color.Unspecified)) { append(word.text) }
                }
            }, Modifier.testTag("vocal-line-${line.id}"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(row.reading, { value -> scope.launch { controller.reading(row.line.id, value) } }, enabled = editable,
                label = { Text(stringResource(Res.string.vocal_reading)) }, modifier = Modifier.fillMaxWidth().testTag("vocal-reading-${row.line.id}"))
            OutlinedButton(onClick = { scope.launch { controller.reading(row.line.id, row.reading, confirm = true) } }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("vocal-confirm-${row.line.id}")) {
                Text(stringResource(if (row.confirmed) Res.string.vocal_reading_confirmed else Res.string.vocal_confirm_reading))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in FlowMode.entries) FilterChip(row.mode == mode, { scope.launch { controller.mode(row.line.id, mode) } }, enabled = editable,
                    label = { Text(stringResource(modeLabel(mode))) })
            }
            planned?.let {
                Text(stringResource(Res.string.vocal_density, it.units, it.sixteenths, it.reading.rhymeVowels ?: "—"))
                if (it.advice != null) Text(stringResource(if (it.advice == FlowAdvice.SPLIT_OR_TWO_BARS) Res.string.vocal_dense else Res.string.vocal_sparse))
            }
            if (prepared != null) {
                val returned = prepared.line.words.count { it.timingOrigin == WordTimingOrigin.RETURNED }
                val estimated = prepared.line.words.count { it.timingOrigin == WordTimingOrigin.ESTIMATED }
                Text(stringResource(Res.string.vocal_prepared, ratio(prepared.speed), returned, estimated))
            }
            if (state.preparingLine == row.line.id) LinearProgressIndicator(Modifier.fillMaxWidth())
            row.failure?.let { Failure(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { controller.prepare(row.line.id, regenerate = prepared != null) } },
                    enabled = editable && plan != null && state.voice != null, modifier = Modifier.heightIn(min = 48.dp).testTag("vocal-generate-${row.line.id}")) {
                    Text(stringResource(if (prepared == null) Res.string.vocal_generate_line else Res.string.vocal_regenerate))
                }
                OutlinedButton(onClick = { scope.launch { controller.listen(row.line.id) } }, enabled = editable && prepared != null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("vocal-listen-${row.line.id}")) { Text(stringResource(Res.string.vocal_listen)) }
            }
        } }
        if (state.issue != null) Text(stringResource(Res.string.vocal_correct_reading), color = MaterialTheme.colorScheme.error)
        state.failure?.let { Failure(it) }
        preview.failure?.let { Failure(it) }
        if (plan?.hasDensityAdvice == true) Row {
            Checkbox(state.densityConfirmed, { value -> scope.launch { controller.confirmDensity(value) } }, enabled = editable,
                modifier = Modifier.testTag("vocal-density-confirm"))
            Text(stringResource(Res.string.vocal_confirm_density), Modifier.weight(1f))
        }
        Button(onClick = { scope.launch { controller.prepare() } }, enabled = editable && plan != null && state.voice != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-generate-all")) { Text(stringResource(Res.string.vocal_generate_all)) }
        Button(onClick = { scope.launch { controller.apply() } }, enabled = state.phase == VocalGuidePhase.READY && first.toLongOrNull() == state.startBeat &&
            (plan?.hasDensityAdvice != true || state.densityConfirmed), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocal-apply")) {
            Text(stringResource(Res.string.vocal_apply))
        }
        if (state.phase == VocalGuidePhase.APPLIED) Text(stringResource(Res.string.vocal_applied), Modifier.testTag("vocal-applied"))
    }
}

private fun modeLabel(mode: FlowMode) = when (mode) {
    FlowMode.ONE_BAR -> Res.string.vocal_one_bar
    FlowMode.TWO_BARS -> Res.string.vocal_two_bars
    FlowMode.DOUBLE_TIME -> Res.string.vocal_double_time
}
@Composable private fun Failure(failure: TtsFailure) {
    Text(stringResource(failureLabel(failure.problem)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("vocal-failure"))
    failure.speedRequired?.let { Text(stringResource(Res.string.vocal_required_speed, ratio(it))) }
}
private fun failureLabel(problem: TtsProblem): StringResource = when (problem) {
    TtsProblem.NO_OFFLINE_VOICE, TtsProblem.UNAVAILABLE, TtsProblem.VOICE_CHANGED -> Res.string.vocal_missing_voice
    TtsProblem.INVALID_INPUT, TtsProblem.STALE_DOCUMENT -> Res.string.vocal_stale
    TtsProblem.UNSUPPORTED_SETTINGS -> Res.string.vocal_unsupported
    TtsProblem.CANNOT_FIT, TtsProblem.TOO_SHORT -> Res.string.vocal_cannot_fit
    TtsProblem.SILENT_AUDIO -> Res.string.vocal_silent
    TtsProblem.RECORDING, TtsProblem.BUSY -> Res.string.vocal_busy
    TtsProblem.CANCELLED, TtsProblem.CLOSED -> Res.string.vocal_cancelled
    TtsProblem.DENSITY_CONFIRMATION -> Res.string.vocal_confirm_density
    TtsProblem.MEMORY_LIMIT -> Res.string.vocal_memory_limit
    TtsProblem.TOO_LARGE, TtsProblem.CACHE_FULL -> Res.string.vocal_limit
    else -> Res.string.vocal_failed
}

private fun ratio(value: Double): String {
    val hundredths = (value * 100).roundToLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
