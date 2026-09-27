package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.lyrics.*
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import kotlinx.coroutines.*
import kotlin.test.*

class ContinuousLyricsControllerTest {
    @Test fun importPreviewIsExplicitAndEachTextOrWordTimingChangeIsOneUndo() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.files.text = "[00:01.000]<00:01.000>音<00:01.500>楽<00:02.000>\n[00:03.000]歌おう"
            val before = h.studio.document.value
            assertTrue(h.editor.dispatch(LyricAction.Open))
            assertTrue(h.editor.dispatch(LyricAction.Import))
            assertEquals(before, h.studio.document.value)
            assertEquals(2, h.editor.view.value.preview!!.lines.size)
            assertEquals(120_000, h.editor.view.value.preview!!.milliBpm)
            assertTrue(h.editor.dispatch(LyricAction.ApplyImport))
            val imported = h.studio.document.value.project
            assertEquals(1L, h.studio.document.value.revision)
            assertEquals(2, imported.lyrics.first().words.size)
            assertEquals(3_840L, imported.lyrics.last().endTick - imported.lyrics.last().startTick)
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(before.project, h.studio.document.value.project)
            assertTrue(h.studio.dispatch(Action.Redo).accepted)
            assertEquals(imported, h.studio.document.value.project)
            assertTrue(h.editor.dispatch(LyricAction.WordTiming("lrc-1", 0, 1050, 1450)))
            assertEquals(2016L, h.studio.document.value.project.lyrics.first().words.first().startTick)
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(imported, h.studio.document.value.project)
            assertFalse(h.editor.dispatch(LyricAction.WordTiming("lrc-1", 0, 900, 1600)))
            assertEquals(LyricProblem.INVALID_ORDER, h.editor.view.value.issue?.problem)
            assertEquals(imported, h.studio.document.value.project)
            assertTrue(h.editor.dispatch(LyricAction.Text("lrc-1", "新しい歌詞")))
            assertTrue(h.studio.document.value.project.lyrics.first().words.isEmpty())
            assertEquals(imported.lyrics.last(), h.studio.document.value.project.lyrics.last())
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertTrue(h.editor.dispatch(LyricAction.Export()))
            assertTrue(h.files.written!!.contains("<00:01.500>"))
            assertTrue(h.editor.dispatch(LyricAction.Export(LrcFormat.STANDARD)))
            assertEquals(LyricEditorNotice.WORD_TIMING_OMITTED, h.editor.view.value.notice)
            assertFalse(h.files.written!!.contains('<'))
            assertTrue(h.engine.commands.isEmpty(), "Lyrics never recompile or interrupt audio")
        } finally { h.close() }
    }

    @Test fun cancellationInvalidTextAndStaleRepliesCannotReplaceTheProject() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.files.text = null
            assertTrue(h.editor.dispatch(LyricAction.Import))
            assertEquals(LyricEditorNotice.CANCELLED, h.editor.view.value.notice)
            h.files.text = "untimed lyrics"
            assertFalse(h.editor.dispatch(LyricAction.Import))
            assertEquals(1, h.editor.view.value.issue?.inputLine)
            assertFalse(h.studio.document.value.canUndo)
            h.files.text = "[00:01.000]saved"
            assertTrue(h.editor.dispatch(LyricAction.Import))
            assertTrue(h.studio.dispatch(Action.Edit(Intent.SetTempo(com.choplab.engine.Tempo(100_000)))).accepted)
            assertFalse(h.editor.dispatch(LyricAction.ApplyImport))
            assertEquals(LyricEditorNotice.STALE, h.editor.view.value.notice)
            assertTrue(h.studio.document.value.project.lyrics.isEmpty())
            h.files.beforeRead = { h.studio.dispatch(Action.New(Project(id = "replaced"))) }
            assertFalse(h.editor.dispatch(LyricAction.Import))
            assertEquals(LyricEditorNotice.STALE, h.editor.view.value.notice)
            assertEquals("replaced", h.studio.document.value.project.id)
            assertTrue(h.studio.document.value.project.lyrics.isEmpty())
            h.files.beforeRead = { error("unreadable") }
            assertFalse(h.editor.dispatch(LyricAction.Import))
            assertEquals(LyricEditorNotice.READ_FAILED, h.editor.view.value.notice)
        } finally { h.close() }
    }

    @Test fun tappingUsesTheCapturedSongFrameAndLineTimingRetainsItsWords() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.files.text = "[00:01.000]<00:01.000>One<00:01.500> word<00:02.000>"
            assertTrue(h.editor.dispatch(LyricAction.Import))
            assertTrue(h.editor.dispatch(LyricAction.ApplyImport))
            assertTrue(h.editor.dispatch(LyricAction.Tap("lrc-1", 96_000)))
            val line = h.studio.document.value.project.lyrics.single()
            assertEquals(3840L, line.startTick)
            assertEquals(listOf(3840L, 4800L), line.words.map { it.startTick })
            assertTrue(h.editor.dispatch(LyricAction.Timing("lrc-1", 3000, 4000)))
            assertEquals(5760L, h.studio.document.value.project.lyrics.single().startTick)
            assertFalse(h.editor.dispatch(LyricAction.Timing("lrc-1", 3000, 3200)))
            assertEquals(LyricProblem.INVALID_ORDER, h.editor.view.value.issue?.problem)
            assertTrue(h.editor.dispatch(LyricAction.Delete("lrc-1")))
            assertTrue(h.studio.document.value.project.lyrics.isEmpty())
            assertTrue(h.studio.dispatch(Action.Undo).accepted)
            assertEquals(5760L, h.studio.document.value.project.lyrics.single().startTick)
        } finally { h.close() }
    }

    private class Files : LyricFiles {
        var text: String? = null
        var written: String? = null
        var beforeRead: suspend () -> Unit = {}
        override suspend fun importLrc(): String? { beforeRead(); return text }
        override suspend fun exportLrc(text: String): Boolean { written = text; return true }
    }
    private class Engine : EnginePort {
        val commands = mutableListOf<EngineCommand>()
        override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
        override suspend fun apply(command: EngineCommand): Boolean { commands += command; return true }
        override fun snapshot() = TransportState(outputAttached = true)
    }
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val files = Files()
        val engine = Engine()
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location): Asset = error("No audio import") },
            object : ProjectPort {
                override suspend fun save(project: Project, revision: Long, location: Location) = Unit
                override suspend fun open(location: Location) = Project()
            }, object : ExportPort { override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("No audio export") }, engine))
        val editor = ContinuousLyricsController(studio, files) { intent, revision -> studio.dispatch(Action.Edit(intent, revision)).accepted }
        suspend fun close() { studio.dispatch(Action.Close); scope.cancel() }
    }
}
