package com.choplab.desktop.next

import com.choplab.core.Action
import com.choplab.core.ai.GoogleLyricSession
import com.choplab.core.inception.DecisionKind
import com.choplab.jvm.inception.FileInceptionStore
import com.choplab.ui.inception.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Real backend/Studio, independent durable notebook and existing project archive/autosave. No provider or audio device. */
class NextInceptionTest {
    @Test fun notebookToLyricsAndTitleRoundTripsWithoutChangingSourceHandPadsOrArrangement() = runBlocking<Unit> {
        val root = Files.createTempDirectory("next-inception-")
        val profile = root.resolve("profile")
        val backend = NextBackend.create(profile, sinkFactory = { error("No audio endpoint in this fixture") }, microphone = { null })
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = FileInceptionStore(profile.resolve("inception-v1"))
        val controller = InceptionController(store, owner, backend.studio, GoogleLyricSession(System::currentTimeMillis))
        try {
            val before = backend.studio.document.value.project
            assertTrue(controller.open()); assertTrue(controller.create("synthetic notebook")); assertTrue(controller.addMaterial("synthetic note"))
            assertTrue(controller.select(controller.state.value.workspace!!.materials.single().id))
            assertTrue(controller.putConcept(null, "synthetic concept", "line one\nline two"))
            val c = controller.state.value.workspace!!.concepts.single()
            assertTrue(controller.decide(c.id, DecisionKind.ADOPT)); assertTrue(controller.preview(c.id, InceptionDestination.LYRICS))
            assertEquals(before, backend.studio.document.value.project)
            assertTrue(controller.apply()); val lyrics = backend.studio.document.value.project
            assertEquals(2, lyrics.lyrics.size); assertEquals(before, lyrics.copy(lyrics = before.lyrics))
            assertTrue(backend.studio.dispatch(Action.Undo).accepted); assertEquals(before, backend.studio.document.value.project)
            assertTrue(controller.preview(c.id, InceptionDestination.PRODUCTION_TITLE)); assertTrue(controller.apply())
            assertEquals(c.title, backend.studio.document.value.project.title)
            assertEquals(before, backend.studio.document.value.project.copy(title = before.title))
            assertTrue(backend.studio.dispatch(Action.Undo).accepted); assertEquals(before, backend.studio.document.value.project)
            assertTrue(controller.preview(c.id, InceptionDestination.LYRICS)); assertTrue(controller.apply())
            val saved = backend.studio.document.value.project
            val archive = root.resolve("synthetic.choplab")
            assertTrue(backend.saveProject(archive).accepted); assertTrue(backend.openProject(archive).accepted)
            assertEquals(saved, backend.studio.document.value.project)
            backend.flushAutosave()
            controller.dismiss(); controller.open(); assertEquals(c, controller.state.value.workspace!!.concepts.single())
            assertTrue(controller.state.value.workspace!!.adopted(c))
        } finally { controller.close(); backend.shutdown(); owner.cancel() }
        val reopened = NextBackend.create(profile, sinkFactory = { error("No audio endpoint in reopen fixture") }, microphone = { null })
        try { assertEquals(listOf("line one", "line two"), reopened.studio.document.value.project.lyrics.map { it.text }) }
        finally { reopened.shutdown() }
    }
}
