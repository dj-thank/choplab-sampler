package com.choplab.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.choplab.core.ai.*
import com.choplab.core.model.ProjectLimits
import com.choplab.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Host puts this panel in its VOCAL dialog/sheet and owns the supplied controller for that dialog lifetime. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricProposalPanel(controller: LyricProposalController, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    // Deliberately remember, never rememberSaveable: key and prompts do not enter saved state or document files.
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }
    var structure by remember { mutableStateOf("") }
    var rhyme by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(LyricLanguage.JAPANESE) }
    var style by remember { mutableStateOf(LyricStyle.SONG) }
    var startBeat by remember { mutableStateOf("1") }
    var beatsPerLine by remember { mutableStateOf("4") }
    var details by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var invalidInput by remember { mutableStateOf(false) }
    val busy = state.phase == LyricProposalPhase.GENERATING || state.phase == LyricProposalPhase.APPLYING
    DisposableEffect(controller) { onDispose { controller.close() } }
    Surface(modifier.fillMaxWidth().testTag("ai-lyrics-panel")) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.ai_lyrics_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.ai_lyrics_provider))
            Text(stringResource(Res.string.ai_lyrics_disclosure))
            Text(stringResource(Res.string.ai_lyrics_data_terms), style = MaterialTheme.typography.bodySmall)
            if (controller.availability == LyricProviderAvailability.UNVERIFIED) Text(stringResource(Res.string.ai_lyrics_unverified))
            TextButton(onClick = { uri.openUri("https://ai.google.dev/gemini-api/terms") }) { Text(stringResource(Res.string.ai_lyrics_terms)) }
            TextButton(onClick = { uri.openUri("https://ai.google.dev/gemini-api/docs/pricing") }) { Text(stringResource(Res.string.ai_lyrics_pricing)) }
            if (state.phase != LyricProposalPhase.PREVIEW && state.phase != LyricProposalPhase.APPLYING && state.phase != LyricProposalPhase.APPLIED) {
                Input(model, { model = it.take(87); consent = false }, Res.string.ai_lyrics_model, "ai-model", !busy)
                OutlinedTextField(value = key, onValueChange = { key = it.take(256); consent = false }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("ai-key"), label = { Text(stringResource(Res.string.ai_lyrics_key)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Input(theme, { theme = it.take(1_024); consent = false }, Res.string.ai_lyrics_theme, "ai-theme", !busy)
                Input(mood, { mood = it.take(512); consent = false }, Res.string.ai_lyrics_mood, "ai-mood", !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(language == LyricLanguage.JAPANESE, { language = LyricLanguage.JAPANESE; consent = false }, enabled = !busy,
                        label = { Text(stringResource(Res.string.ai_lyrics_japanese)) })
                    FilterChip(language == LyricLanguage.ENGLISH, { language = LyricLanguage.ENGLISH; consent = false }, enabled = !busy,
                        label = { Text(stringResource(Res.string.ai_lyrics_english)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(style == LyricStyle.SONG, { style = LyricStyle.SONG; consent = false }, enabled = !busy,
                        label = { Text(stringResource(Res.string.ai_lyrics_song)) })
                    FilterChip(style == LyricStyle.RAP, { style = LyricStyle.RAP; consent = false }, enabled = !busy,
                        label = { Text(stringResource(Res.string.ai_lyrics_rap)) })
                }
                TextButton(onClick = { details = !details }, enabled = !busy) { Text(stringResource(Res.string.ai_lyrics_details)) }
                if (details) {
                    Input(structure, { structure = it.take(1_024); consent = false }, Res.string.ai_lyrics_structure, "ai-structure", !busy)
                    Input(rhyme, { rhyme = it.take(512); consent = false }, Res.string.ai_lyrics_rhyme, "ai-rhyme", !busy)
                    Input(keep, { keep = it.take(4_096); consent = false }, Res.string.ai_lyrics_keep, "ai-keep", !busy)
                }
                Text(stringResource(Res.string.ai_lyrics_placement))
                Input(startBeat, { startBeat = it.take(8) }, Res.string.ai_lyrics_start, "ai-start", !busy)
                Input(beatsPerLine, { beatsPerLine = it.take(2) }, Res.string.ai_lyrics_beats, "ai-beats", !busy)
                Row {
                    Checkbox(consent, { consent = it }, enabled = !busy, modifier = Modifier.testTag("ai-consent"))
                    Text(stringResource(Res.string.ai_lyrics_consent), Modifier.weight(1f))
                }
                Button(onClick = {
                    scope.launch {
                        val permitted = consent
                        consent = false
                        val input = runCatching { LyricRequest(model.trim(), theme, mood, language, style, structure, rhyme, keep) }.getOrNull()
                        val first = startBeat.toLongOrNull()
                        val length = beatsPerLine.toIntOrNull()
                        val secret = runCatching { SessionApiKey(key) }.getOrNull()
                        if (input == null || first == null || first !in 1..(ProjectLimits.MAX_TIMELINE_TICKS / ProjectLimits.PPQ) ||
                            length == null || length !in 1..16 || secret == null) {
                            secret?.close(); invalidInput = true
                        } else {
                            invalidInput = false
                            controller.generate(input, secret, (first - 1) * ProjectLimits.PPQ, length, permitted)
                        }
                    }
                }, enabled = controller.availability == LyricProviderAvailability.AVAILABLE && !busy && consent && key.isNotBlank() && model.isNotBlank() && theme.isNotBlank() && state.retryRemainingSeconds == 0L,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai-generate")) {
                    Text(stringResource(Res.string.ai_lyrics_generate))
                }
            }
            if (invalidInput) Text(stringResource(Res.string.ai_lyrics_invalid_input), color = MaterialTheme.colorScheme.error)
            if (state.phase == LyricProposalPhase.GENERATING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.ai_lyrics_generating))
            }
            state.proposal?.let { proposal ->
                Text(proposal.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.ai_lyrics_replace, state.before.size, state.placed.size))
                Text(stringResource(Res.string.ai_lyrics_preview_only), style = MaterialTheme.typography.bodySmall)
                if (state.before.isNotEmpty()) {
                    Text(stringResource(Res.string.ai_lyrics_before), style = MaterialTheme.typography.titleMedium)
                    for (line in state.before) Text(line.text)
                }
                Text(stringResource(Res.string.ai_lyrics_after), style = MaterialTheme.typography.titleMedium)
                var index = 0
                for (section in proposal.sections) {
                    Text(stringResource(Res.string.ai_lyrics_section, section.name, stringResource(sectionText(section.kind)), section.bars), style = MaterialTheme.typography.titleMedium)
                    for (line in section.lines) {
                        val placed = state.placed[index++]
                        Text(line.text)
                        Text(stringResource(Res.string.ai_lyrics_line_detail, placed.startTick / ProjectLimits.PPQ + 1,
                            placed.endTick / ProjectLimits.PPQ + 1, line.reading, line.mora?.toString() ?: "—", line.rhymeVowels ?: "—"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.phase == LyricProposalPhase.PREVIEW) Button(onClick = { scope.launch { controller.applyPreview() } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai-apply")) { Text(stringResource(Res.string.ai_lyrics_apply)) }
            }
            if (state.usage != null) {
                val usage = state.usage!!
                val unknown = stringResource(Res.string.ai_lyrics_unreported)
                Text(stringResource(Res.string.ai_lyrics_usage, usage.inputTokens?.toString() ?: unknown,
                    usage.outputTokens?.toString() ?: unknown, usage.totalTokens?.toString() ?: unknown))
            } else if (state.phase == LyricProposalPhase.PREVIEW || state.phase == LyricProposalPhase.APPLIED) Text(stringResource(Res.string.ai_lyrics_usage_unknown))
            state.modelVersion?.let { Text(stringResource(Res.string.ai_lyrics_model_used, it)) }
            if (state.proposal != null || state.failure?.costUnknown == true) Text(stringResource(Res.string.ai_lyrics_cost_unknown))
            state.failure?.let { Text(stringResource(problemText(it.problem)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai-failure")) }
            if (state.retryRemainingSeconds > 0) Text(stringResource(Res.string.ai_lyrics_retry_after, state.retryRemainingSeconds))
            if (state.phase == LyricProposalPhase.APPLIED) Text(stringResource(Res.string.ai_lyrics_applied), Modifier.testTag("ai-applied"))
            if (state.phase == LyricProposalPhase.GENERATING || state.phase == LyricProposalPhase.PREVIEW) OutlinedButton(
                onClick = { scope.launch { controller.cancel() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai-cancel")) {
                Text(stringResource(Res.string.ai_lyrics_cancel))
            }
            OutlinedButton(onClick = { key = ""; controller.close(); onClose() }, enabled = state.phase != LyricProposalPhase.APPLYING,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai-close")) { Text(stringResource(Res.string.ai_lyrics_close)) }
        }
    }
}

@Composable private fun Input(value: String, change: (String) -> Unit, label: StringResource, tag: String, enabled: Boolean) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth().testTag(tag), enabled = enabled,
        label = { Text(stringResource(label)) }, minLines = 1, maxLines = 4)
}
private fun problemText(problem: LyricAiProblem): StringResource = when (problem) {
    LyricAiProblem.INVALID_INPUT -> Res.string.ai_lyrics_invalid_input
    LyricAiProblem.PROVIDER_UNVERIFIED -> Res.string.ai_lyrics_unverified
    LyricAiProblem.CONSENT_REQUIRED -> Res.string.ai_lyrics_consent_required
    LyricAiProblem.AUTHENTICATION -> Res.string.ai_lyrics_auth
    LyricAiProblem.UNKNOWN_MODEL -> Res.string.ai_lyrics_unknown_model
    LyricAiProblem.RATE_LIMITED -> Res.string.ai_lyrics_rate
    LyricAiProblem.OFFLINE -> Res.string.ai_lyrics_offline
    LyricAiProblem.TIMEOUT -> Res.string.ai_lyrics_timeout
    LyricAiProblem.CANCELLED -> Res.string.ai_lyrics_cancelled
    LyricAiProblem.PROVIDER_REJECTED -> Res.string.ai_lyrics_provider_rejected
    LyricAiProblem.INVALID_RESPONSE -> Res.string.ai_lyrics_invalid_response
    LyricAiProblem.STALE_DOCUMENT -> Res.string.ai_lyrics_stale
    LyricAiProblem.APPLY_REJECTED -> Res.string.ai_lyrics_apply_rejected
    LyricAiProblem.CLOSED -> Res.string.ai_lyrics_closed
}
private fun sectionText(kind: LyricSectionKind): StringResource = when (kind) {
    LyricSectionKind.INTRO -> Res.string.ai_lyrics_intro
    LyricSectionKind.VERSE -> Res.string.ai_lyrics_verse
    LyricSectionKind.CHORUS -> Res.string.ai_lyrics_chorus
    LyricSectionKind.BRIDGE -> Res.string.ai_lyrics_bridge
    LyricSectionKind.OUTRO -> Res.string.ai_lyrics_outro
}
