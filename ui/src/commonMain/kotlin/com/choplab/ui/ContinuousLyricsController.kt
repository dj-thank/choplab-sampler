package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.lyrics.*
import com.choplab.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Host file dialogs and bounded UTF-8 text only; paths and document handles stay with the host. */
interface LyricFiles {
    suspend fun importLrc(): String?
    /** False means the user cancelled; a failed write throws. */
    suspend fun exportLrc(text: String): Boolean
}

enum class LyricEditorNotice { IMPORT_READY, APPLIED, EXPORTED, WORD_TIMING_OMITTED, CANCELLED, READ_FAILED, WRITE_FAILED, STALE, EDIT_FAILED }
data class LyricImportPreview(val lines: List<LyricLine>, val milliBpm: Int)
data class ContinuousLyricsState(
    val open: Boolean = false, val selectedId: String? = null, val lines: List<LyricLine> = emptyList(),
    val preview: LyricImportPreview? = null, val notice: LyricEditorNotice? = null, val issue: LyricIssue? = null,
)

sealed interface LyricAction {
    data object Open : LyricAction
    data object Close : LyricAction
    data class Select(val id: String) : LyricAction
    data class Add(val songFrame: Long) : LyricAction
    data class Text(val id: String, val text: String) : LyricAction
    data class Timing(val id: String, val startMilliseconds: Long, val endMilliseconds: Long) : LyricAction
    data class WordTiming(val id: String, val word: Int, val startMilliseconds: Long, val endMilliseconds: Long) : LyricAction
    data class Tap(val id: String, val songFrame: Long) : LyricAction
    data class Delete(val id: String, val expectedLine: LyricLine? = null, val expectedRevision: Long? = null) : LyricAction
    data object Import : LyricAction
    data object ApplyImport : LyricAction
    data object CancelImport : LyricAction
    data class Export(val format: LrcFormat = LrcFormat.EXTENDED) : LyricAction
}

