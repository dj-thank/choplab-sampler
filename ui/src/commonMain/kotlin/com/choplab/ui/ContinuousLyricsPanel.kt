package com.choplab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.choplab.core.lyrics.*
import com.choplab.core.model.LyricLine
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun CELyricsButton(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit, modifier: Modifier = Modifier) {
    CEButton(stringResource(Res.string.ce_lyrics), { onAction(ContinuousEditorAction.Lyrics(LyricAction.Open)) }, modifier,
        enabled = state.permits(ContinuousCapability.LYRICS_EDIT), tag = "ce-lyrics-open")
}

/** One scrollable editor, with the current/next text driven only by the scoped audio-clock readout. */
@Composable internal fun CELyricsPanel(state: ContinuousEditorState, onAction: (ContinuousEditorAction) -> Unit,
                                      readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val lyrics = state.lyrics
    if (!lyrics.open) return
    val send = { action: LyricAction -> onAction(ContinuousEditorAction.Lyrics(action)) }
    val canEdit = state.permits(ContinuousCapability.LYRICS_EDIT)
    val files = state.permits(ContinuousCapability.LYRICS_FILES)
    val timing = remember(state.milliBpm) { LyricTiming(state.milliBpm) }
    AlertDialog(onDismissRequest = { send(LyricAction.Close) }, modifier = Modifier.testTag("ce-lyrics-panel"),
        title = { Text(stringResource(Res.string.ce_lyrics)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.ce_lyrics_timing_hint, state.milliBpm / 1000f))
                CELyricFollow(lyrics.lines, state, readout, refreshKey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_lyrics_play), { onAction(ContinuousEditorAction.PlaySong) }, Modifier.weight(1f),
                        enabled = state.permits(ContinuousCapability.SONG_PLAYBACK), tag = "ce-lyrics-play")
                    CEButton(stringResource(Res.string.ce_lyrics_pause), { onAction(ContinuousEditorAction.PauseSong) }, Modifier.weight(1f),
                        enabled = state.permits(ContinuousCapability.SONG_PLAYBACK), tag = "ce-lyrics-pause")
                }
                CEButton(stringResource(Res.string.ce_lyrics_add), { send(LyricAction.Add(readout().songFrame)) }, Modifier.fillMaxWidth(),
                    enabled = canEdit, tag = "ce-lyrics-add")
                CEButton(stringResource(Res.string.ai_lyrics_title), { onAction(ContinuousEditorAction.OpenLyricProposal) }, Modifier.fillMaxWidth(),
                    enabled = state.permits(ContinuousCapability.LYRIC_PROPOSAL), tag = "ce-lyrics-ai-open")
                CEButton(stringResource(Res.string.vocal_guide_title), { onAction(ContinuousEditorAction.OpenVocalGuide) }, Modifier.fillMaxWidth(),
                    enabled = state.permits(ContinuousCapability.VOCAL_GUIDE), tag = "ce-vocal-guide-open")
                CEButton(stringResource(Res.string.punch_title), { onAction(ContinuousEditorAction.OpenVocalPunch) }, Modifier.fillMaxWidth(),
                    enabled = state.permits(ContinuousCapability.VOCAL_PUNCH), tag = "ce-vocal-punch-open")
                CEButton(stringResource(Res.string.vocal_take_title), { onAction(ContinuousEditorAction.OpenVocalTakes) }, Modifier.fillMaxWidth(),
                    enabled = state.permits(ContinuousCapability.VOCAL_TAKES), tag = "ce-vocal-takes-open")
                CEButton(stringResource(Res.string.practice_title), { onAction(ContinuousEditorAction.OpenVocalPractice) }, Modifier.fillMaxWidth(),
                    enabled = state.permits(ContinuousCapability.VOCAL_PRACTICE), tag = "ce-practice-open")
                if (lyrics.lines.isEmpty()) Text(stringResource(Res.string.ce_lyrics_empty))
                else LazyColumn(Modifier.fillMaxWidth().height(160.dp).testTag("ce-lyrics-list")) {
                    items(lyrics.lines, key = { it.id }) { line ->
                        Text("${timing.tickToMilliseconds(line.startTick)} ms  ${line.text.ifBlank { "…" }}",
                            Modifier.fillMaxWidth().semantics { selected = line.id == lyrics.selectedId }
                                .clickable { send(LyricAction.Select(line.id)) }.padding(vertical = 12.dp).testTag("ce-lyric-${line.id}"),
                            fontWeight = if (line.id == lyrics.selectedId) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                lyrics.lines.firstOrNull { it.id == lyrics.selectedId }?.let { line -> key(line.id) { CELyricEdit(line, timing, canEdit, readout, send) } }
                CEButton(stringResource(Res.string.ce_lyrics_import), { send(LyricAction.Import) }, Modifier.fillMaxWidth(),
                    enabled = files, tag = "ce-lyrics-import")
                lyrics.preview?.let { preview ->
                    Text(stringResource(Res.string.ce_lyrics_import_preview, preview.lines.size, preview.milliBpm / 1000f), Modifier.testTag("ce-lyrics-preview"))
                    LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                        items(preview.lines, key = { it.id }) { Text(it.text, Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                    }
                    CEButton(stringResource(Res.string.ce_lyrics_replace), { send(LyricAction.ApplyImport) }, Modifier.fillMaxWidth(),
                        enabled = canEdit, tag = "ce-lyrics-apply-import")
                    CEButton(stringResource(Res.string.ce_lyrics_cancel), { send(LyricAction.CancelImport) }, Modifier.fillMaxWidth(), tag = "ce-lyrics-cancel-import")
                }
                CEButton(stringResource(Res.string.ce_lyrics_export_extended), { send(LyricAction.Export()) }, Modifier.fillMaxWidth(),
                    enabled = files && lyrics.lines.isNotEmpty(), tag = "ce-lyrics-export")
                CEButton(stringResource(Res.string.ce_lyrics_export_standard), { send(LyricAction.Export(LrcFormat.STANDARD)) }, Modifier.fillMaxWidth(),
                    enabled = files && lyrics.lines.isNotEmpty(), tag = "ce-lyrics-export-standard")
                lyrics.notice?.let { Text(lyricNotice(it), Modifier.testTag("ce-lyrics-notice")) }
                lyrics.issue?.let { issue ->
                    Text(lyricIssue(issue), Modifier.testTag("ce-lyrics-issue"), color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = { CEButton(stringResource(Res.string.ce_close), { send(LyricAction.Close) }, tag = "ce-lyrics-close") })
}

@Composable private fun CELyricFollow(lines: List<LyricLine>, state: ContinuousEditorState,
                                      readout: () -> ContinuousEditorReadout, refreshKey: Long) {
    val clock by CELive(state.songPlaying, refreshKey, readout)
    val tick = ContinuousLyricsController.tick(clock.songFrame, state.milliBpm) ?: 0
    val position = (LyricSynchronization.at(lines, tick) as? LyricResult.Success)?.value
    Text(stringResource(Res.string.ce_lyrics_now), fontWeight = FontWeight.Bold)
    position?.current?.forEach { active ->
        Text(buildAnnotatedString {
            if (active.line.words.isEmpty() || active.line.words.joinToString("") { it.text } != active.line.text) append(active.line.text)
            else active.line.words.forEachIndexed { index, word ->
                withStyle(SpanStyle(fontWeight = if (index == active.wordIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == active.wordIndex) CEColor.Orange else CEColor.Ink)) { append(word.text) }
            }
        }, Modifier.testTag("ce-lyric-current-${active.line.id}"))
    }
    Text(stringResource(Res.string.ce_lyrics_next))
    position?.next?.forEach { Text(it.text, Modifier.testTag("ce-lyric-next-${it.id}")) }
}

@Composable private fun CELyricEdit(line: LyricLine, timing: LyricTiming, enabled: Boolean,
                                   readout: () -> ContinuousEditorReadout, send: (LyricAction) -> Unit) {
    var text by remember(line.id, line.text) { mutableStateOf(line.text) }
    OutlinedTextField(text, { if (it.length <= 4096) text = it }, Modifier.fillMaxWidth().testTag("ce-lyric-text"),
        enabled = enabled, label = { Text(stringResource(Res.string.ce_lyrics_text)) }, singleLine = true)
    CEButton(stringResource(Res.string.ce_lyrics_apply_text), { send(LyricAction.Text(line.id, text)) }, Modifier.fillMaxWidth(),
        enabled = enabled && text != line.text, tag = "ce-lyric-apply-text")
    LyricTimeFields(timing.tickToMilliseconds(line.startTick), timing.tickToMilliseconds(line.endTick), enabled,
        "ce-lyric-time") { from, to -> send(LyricAction.Timing(line.id, from, to)) }
    CEButton(stringResource(Res.string.ce_lyrics_tap), { send(LyricAction.Tap(line.id, readout().songFrame)) }, Modifier.fillMaxWidth(),
        enabled = enabled, tag = "ce-lyric-tap")
    if (line.words.isNotEmpty()) {
        Text(stringResource(Res.string.ce_lyrics_words))
        var wordIndex by remember { mutableStateOf(0) }
        val index = wordIndex.coerceAtMost(line.words.lastIndex)
        LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
            items(line.words.size) { item ->
                Text(line.words[item].text, Modifier.fillMaxWidth().semantics { selected = item == index }
                    .clickable { wordIndex = item }.padding(vertical = 12.dp).testTag("ce-lyric-select-word-$item"),
                    fontWeight = if (item == index) FontWeight.Bold else FontWeight.Normal)
            }
        }
        val word = line.words[index]
        key(index) {
            LyricTimeFields(timing.tickToMilliseconds(word.startTick), timing.tickToMilliseconds(word.endTick), enabled,
                "ce-lyric-word-$index") { from, to -> send(LyricAction.WordTiming(line.id, index, from, to)) }
        }
    }
    var deleting by remember(line.id) { mutableStateOf(false) }
    CEButton(stringResource(if (deleting) Res.string.ce_lyrics_delete_confirm else Res.string.ce_lyrics_delete),
        { if (deleting) send(LyricAction.Delete(line.id)) else deleting = true }, Modifier.fillMaxWidth(), enabled = enabled, tag = "ce-lyric-delete")
}

@Composable private fun LyricTimeFields(start: Long, end: Long, enabled: Boolean, tag: String, apply: (Long, Long) -> Unit) {
    var from by remember(start) { mutableStateOf(start.toString()) }
    var to by remember(end) { mutableStateOf(end.toString()) }
    OutlinedTextField(from, { if (it.length <= 12) from = it }, Modifier.fillMaxWidth().testTag("$tag-start"), enabled = enabled,
        singleLine = true, label = { Text(stringResource(Res.string.ce_lyrics_start_ms)) })
    OutlinedTextField(to, { if (it.length <= 12) to = it }, Modifier.fillMaxWidth().testTag("$tag-end"), enabled = enabled,
        singleLine = true, label = { Text(stringResource(Res.string.ce_lyrics_end_ms)) })
    CEButton(stringResource(Res.string.ce_lyrics_apply_time), { apply(from.toLongOrNull() ?: -1, to.toLongOrNull() ?: -1) },
        Modifier.fillMaxWidth(), enabled = enabled, tag = "$tag-apply")
}

@Composable private fun lyricNotice(notice: LyricEditorNotice): String = stringResource(when (notice) {
    LyricEditorNotice.IMPORT_READY -> Res.string.ce_lyrics_ready
    LyricEditorNotice.APPLIED -> Res.string.ce_lyrics_applied
    LyricEditorNotice.EXPORTED -> Res.string.ce_lyrics_exported
    LyricEditorNotice.WORD_TIMING_OMITTED -> Res.string.ce_lyrics_omitted
    LyricEditorNotice.CANCELLED -> Res.string.ce_lyrics_cancelled
    LyricEditorNotice.READ_FAILED -> Res.string.ce_lyrics_read_failed
    LyricEditorNotice.WRITE_FAILED -> Res.string.ce_lyrics_write_failed
    LyricEditorNotice.STALE -> Res.string.ce_lyrics_stale
    LyricEditorNotice.EDIT_FAILED -> Res.string.ce_lyrics_edit_failed
})

@Composable private fun lyricIssue(issue: LyricIssue): String {
    val reason = stringResource(when (issue.problem) {
        LyricProblem.TOO_LARGE, LyricProblem.TOO_MANY_LINES, LyricProblem.TOO_MANY_WORDS, LyricProblem.TOO_MANY_METADATA -> Res.string.ce_lyrics_limit
        LyricProblem.MALFORMED_TAG, LyricProblem.MALFORMED_TIMESTAMP, LyricProblem.UNTYPED_TEXT,
        LyricProblem.DUPLICATE_OFFSET -> Res.string.ce_lyrics_format
        LyricProblem.NEGATIVE_TIME, LyricProblem.TIME_OUT_OF_RANGE, LyricProblem.INVALID_ORDER, LyricProblem.TIMING_COLLAPSE -> Res.string.ce_lyrics_time_error
        LyricProblem.UNREPRESENTABLE_END -> Res.string.ce_lyrics_need_extended
        LyricProblem.INVALID_TEXT, LyricProblem.WORD_TEXT_MISMATCH -> Res.string.ce_lyrics_text_error
        LyricProblem.DUPLICATE_ID, LyricProblem.UNKNOWN_LINE, LyricProblem.UNKNOWN_WORD -> Res.string.ce_lyrics_stale
    })
    return issue.inputLine?.let { stringResource(Res.string.ce_lyrics_line_error, it, reason) } ?: reason
}
