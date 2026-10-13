package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.lyrics.LrcFormat
import com.choplab.core.model.*
import com.choplab.ui.pattern.PatternAction
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.jvm.closeAfterAutosave
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class ProjectTransitionTest {
    @Test fun menuAndDropPreserveUnsavedDocumentUndoAndOriginalOnCancelFailureAndStaleConfirmation() = runBlocking<Unit> {
        for (menu in listOf(true, false)) {
            val h = Harness()
            suspend fun request() = h.presenter.dispatch(if (menu) ContinuousEditorAction.OpenProject else ContinuousEditorAction.OpenProjectFile(Location("chosen")))
            try {
                assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("Unsaved song"))).accepted)
                val before = h.studio.document.value
                val original = h.original.copyOf()
                assertTrue(request()); h.until { it.openProjectRevision == before.revision }
                assertEquals(0, h.opens)
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.CancelOpenProject))
                assertEquals(before, h.studio.document.value)
                assertTrue(request()); h.saveFails = true
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.ConfirmOpenProject(true, before.revision)))
                assertEquals(before, h.studio.document.value); assertEquals(0, h.opens)
                assertContentEquals(original, h.original)
                h.saveFails = false; h.saveChoice = null
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.ConfirmOpenProject(true, before.revision)))
                assertEquals(before, h.studio.document.value)
                assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("Changed after confirmation"))).accepted)
                val changed = h.studio.document.value
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.ConfirmOpenProject(false, before.revision)))
                assertEquals(changed, h.studio.document.value); assertEquals(0, h.opens)
                assertTrue(h.studio.dispatch(Action.Undo).accepted)
                assertEquals(before.project, h.studio.document.value.project)
                h.saveChoice = Location("safe")
                assertTrue(request())
                val revision = h.studio.document.value.revision
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.ConfirmOpenProject(true, revision)))
                h.until { it.projectTitle == "Chosen project" }
                assertEquals(before.project, h.saved)
                assertEquals("chosen", h.openedLocation?.handle)
                assertEquals(1, h.opens); assertFalse(h.studio.document.value.canUndo)
                assertContentEquals(original, h.original)
            } finally { h.close() }
        }
    }

    @Test fun chooserRevisionAndOpenCompletionAreBothFencedAndSavedDocumentOpensDirectly() = runBlocking<Unit> {
        val h = Harness()
        try {
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenProject))
            val revision = h.studio.document.value.revision
            h.saveEntered = CompletableDeferred(); h.saveRelease = CompletableDeferred()
            val confirm = async { h.presenter.dispatch(ContinuousEditorAction.ConfirmOpenProject(true, revision)) }
            h.saveEntered!!.await()
            assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("Newer while choosing"))).accepted)
            val newer = h.studio.document.value
            h.saveRelease!!.complete(Unit)
            assertFalse(confirm.await()); assertEquals(newer, h.studio.document.value); assertEquals(0, h.saves)
            h.saveEntered = null; h.saveRelease = null
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.CancelOpenProject))
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.SaveProject))
            h.until { h.studio.document.value.savedRevision == newer.revision }
            h.openRelease = CompletableDeferred()
            assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenProject))
            h.until { h.opens == 1 }
            assertTrue(h.studio.dispatch(Action.Edit(Intent.Rename("Edited while file was reading"))).accepted)
            val reading = h.studio.document.value
            h.openRelease!!.complete(Unit)
            h.until { h.studio.work.value.jobId == null && h.studio.work.value.preparationId == null }
            assertEquals(reading, h.studio.document.value)
        } finally { h.openRelease?.complete(Unit); h.close() }
    }

    @Test fun quitCancelsOnlyOwnedPickerBeforeOneFinalSaveAndCanResumeAfterFailure() = runBlocking<Unit> {
        val h = Harness()
        var pendingPicker: Job? = null
        try {
            for (kind in listOf("library", "save", "open", "lyrics-import", "lyrics-export", "lyrics-draft-export")) {
                h.blockPicker = if (kind == "lyrics-draft-export") "lyrics-export" else kind
                h.pickerEntered = CompletableDeferred(); h.pickerCancelled = false
                val action = when (kind) {
                    "library" -> ContinuousEditorAction.ImportLibrary
                    "save" -> ContinuousEditorAction.SaveProject
                    "lyrics-import" -> ContinuousEditorAction.Lyrics(LyricAction.Import)
                    "lyrics-export" -> ContinuousEditorAction.Lyrics(LyricAction.Export())
                    "lyrics-draft-export" -> ContinuousEditorAction.Lyrics(LyricAction.ApplyDraftAndExport(
                        listOf(LyricAction.Text("line", "Applied draft")), h.studio.document.value.revision, LrcFormat.EXTENDED))
                    else -> ContinuousEditorAction.OpenProject
                }
                val choosing = async { h.presenter.dispatch(action) }.also { pendingPicker = it }
                h.pickerEntered.await()
                if (kind == "lyrics-draft-export") {
                    assertEquals("Applied draft", h.studio.document.value.project.lyrics.single().text)
                    assertTrue(h.studio.document.value.canUndo)
                }
                val before = h.studio.document.value
                withTimeout(2000) { h.presenter.prepareToClose() }
                assertFalse(withTimeout(2000) { choosing.await() }); assertTrue(h.pickerCancelled)
                assertTrue(withTimeout(2000) { h.presenter.finishRecording() })
                var saves = 0; var closes = 0
                assertFalse(closeAfterAutosave({ saves++; error("Synthetic final save failure") }, { false }) { closes++ })
                assertEquals(1, saves); assertEquals(0, closes)
                h.presenter.cancelClose()
                assertEquals(before, h.studio.document.value)
                h.blockPicker = null
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.SetTempo(121)))
            }
            // Closing just the picker is not an application close.
            h.saveChoice = null
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SaveProject))
            assertFalse(h.presenter.state.value.closing)
            var saves = 0; var closes = 0
            h.presenter.prepareToClose()
            assertTrue(closeAfterAutosave({ check(h.presenter.finishRecording()); saves++ }, { error("Successful save asks nothing") }) { closes++ })
            assertEquals(1, saves); assertEquals(1, closes)
        } finally { pendingPicker?.cancelAndJoin(); h.close() }
    }

    @Test fun projectSwitchPreservesUnsavedPatternAndQueuedArrangementEvenWhenTheDocumentIsSaved() = runBlocking<Unit> {
        for (queued in listOf(false, true)) {
            val h = Harness()
            try {
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.SaveProject))
                h.until { h.studio.document.value.savedRevision == h.studio.document.value.revision }
                h.until { it.permits(ContinuousCapability.STEP_PATTERNS) }
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenStepPatterns))
                val controller = assertNotNull(h.presenter.stepPatterns.value)
                h.until { controller.state.value.editable }
                assertTrue(controller.dispatch(if (queued) PatternAction.Queue else PatternAction.Name("Keep this draft")))
                val draft = controller.state.value
                val before = h.studio.document.value
                for (action in listOf(ContinuousEditorAction.OpenProject,
                    ContinuousEditorAction.OpenProjectFile(Location("chosen")), ContinuousEditorAction.NewProject)) {
                    assertFalse(h.presenter.dispatch(action))
                    assertSame(controller, h.presenter.stepPatterns.value)
                    assertTrue(controller.state.value.closeConfirmation)
                    assertEquals(draft.name, controller.state.value.name)
                    assertEquals(draft.sequence, controller.state.value.sequence)
                    assertEquals(before, h.studio.document.value)
                    assertEquals(0, h.opens)
                    controller.keepEditing()
                    assertTrue(controller.state.value.editable)
                }
                assertTrue(controller.requestClose(discard = true))
                assertTrue(h.presenter.dispatch(ContinuousEditorAction.OpenProjectFile(Location("chosen"))))
                h.until { it.projectTitle == "Chosen project" }
                assertEquals(1, h.opens)
            } finally { h.close() }
        }
    }

    @Test fun stopAllDoesNotWaitForTheAppliedLyricDraftExportPicker() = runBlocking<Unit> {
        val h = Harness(monitorAvailable = true)
        var pendingPicker: Job? = null
        try {
            h.blockPicker = "lyrics-export"
            val choosing = async { h.presenter.dispatch(ContinuousEditorAction.Lyrics(
                LyricAction.ApplyDraftAndExport(listOf(LyricAction.Text("line", "Keep after cancel")),
                    h.studio.document.value.revision, LrcFormat.EXTENDED))) }.also { pendingPicker = it }
            withTimeout(2000) { h.pickerEntered.await() }
            val before = h.studio.document.value
            val stopsBefore = h.originalStops
            h.presenter.onAction(ContinuousEditorAction.StopAll)
            withTimeout(2000) { while (h.originalStops == stopsBefore) delay(5) }
            assertFalse(choosing.isCompleted, "All-stop must work while the file picker stays open")
            assertFalse(h.pickerCancelled)
            withTimeout(2000) { h.presenter.prepareToClose() }
            assertFalse(withTimeout(2000) { choosing.await() })
            assertTrue(h.pickerCancelled)
            assertEquals(before.project, h.studio.document.value.project)
        } finally { pendingPicker?.cancelAndJoin(); h.close() }
    }

    private class Harness(private val monitorAvailable: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val asset = Asset("d".repeat(64), "wav", 48, 48_000, 1, 1, "Original")
        val original = byteArrayOf(0, 0, 0, 1)
        var saves = 0; var opens = 0; var saveFails = false
        @Volatile var originalStops = 0
        var saved: Project? = null; var openedLocation: Location? = null
        var saveChoice: Location? = Location("safe")
        var saveEntered: CompletableDeferred<Unit>? = null; var saveRelease: CompletableDeferred<Unit>? = null
        var openRelease: CompletableDeferred<Unit>? = null
        var blockPicker: String? = null; var pickerEntered = CompletableDeferred<Unit>(); var pickerCancelled = false
        val initial = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 1)),
            lyrics = frozenListOf(LyricLine("line", "Sing", 0, 960)))
        val engine = object : EnginePort {
            override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
            override suspend fun apply(command: EngineCommand) = true
            override fun snapshot() = TransportState(outputAttached = true)
        }
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = original.copyOf()
        }, object : ImportPort { override suspend fun import(location: Location) = asset }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) { saves++; check(!saveFails); saved = project }
            override suspend fun open(location: Location): Project { opens++; openedLocation = location; openRelease?.await(); return Project(id = "chosen", title = "Chosen project") }
        }, object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Unused")
        }, engine), initial)
        suspend fun picker(kind: String) {
            if (blockPicker == kind) {
                pickerEntered.complete(Unit)
                try { awaitCancellation() } finally { pickerCancelled = true }
            }
        }
        val presenter = ContinuousEditorPresenter(studio, scope, object : ContinuousEditorPorts {
            override val stepPatternsAvailable = true
            override val originalAvailable = monitorAvailable
            override val lyricFiles = object : LyricFiles {
                override suspend fun importLrc(): String? { picker("lyrics-import"); return null }
                override suspend fun exportLrc(text: String): Boolean { picker("lyrics-export"); return false }
            }
            override val libraryAvailable = true
            override suspend fun chooseLibrary(): Location? { picker("library"); return null }
            override suspend fun chooseAudio(): Location? = null
            override suspend fun chooseOpen(): Location { picker("open"); return Location("chosen") }
            override suspend fun chooseSave(): Location? { picker("save"); saveEntered?.complete(Unit); saveRelease?.await(); return saveChoice }
            override suspend fun chooseExport(frames: Long): ExportRequest? = null
            override suspend fun peaks(asset: Asset) = listOf(.5f)
            override fun readout() = ContinuousEditorReadout()
            override suspend fun setSongMonitorGain(gain: Float) = true
            override suspend fun stopOriginal(): Boolean { originalStops++; return true }
        })
        suspend fun until(condition: (ContinuousEditorState) -> Boolean) = withTimeout(5000) { while (!condition(presenter.state.value)) delay(5) }
        suspend fun close() { presenter.close(); studio.dispatch(Action.Close); scope.cancel() }
    }
}