/** Serialized by the editor. Imports are previews fenced to the exact document revision, never implicit edits. */
internal class ContinuousLyricsController(private val studio: Studio, private val files: LyricFiles?,
                                          private val apply: suspend (Intent, Long) -> Boolean) {
    private data class Pending(val revision: Long, val project: Project, val imported: LrcImport)
    private var pending: Pending? = null
    private var serial = 0L
    private val mutable = MutableStateFlow(ContinuousLyricsState())
    val view = mutable.asStateFlow()

    suspend fun dispatch(action: LyricAction): Boolean {
        val snapshot = studio.document.value
        val project = snapshot.project
        val timing = LyricTiming(project.tempo.milliBpm)
        mutable.update { it.copy(notice = null, issue = null) }
        return when (action) {
            LyricAction.Open -> { mutable.update { it.copy(open = true, selectedId = it.selectedId ?: project.lyrics.firstOrNull()?.id) }; true }
            LyricAction.Close -> { pending = null; mutable.update { it.copy(open = false, preview = null) }; true }
            is LyricAction.Select -> if (project.lyrics.none { it.id == action.id }) issue(LyricIssue(LyricProblem.UNKNOWN_LINE))
                else { mutable.update { it.copy(selectedId = action.id) }; true }
            is LyricAction.Add -> {
                val from = tick(action.songFrame, project.tempo.milliBpm) ?: return issue(LyricIssue(LyricProblem.TIME_OUT_OF_RANGE))
                var id: String
                do { id = "lyric-${++serial}" } while (project.lyrics.any { it.id == id })
                val end = (from + 4 * ProjectLimits.PPQ).coerceAtMost(ProjectLimits.MAX_TIMELINE_TICKS)
                if (end <= from) issue(LyricIssue(LyricProblem.TIME_OUT_OF_RANGE))
                else changed(LyricEdits.insertLine(project.lyrics, LyricLine(id, "", from, end)), id, snapshot.revision)
            }
            is LyricAction.Text -> changed(LyricEdits.replaceText(project.lyrics, action.id, action.text), action.id, snapshot.revision)
            is LyricAction.Delete -> if ((action.expectedRevision != null && action.expectedRevision != snapshot.revision) ||
                (action.expectedLine != null && action.expectedLine != project.lyrics.firstOrNull { it.id == action.id })) notice(LyricEditorNotice.STALE, false)
                else changed(LyricEdits.removeLine(project.lyrics, action.id), null, snapshot.revision)
            is LyricAction.Tap -> tick(action.songFrame, project.tempo.milliBpm)?.let { changed(LyricEdits.tapLineStart(project.lyrics, action.id, it), action.id, snapshot.revision) }
                ?: issue(LyricIssue(LyricProblem.TIME_OUT_OF_RANGE))
            is LyricAction.Timing -> times(timing, action.startMilliseconds, action.endMilliseconds) { from, to ->
                changed(LyricEdits.retimeLine(project.lyrics, action.id, from, to), action.id, snapshot.revision)
            }
            is LyricAction.WordTiming -> times(timing, action.startMilliseconds, action.endMilliseconds) { from, to ->
                changed(LyricEdits.retimeWord(project.lyrics, action.id, action.word, from, to), action.id, snapshot.revision)
            }
            LyricAction.Import -> {
                val port = files ?: return notice(LyricEditorNotice.READ_FAILED, false)
                val text = try { port.importLrc() } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { return notice(LyricEditorNotice.READ_FAILED, false) }
                    ?: return notice(LyricEditorNotice.CANCELLED, true)
                if (!current(snapshot)) return notice(LyricEditorNotice.STALE, false)
                // With no explicit end the final row lasts four beats, including imports before a song exists.
                when (val result = LrcCodec.parse(text, timing, ProjectLimits.MAX_TIMELINE_TICKS, finalLineDurationTicks = 4L * ProjectLimits.PPQ)) {
                    is LyricResult.Failure -> issue(result.issue)
                    is LyricResult.Success -> {
                        pending = Pending(snapshot.revision, project, result.value)
                        mutable.update { it.copy(preview = LyricImportPreview(result.value.lines, timing.milliBpm), notice = LyricEditorNotice.IMPORT_READY) }
                        true
                    }
                }
            }
            LyricAction.ApplyImport -> {
                val prepared = pending ?: return notice(LyricEditorNotice.STALE, false)
                if (snapshot.revision != prepared.revision || project != prepared.project) {
                    pending = null; mutable.update { it.copy(preview = null) }
                    return notice(LyricEditorNotice.STALE, false)
                }
                changed(LyricResult.Success(prepared.imported.lines), prepared.imported.lines.firstOrNull()?.id, prepared.revision)
            }
            LyricAction.CancelImport -> { pending = null; mutable.update { it.copy(preview = null) }; notice(LyricEditorNotice.CANCELLED, true) }
            is LyricAction.Export -> {
                val port = files ?: return notice(LyricEditorNotice.WRITE_FAILED, false)
                val rendered = when (val result = LrcCodec.export(project.lyrics, timing, action.format)) {
                    is LyricResult.Failure -> return issue(result.issue)
                    is LyricResult.Success -> result.value
                }
                val saved = try { port.exportLrc(rendered.text) } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { return notice(LyricEditorNotice.WRITE_FAILED, false) }
                notice(if (!saved) LyricEditorNotice.CANCELLED else if (rendered.omittedWordTiming) LyricEditorNotice.WORD_TIMING_OMITTED else LyricEditorNotice.EXPORTED, true)
            }
        }
    }
    private fun current(snapshot: DocumentState) = studio.document.value.let { it.revision == snapshot.revision && it.project == snapshot.project }
    private suspend fun changed(result: LyricResult<FrozenList<LyricLine>>, selected: String?, revision: Long): Boolean = when (result) {
        is LyricResult.Failure -> issue(result.issue)
        is LyricResult.Success -> if (!apply(Intent.SetLyrics(result.value), revision))
            notice(if (studio.document.value.revision != revision) LyricEditorNotice.STALE else LyricEditorNotice.EDIT_FAILED, false)
            else { pending = null; mutable.update { it.copy(selectedId = selected, preview = null, notice = LyricEditorNotice.APPLIED) }; true }
    }
    private suspend fun times(timing: LyricTiming, from: Long, to: Long, edit: suspend (Long, Long) -> Boolean): Boolean {
        if (from !in 0..timing.maximumMilliseconds || to !in 0..timing.maximumMilliseconds) return issue(LyricIssue(LyricProblem.TIME_OUT_OF_RANGE))
        return edit(timing.millisecondsToTick(from), timing.millisecondsToTick(to))
    }
    private fun issue(issue: LyricIssue): Boolean { mutable.update { it.copy(issue = issue) }; return false }
    private fun notice(notice: LyricEditorNotice, accepted: Boolean): Boolean { mutable.update { it.copy(notice = notice) }; return accepted }
    companion object {
        /** Straight musical ticks from the 48 kHz song clock; lyric timestamps never inherit swing. */
        fun tick(frame: Long, milliBpm: Int): Long? = if (frame !in 0..ProjectLimits.MAX_TIMELINE_FRAMES) null
            else (frame * milliBpm * ProjectLimits.PPQ + 1_440_000_000L) / 2_880_000_000L
    }
}
