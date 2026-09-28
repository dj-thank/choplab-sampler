package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.jvm.ai.*
import com.choplab.ui.*
import com.choplab.ui.ai.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.*

/** Production HTTP construction/decoder -> common preview -> real Studio actor -> archive/autosave, with no external I/O. */
class GeminiLyricFlowTest {
    @Test fun fakeGoogleReplyRequiresPreviewApplyAndOneUndoAndSurvivesSaveWithoutTheKeyOrPrompt() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("gemini-lyrics-flow-")
        val profile = directory.resolve("profile")
        val backend = NextBackend.create(profile, sinkFactory = { error("No device in this test") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        val provider = GeminiLyricProvider(UrlConnectionGeminiTransport { url -> calls++; Connection(url) })
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by ports {
            override val lyricProposal = object : LyricProposalPort {
                override val availability = LyricProviderAvailability.AVAILABLE
                override fun createProvider() = provider
            }
        })
        val saved: Project
        try {
            val original = Project(lyrics = frozenListOf(LyricLine("old", "元の歌", 0, 1920,
                frozenListOf(LyricWord("元", 0, 960), LyricWord("の歌", 960, 1920)))))
            assertTrue(backend.studio.dispatch(Action.New(original)).accepted)
            idle(backend)
            assertTrue(presenter.dispatch(ContinuousEditorAction.Lyrics(LyricAction.Open)))
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenLyricProposal))
            val controller = requireNotNull(presenter.lyricProposal.value)
            val before = backend.studio.document.value
            assertFalse(before.canUndo)
            val audioRevision = backend.engine.snapshot().programRevision
            assertTrue(controller.generate(request(), SessionApiKey("fake-private-session-key"), 960, 2, true))
            waitUntil { controller.state.value.phase == LyricProposalPhase.PREVIEW }
            assertEquals(1, calls)
            assertEquals(before, backend.studio.document.value, "A completed network request is still only a preview")
            assertEquals(listOf("新しい歌", "風の音"), controller.state.value.placed.map { it.text })
            assertEquals(7, controller.state.value.proposal!!.sections.single().lines.first().mora)
            assertEquals(LyricUsage(40, 55, 95), controller.state.value.usage)
            assertTrue(controller.applyPreview())
            saved = backend.studio.document.value.project
            assertEquals(original.copy(lyrics = saved.lyrics, lyricStructure = saved.lyricStructure), saved)
            assertEquals("preview-title", saved.lyricStructure!!.title)
            assertEquals("あたらしいうた", saved.lyricStructure!!.sections.single().lines.first().reading)
            assertEquals(7, saved.lyricStructure!!.sections.single().lines.first().mora)
            assertEquals(before.revision + 1, backend.studio.document.value.revision)
            assertEquals(audioRevision, backend.engine.snapshot().programRevision, "Lyric edits do not rebuild audio")
            assertEquals(listOf(960L, 2880L), saved.lyrics.map { it.startTick })
            assertEquals(listOf(2880L, 4800L), saved.lyrics.map { it.endTick })
            assertTrue(saved.lyrics.all { it.words.isEmpty() }, "Manual placement does not claim provider word timing")
            assertFalse(controller.applyPreview())
            assertTrue(presenter.dispatch(ContinuousEditorAction.Undo))
            assertEquals(original, backend.studio.document.value.project)
            assertFalse(backend.studio.document.value.canUndo, "Exactly one undo transaction")
            assertTrue(presenter.dispatch(ContinuousEditorAction.Redo))
            assertEquals(saved, backend.studio.document.value.project)
            val archive = directory.resolve("song.choplab")
            assertTrue(backend.saveProject(archive).accepted); idle(backend)
            assertTrue(Files.isRegularFile(archive))
            ZipFile(archive.toFile()).use { zip ->
                val json = zip.getInputStream(zip.getEntry("project.json")).bufferedReader().use { it.readText() }
                for (privateValue in listOf("fake-private-session-key", "private-theme-marker", "gemini-test")) {
                    assertFalse(json.contains(privateValue), "Credentials, prompts and provider configuration must not enter the archive")
                }
                assertTrue(json.contains("preview-title"), "Explicitly applied composition metadata survives saving")
            }
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertTrue(backend.openProject(archive).accepted); idle(backend)
            assertEquals(saved, backend.studio.document.value.project)
            backend.flushAutosave()
        } finally { presenter.close(); ports.close(); scope.cancel(); backend.shutdown() }
        val restored = NextBackend.create(profile, sinkFactory = { error("No device in this test") }, microphone = { null })
        try { assertEquals(saved, restored.studio.document.value.project) } finally { restored.shutdown() }
    }

    @Test fun defaultDesktopEntryExplainsUnverifiedProviderWithoutSendingOrEditing() = runBlocking<Unit> {
        val backend = NextBackend.create(Files.createTempDirectory("gemini-lyrics-unverified-"), sinkFactory = { error("No device") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        try {
            idle(backend)
            val before = backend.studio.document.value
            assertEquals(LyricProviderAvailability.UNVERIFIED, ports.lyricProposal.availability)
            assertTrue(presenter.dispatch(ContinuousEditorAction.OpenLyricProposal))
            val controller = requireNotNull(presenter.lyricProposal.value)
            val key = SessionApiKey("fake-key")
            assertFalse(controller.generate(request(), key, 0, 4, true))
            assertEquals(LyricAiProblem.PROVIDER_UNVERIFIED, controller.state.value.failure?.problem)
            assertFailsWith<IllegalStateException> { key.useValue { it } }
            assertEquals(before, backend.studio.document.value)
            assertTrue(presenter.dispatch(ContinuousEditorAction.CloseLyricProposal))
            assertEquals(LyricProposalPhase.CLOSED, controller.state.value.phase)
            assertNull(presenter.lyricProposal.value)
        } finally { presenter.close(); ports.close(); scope.cancel(); backend.shutdown() }
    }

    @Test fun realActorRefusesARevisionChangedAfterPreviewBeforeAtomicApply() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("gemini-lyrics-stale-")
        val backend = NextBackend.create(directory, sinkFactory = { error("No device") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = GeminiLyricProvider(UrlConnectionGeminiTransport { Connection(it) })
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var notice: Notice? = null
        val controller = LyricProposalController(backend.studio.document, provider, LyricProposalApply { placement, revision ->
            reached.complete(Unit); resume.await()
            backend.studio.dispatch(Action.Edit(Intent.SetStructuredLyrics(placement.lines, placement.structure), revision)).also { notice = it.notice }.accepted
        }, scope, LyricProviderAvailability.AVAILABLE)
        try {
            idle(backend)
            assertTrue(controller.generate(request(), SessionApiKey("fake-key"), 0, 4, true))
            waitUntil { controller.state.value.phase == LyricProposalPhase.PREVIEW }
            val applying = async { controller.applyPreview() }
            reached.await()
            val local = frozenListOf(LyricLine("local", "自分の編集", 0, 960))
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.SetLyrics(local))).accepted)
            val beforeReject = backend.studio.document.value
            resume.complete(Unit)
            assertFalse(applying.await())
            assertEquals(Notice.StaleCompletion, notice)
            assertEquals(beforeReject, backend.studio.document.value)
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertTrue(backend.studio.document.value.project.lyrics.isEmpty())
            assertFalse(backend.studio.document.value.canUndo, "Refused AI apply adds no history")
        } finally { resume.complete(Unit); controller.close(); scope.cancel(); backend.shutdown() }
    }

    private fun request() = LyricRequest("gemini-test", "private-theme-marker", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", "")
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) }
    private suspend fun idle(backend: NextBackend) = waitUntil { backend.studio.work.value.jobId == null && backend.studio.work.value.preparationId == null }
    private class Connection(url: URL) : HttpURLConnection(url) {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = 200
        override fun getHeaderField(name: String?): String? = null
        override fun getInputStream() = ByteArrayInputStream(REPLY.toByteArray())
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }
    private companion object {
        // Fake generateContent response bytes, not a real provider receipt.
        const val REPLY = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"title\":\"preview-title\",\"language\":\"ja\",\"sections\":[{\"name\":\"A\",\"kind\":\"verse\",\"bars\":4,\"lines\":[{\"text\":\"新しい歌\",\"reading\":\"あたらしいうた\",\"mora\":99,\"rhymeVowels\":\"wrong\"},{\"text\":\"風の音\",\"reading\":\"かぜのおと\",\"mora\":99,\"rhymeVowels\":\"wrong\"}]}]}"}]}}],"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":55,"totalTokenCount":95},"modelVersion":"gemini-test-revision"}"""
    }
}
