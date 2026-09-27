package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.Project
import com.choplab.engine.PlayMode
import com.choplab.jvm.DriverPhase
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Actual shared presenter, native-host text adapter, EngineCore, archives and autosave; synthetic sound only. */
class NextLyricsTest {
    @Test fun lyricsRoundTripThroughProductionWithoutInterruptingThePlayingPad() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("next-lyrics-")
        val profile = directory.resolve("profile")
        val input = directory.resolve("lyrics.lrc")
        val output = directory.resolve("export.lrc")
        Files.writeString(input, "[00:00.000]<00:00.000>音<00:00.500>楽<00:01.000>\n[00:02.000]歌おう<00:03.000>")
        val audio = directory.resolve("source.wav")
        NextSelfTest.writeDemo(audio)
        val sink = CountingTestSink()
        val backend = NextBackend.create(profile, sinkFactory = { sink }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val real = DesktopEditorPorts(backend) { null }
        val ports = object : ContinuousEditorPorts by real {
            override val lyricFiles = DesktopLyricFiles { save -> if (save) output else input }
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        val saved: Project
        try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(backend.importAudio(audio).accepted)
            idle(backend)
            assertTrue(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            val pad = backend.studio.document.value.project.pads[0]
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetPad(pad.copy(mode = PlayMode.LOOP)))).accepted)
            assertTrue(backend.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
            assertTrue(backend.studio.dispatch(Action.Trigger(0)).accepted)
            waitUntil { backend.engine.snapshot().activeVoices > 0 && sink.leftEnergy > 0 }
            val before = backend.studio.document.value
            val audioRevision = backend.engine.snapshot().programRevision
            suspend fun lyric(action: LyricAction) = presenter.dispatch(ContinuousEditorAction.Lyrics(action))
            assertTrue(lyric(LyricAction.Open))
            assertTrue(lyric(LyricAction.Import))
            assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(lyric(LyricAction.ApplyImport))
            val imported = backend.studio.document.value.project
            assertEquals(2, imported.lyrics.size)
            assertEquals(audioRevision, backend.engine.snapshot().programRevision)
            assertTrue(backend.engine.snapshot().activeVoices > 0)
            assertFalse(backend.studio.document.value.audiblePending)
            assertNull(backend.studio.work.value.jobId)
            assertNull(backend.studio.work.value.preparationId)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(imported, backend.studio.document.value.project)
            assertEquals(audioRevision, backend.engine.snapshot().programRevision)
            assertTrue(backend.engine.snapshot().activeVoices > 0, "Lyric Undo/Redo cannot cut the held PAD")
            assertTrue(lyric(LyricAction.Tap("lrc-1", 24_000)))
            assertTrue(lyric(LyricAction.Export()))
            assertTrue(Files.readString(output).contains("[00:00.500]"))
            assertTrue(Files.readString(output).contains("<00:01.000>"))
            assertEquals(audioRevision, backend.engine.snapshot().programRevision)
            // The next real audio edit catches up to the current document revision normally.
            assertTrue(presenter.dispatch(ContinuousEditorAction.SetPadGain(0, .5f)))
            assertEquals(backend.studio.document.value.revision, backend.engine.snapshot().programRevision)
            saved = backend.studio.document.value.project
            val archive = directory.resolve("lyrics.choplab")
            assertTrue(backend.saveProject(archive).accepted)
            idle(backend)
            assertTrue(backend.openProject(archive).accepted)
            idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
            backend.flushAutosave()
        } finally { presenter.close(); backend.shutdown(); scope.cancel() }
        val restarted = NextBackend.create(profile, sinkFactory = { error("No output needed") }, microphone = { null })
        try { assertEquals(saved, restarted.studio.document.value.project) }
        finally { restarted.shutdown() }
        assertEquals(0L, Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".pending") }.count() })
    }
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    private suspend fun idle(backend: NextBackend) = waitUntil { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
}
